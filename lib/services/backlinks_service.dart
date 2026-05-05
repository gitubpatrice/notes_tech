/// Service métier des liens `[[Titre]]` entre notes.
///
/// Responsabilités :
///  - **Parsing** : extrait les `[[Titre]]` du contenu Markdown.
///  - **Indexation** : à chaque modification d'une note, recalcule
///    et persiste son set de liens sortants.
///  - **Résolution** : matching insensible à la casse / accents avec
///    les titres existants. Liens fantômes acceptés (tels quels en DB,
///    `target_id = NULL`).
///  - **Re-résolution** : à la création / renommage d'une note, les
///    liens fantômes pointant vers ce titre sont automatiquement attachés.
///
/// Sécurité :
///  - Cap sur la longueur du contenu parsé (= `noteContentIndexLimit`)
///    pour éviter tout coût catastrophique sur une note volumineuse.
///  - Le titre cible est tronqué à 200 caractères (limite UX raisonnable).
///  - Toute exception sur une passe est avalée et loguée (debug uniquement),
///    le service ne crashera jamais l'app.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/constants.dart';
import '../data/models/note.dart';
import '../data/models/note_link.dart';
import '../data/repositories/links_repository.dart';
import '../data/repositories/notes_repository.dart';
import '../utils/text_utils.dart';

class BacklinksService {
  BacklinksService({
    required NotesRepository notes,
    required LinksRepository links,
  })  : _notes = notes,
        _links = links;

  final NotesRepository _notes;
  final LinksRepository _links;

  /// Regex source : `[[ ... ]]` non gourmand, refuse les `[` et `]` à l'intérieur.
  /// Pas de capture sur plusieurs lignes (titre = une seule ligne).
  static final RegExp _linkRegex = RegExp(r'\[\[([^\[\]\n]{1,200})\]\]');

  /// Borne haute du nombre de liens extraits d'une seule note.
  /// Garde-fou contre un texte spam saturant la table `note_links`.
  static const int _maxLinksPerNote = 256;

  static const int _writeDebounce = 500; // ms

  StreamSubscription<void>? _notesSub;
  Timer? _debounceTimer;
  bool _disposed = false;

  /// Démarre l'écoute des modifications de notes pour réindexer en
  /// arrière-plan. Une indexation initiale complète est lancée.
  Future<void> start() async {
    _notesSub = _notes.changes.listen((_) => _scheduleReindexAll());
    unawaited(_reindexAll());
  }

  Future<void> dispose() async {
    _disposed = true;
    _debounceTimer?.cancel();
    _debounceTimer = null;
    await _notesSub?.cancel();
    _notesSub = null;
  }

  // ---------------------------------------------------------------------
  // API publique : parsing pur (utilisé par UI pour la preview/highlight).
  // ---------------------------------------------------------------------

  /// Extrait les liens `[[Titre]]` d'un texte. Pas de side-effects DB.
  /// Bornes :
  ///  - contenu tronqué à `noteContentIndexLimit` caractères ;
  ///  - max `_maxLinksPerNote` liens retenus (les suivants sont ignorés).
  /// Doublons éliminés (même titre normalisé apparu plusieurs fois → 1 entrée
  /// avec la première position rencontrée).
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

  /// Normalisation pour le matching de titres : lowercase + accents
  /// dépouillés + whitespace réduits. Idempotente, déterministe.
  static String normalizeTitle(String s) {
    final lower = s.toLowerCase();
    final buf = StringBuffer();
    for (var i = 0; i < lower.length; i++) {
      buf.writeCharCode(TextUtils.stripLatinDiacritic(lower.codeUnitAt(i)));
    }
    return buf.toString().replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  // ---------------------------------------------------------------------
  // Indexation
  // ---------------------------------------------------------------------

  void _scheduleReindexAll() {
    if (_disposed) return;
    _debounceTimer?.cancel();
    _debounceTimer = Timer(Duration(milliseconds: _writeDebounce), () {
      unawaited(_reindexAll());
    });
  }

  /// Réindexe toutes les notes vivantes.
  /// Idempotent — peut être rappelé sans dommage.
  Future<void> _reindexAll() async {
    if (_disposed) return;
    try {
      final notes = await _notes.listAllAlive();
      if (notes.isEmpty) return;

      // Map titre normalisé → id, pour résoudre les targetId.
      final byTitleNorm = <String, String>{
        for (final n in notes)
          if (n.title.isNotEmpty) normalizeTitle(n.title): n.id,
      };

      for (final n in notes) {
        if (_disposed) return;
        await _indexOne(n, byTitleNorm);
        // Yield à l'event loop entre notes.
        await Future<void>.delayed(Duration.zero);
      }
    } catch (e, st) {
      if (kDebugMode) {
        debugPrint('BacklinksService: reindexAll erreur — $e\n$st');
      }
    }
  }

  Future<void> _indexOne(Note n, Map<String, String> byTitleNorm) async {
    final extracted = extractFromContent(n.content);
    final links = <NoteLink>[];
    for (final e in extracted) {
      final targetId = byTitleNorm[e.titleNorm];
      // Empêche les self-links (note qui se cite elle-même via son titre).
      final resolvedId = targetId == n.id ? null : targetId;
      links.add(NoteLink(
        sourceId: n.id,
        targetId: resolvedId,
        targetTitle: e.title,
        targetTitleNorm: e.titleNorm,
        position: e.position,
      ));
    }
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
  /// (préfixe, insensible casse/accents). Capé à `limit`.
  Future<List<Note>> suggestTitles(
    String query, {
    int limit = 8,
    String? excludeId,
  }) async {
    final q = normalizeTitle(query);
    if (q.isEmpty) return const [];
    final notes = await _notes.listAllAlive();
    final out = <Note>[];
    for (final n in notes) {
      if (n.id == excludeId) continue;
      if (n.title.isEmpty) continue;
      final norm = normalizeTitle(n.title);
      if (norm.startsWith(q) || norm.contains(' $q')) {
        out.add(n);
        if (out.length >= limit) break;
      }
    }
    return out;
  }
}
