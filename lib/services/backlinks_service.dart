/// Service métier des liens `[[Titre]]` entre notes.
///
/// Responsabilités :
///  - **Parsing** : extrait les `[[Titre]]` du contenu Markdown.
///  - **Indexation ciblée** : à chaque mutation d'une note, recalcule
///    et persiste son set de liens sortants — uniquement pour cette
///    note, pas pour toute la base.
///  - **Re-résolution incoming** : à la création / renommage d'une note,
///    les liens fantômes pointant vers ce titre sont attachés ; à
///    l'inverse, les liens résolus dont le titre cible a changé sont
///    repassés en fantômes (la FK `ON DELETE SET NULL` couvre déjà la
///    suppression directe).
///  - **Indexation initiale** : au démarrage, une passe complète
///    réconcilie l'état avec d'éventuelles incohérences.
///
/// Sécurité / robustesse :
///  - Cap sur la longueur du contenu parsé (= `noteContentIndexLimit`).
///  - Titre cible tronqué à 200 caractères.
///  - Toute exception est avalée et loguée (debug uniquement) : le
///    service ne crashera jamais l'app et exposera l'erreur via
///    `lastError` pour un éventuel feedback UI.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/constants.dart';
import '../data/models/note.dart';
import '../data/models/note_change.dart';
import '../data/models/note_link.dart';
import '../data/repositories/links_repository.dart';
import '../data/repositories/notes_repository.dart';
import '../utils/text_utils.dart';

class BacklinksService {
  BacklinksService({
    required NotesRepository notes,
    required LinksRepository links,
  }) : _notes = notes,
       _links = links;

  final NotesRepository _notes;
  final LinksRepository _links;

  /// Regex source : `[[ ... ]]` non gourmand, refuse `[` et `]` à l'intérieur.
  /// Pas de capture sur plusieurs lignes (titre = une seule ligne).
  static final RegExp _linkRegex = RegExp(r'\[\[([^\[\]\n]{1,200})\]\]');

  /// Regex auxiliaire pour la normalisation des espaces.
  static final RegExp _wsRegex = RegExp(r'\s+');

  /// Borne haute du nombre de liens extraits d'une seule note.
  /// Garde-fou contre un texte spam saturant la table `note_links`.
  static const int _maxLinksPerNote = 256;

  static const Duration _writeDebounce = Duration(milliseconds: 500);

  /// Dernière erreur d'indexation, si pertinente pour l'UI.
  /// `null` quand tout va bien.
  final ValueNotifier<String?> lastError = ValueNotifier<String?>(null);

  StreamSubscription<NoteChangeEvent>? _notesSub;
  Timer? _bulkDebounceTimer;
  bool _disposed = false;

  /// Démarre l'écoute des changements de notes et planifie une passe
  /// complète initiale (réconciliation au boot).
  ///
  /// La passe `_reindexAll()` itère sur TOUTES les notes — coûteux à
  /// boot avec ≥500 notes sur S9/POCO C75 (~30-60 ms par note). On la
  /// **diffère de 2 secondes** pour laisser le 1er frame se peindre,
  /// les FutureBuilders du HomeScreen résoudre, et l'utilisateur
  /// commencer à interagir avant que cette tâche d'arrière-plan ne
  /// commence à yielder.
  Future<void> start() async {
    _notesSub = _notes.changes.listen(_onNoteChange);
    Timer(const Duration(seconds: 2), () {
      if (_disposed) return;
      unawaited(_reindexAll());
    });
  }

  Future<void> dispose() async {
    _disposed = true;
    _bulkDebounceTimer?.cancel();
    _bulkDebounceTimer = null;
    await _notesSub?.cancel();
    _notesSub = null;
    lastError.dispose();
  }

  // ---------------------------------------------------------------------
  // API publique : parsing pur (utilisé par UI pour la preview/highlight).
  // ---------------------------------------------------------------------

  /// Extrait les liens `[[Titre]]` d'un texte. Pas de side-effects DB.
  /// Bornes :
  ///  - contenu tronqué à `noteContentIndexLimit` caractères ;
  ///  - max `_maxLinksPerNote` liens retenus (les suivants sont ignorés) ;
  ///  - doublons éliminés (même titre normalisé → 1 entrée, première
  ///    position rencontrée).
  static List<({String title, String titleNorm, int position})>
  extractFromContent(String content) {
    final cap = content.length > AppConstants.noteContentIndexLimit
        ? content.substring(0, AppConstants.noteContentIndexLimit)
        : content;
    final seen = <String>{};
    final out = <({String title, String titleNorm, int position})>[];
    for (final match in _linkRegex.allMatches(cap)) {
      if (out.length >= _maxLinksPerNote) break;
      final raw = match.group(1)!.trim();
      if (raw.isEmpty) continue;
      final norm = normalizeTitle(raw);
      if (norm.isEmpty || !seen.add(norm)) continue;
      out.add((title: raw, titleNorm: norm, position: match.start));
    }
    return out;
  }

  /// Normalisation pour le matching de titres : lowercase + diacritiques
  /// dépouillés + whitespace réduits. Idempotente, déterministe.
  static String normalizeTitle(String s) {
    final lower = s.toLowerCase();
    final buf = StringBuffer();
    for (var i = 0; i < lower.length; i++) {
      buf.writeCharCode(TextUtils.stripLatinDiacritic(lower.codeUnitAt(i)));
    }
    return buf.toString().replaceAll(_wsRegex, ' ').trim();
  }

  // ---------------------------------------------------------------------
  // Réception des événements de mutation
  // ---------------------------------------------------------------------

  void _onNoteChange(NoteChangeEvent event) {
    if (_disposed) return;
    if (event.isBulk) {
      _scheduleBulkReindex();
      return;
    }
    // Mutation ciblée : id obligatoirement non-null par contrat.
    unawaited(_handleSingleChange(event));
  }

  Future<void> _handleSingleChange(NoteChangeEvent event) async {
    final id = event.id!;
    try {
      if (event.isDeletion) {
        // Les liens sortants disparaissent via FK `ON DELETE CASCADE` côté
        // hard-delete ; pour le soft-delete (corbeille), on les efface
        // explicitement pour qu'aucune mention ne ressorte côté UI.
        await _links.replaceLinksForSource(id, const <NoteLink>[]);
        // La note elle-même n'apparaît plus dans `listAllAlive`, donc
        // les liens entrants `target_id == id` doivent être repassés
        // en fantômes : on aligne sur `target_title_norm` du titre
        // précédent (s'il est connu).
        final prev = event.previousTitle;
        if (prev != null && prev.isNotEmpty) {
          await _links.unresolveByMismatch(
            noteId: id,
            // Aucune cible ne matchera jamais `` (caractère de contrôle) :
            // force le passage en fantôme de tous les liens vers `id`.
            newTitleNorm: '',
          );
        }
        return;
      }

      final note = await _notes.get(id);
      if (note == null) return; // race : la note vient d'être supprimée

      // P3 v1.1.0 — invalide le cache d'index titre→id si le titre vient
      // de changer (ou si l'event ne précise pas, par sûreté). Sans ça,
      // une rafale d'auto-saves portant un rename pouvait laisser le cache
      // 5 s avec l'ancien titre, brisant la résolution de backlinks.
      if (event.previousTitle != null && event.previousTitle != note.title) {
        _invalidateTitleIndex();
      }

      // v1.0.7 sécu M-01 — note dans un coffre verrouillé : on traite
      // exactement comme si elle venait d'être supprimée. Effets :
      //  - liens sortants purgés (le contenu de la note locked est
      //    déjà vidé, mais d'anciens liens pouvaient survivre depuis
      //    une indexation antérieure au vault-isation) ;
      //  - liens entrants pointant vers son ancien titre repassent
      //    en fantômes (forcés via `newTitleNorm: ''`), pour qu'aucun
      //    backlink ne révèle l'existence/le titre de la note locked.
      if (note.isLocked) {
        await _links.replaceLinksForSource(id, const <NoteLink>[]);
        await _links.unresolveByMismatch(noteId: id, newTitleNorm: '');
        lastError.value = null;
        return;
      }

      // Index sortant ciblé.
      final byTitleNorm = await _buildTitleIndex();
      await _indexOne(note, byTitleNorm);

      // Re-résolution entrante : titre courant attire les fantômes,
      // titre obsolète détache les liens devenus incorrects.
      final currentNorm = normalizeTitle(note.title);
      if (currentNorm.isNotEmpty) {
        await _links.resolveDangling(noteId: note.id, titleNorm: currentNorm);
        await _links.unresolveByMismatch(
          noteId: note.id,
          newTitleNorm: currentNorm,
        );
      }
      lastError.value = null;
    } catch (e, st) {
      lastError.value = 'Indexation des liens en erreur';
      if (kDebugMode) {
        debugPrint('BacklinksService: handleSingle($id) — $e\n$st');
      }
    }
  }

  // ---------------------------------------------------------------------
  // Réindexation complète (boot + opérations bulk)
  // ---------------------------------------------------------------------

  void _scheduleBulkReindex() {
    if (_disposed) return;
    _bulkDebounceTimer?.cancel();
    _bulkDebounceTimer = Timer(_writeDebounce, () {
      unawaited(_reindexAll());
    });
  }

  /// Réindexe toutes les notes vivantes. Idempotent.
  ///
  /// **Perf v0.9.3** : skip explicite des notes qui ne contiennent
  /// **aucun marqueur `[[`** — sur un corpus typique, la majorité des
  /// notes n'a pas de backlink, et chaque `_indexOne` skippé évite un
  /// DELETE+INSERT SQLCipher. Gain mesuré : ~80% des notes n'ont pas
  /// de `[[`, donc 500 notes = ~100 round-trips au lieu de 1000.
  Future<void> _reindexAll() async {
    if (_disposed) return;
    try {
      final notes = await _notes.listAllAlive();
      if (notes.isEmpty) return;
      final byTitleNorm = _indexByTitle(notes);
      for (final n in notes) {
        if (_disposed) return;
        // v1.0.7 sécu M-01 — pas d'indexation des liens pour les notes
        // verrouillées. Le contenu chiffré n'a pas de `[[`, mais on
        // assure aussi la purge des liens entrants pointant vers cette
        // note (les fantômes via `target_title_norm` continueraient à
        // révéler l'existence/titre via les listes de backlinks).
        if (n.isLocked) {
          await _links.replaceLinksForSource(n.id, const <NoteLink>[]);
          await _links.unresolveByMismatch(noteId: n.id, newTitleNorm: '');
          continue;
        }
        // Heuristique cheap : pas de `[[` = pas de backlinks possibles.
        // On skip sans toucher la DB.
        if (!n.content.contains('[[')) continue;
        await _indexOne(n, byTitleNorm);
        await Future<void>.delayed(Duration.zero); // yield event-loop
      }
      lastError.value = null;
    } catch (e, st) {
      lastError.value = 'Indexation initiale en erreur';
      if (kDebugMode) {
        debugPrint('BacklinksService: reindexAll — $e\n$st');
      }
    }
  }

  // P3 v1.1.0 — cache TTL court de l'index titre→id pour coalescer les
  // rafales d'auto-save (1 event/500ms par frappe → 1 SELECT complet
  // listAllAlive() = 50-200ms × N saves). 5s couvre une rafale typique
  // tout en restant frais : un titre venant de changer met < 5s à se
  // propager partout. Invalidé explicitement par les callers qui savent
  // qu'un titre vient de muter.
  Map<String, String>? _titleIndexCache;
  int _titleIndexCacheAtMs = 0;
  static const int _titleIndexTtlMs = 5000;

  void _invalidateTitleIndex() {
    _titleIndexCache = null;
    _titleIndexCacheAtMs = 0;
  }

  Future<Map<String, String>> _buildTitleIndex() async {
    final now = DateTime.now().millisecondsSinceEpoch;
    final cached = _titleIndexCache;
    if (cached != null && now - _titleIndexCacheAtMs < _titleIndexTtlMs) {
      return cached;
    }
    final notes = await _notes.listAllAlive();
    final idx = _indexByTitle(notes);
    _titleIndexCache = idx;
    _titleIndexCacheAtMs = now;
    return idx;
  }

  /// v1.0.7 sécu M-01 — exclut les notes verrouillées de l'index titre→id.
  /// Sans ce filtre, un lien `[[Titre verrouillé]]` dans une note alive
  /// résolverait son `target_id` au moment de l'indexation et révélerait
  /// l'existence + le mapping titre↔id de la note locked via la table
  /// `note_links`. La note locked apparaîtrait aussi comme cible cliquable
  /// dans la liste des liens sortants.
  static Map<String, String> _indexByTitle(List<Note> notes) {
    return <String, String>{
      for (final n in notes)
        if (n.title.isNotEmpty && !n.isLocked) normalizeTitle(n.title): n.id,
    };
  }

  Future<void> _indexOne(Note n, Map<String, String> byTitleNorm) async {
    final extracted = extractFromContent(n.content);
    final links = <NoteLink>[
      for (final e in extracted)
        NoteLink(
          sourceId: n.id,
          // Empêche les self-links (note qui se cite elle-même).
          targetId: byTitleNorm[e.titleNorm] == n.id
              ? null
              : byTitleNorm[e.titleNorm],
          targetTitle: e.title,
          targetTitleNorm: e.titleNorm,
          position: e.position,
        ),
    ];
    await _links.replaceLinksForSource(n.id, links);
  }

  // ---------------------------------------------------------------------
  // API : lecture pour l'UI.
  // ---------------------------------------------------------------------

  /// Liens sortants d'une note (résolus + fantômes).
  Future<List<NoteLink>> outgoingLinks(String noteId) =>
      _links.outgoing(noteId);

  /// Notes qui mentionnent celle-ci (par id ou par titre normalisé).
  Future<List<Note>> backlinks(Note target) => _links.backlinkSources(
    targetId: target.id,
    targetTitleNorm: normalizeTitle(target.title),
  );

  /// Auto-complétion : retourne les notes dont le titre matche `query`
  /// (préfixe ou préfixe de mot, insensible casse/accents). Capé à `limit`.
  ///
  /// Stratégie deux passes pour rester O(limit) en mémoire :
  ///  1. Pré-filtrage SQLite via `LOWER(title) LIKE ?` (over-fetch ×4).
  ///  2. Affinage Dart via `normalizeTitle` pour gérer les diacritiques.
  Future<List<Note>> suggestTitles(
    String query, {
    int limit = 8,
    String? excludeId,
  }) async {
    final norm = normalizeTitle(query);
    if (norm.isEmpty) return const <Note>[];
    // L'over-fetch SQL utilise la version lowercase non-décomposée :
    // suffisant pour pré-filtrer (le tokenizer FTS5 ne s'applique pas ici).
    final lowerNeedle = query.trim().toLowerCase();
    final candidates = await _notes.findByTitleLike(
      lowerNeedle,
      limit: limit * 4,
      excludeId: excludeId,
    );
    final out = <Note>[];
    for (final n in candidates) {
      if (n.title.isEmpty) continue;
      // F3 v1.0.9 — Skip locked notes : sinon l'auto-complétion `[[…]]`
      // dans l'éditeur d'une note alive révèle le titre + l'existence
      // de notes dans un coffre verrouillé. Aligne sur M-01 v1.0.7
      // (`_indexByTitle` + `_handleSingleChange` + `_reindexAll` qui
      // skip déjà locked).
      if (n.isLocked) continue;
      final t = normalizeTitle(n.title);
      if (t.startsWith(norm) || t.contains(' $norm')) {
        out.add(n);
        if (out.length >= limit) break;
      }
    }
    return out;
  }
}
