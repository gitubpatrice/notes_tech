/// Recherche par similarité cosinus sur les embeddings indexés.
///
/// - Charge les vecteurs en mémoire à la demande (lazy + cache).
/// - Encode la query, puis dot product (vecteurs L2-normalisés).
/// - Retourne le top-K avec score, joint aux notes via `getMany` (1 SELECT).
///
/// Stratégie de cache : la liste est rechargée si invalidée par
/// `IndexingService` (qu'on suit en interne dès la construction).
library;

import 'dart:async';
import 'dart:typed_data';

import '../core/constants.dart';
import '../data/models/note.dart';
import '../data/models/note_embedding.dart';
import '../data/repositories/embeddings_repository.dart';
import '../data/repositories/notes_repository.dart';
import '../utils/vector_math.dart';
import 'embedding/embedding_provider.dart';
import 'embedding/local_embedder.dart';
import 'indexing_service.dart';

class SemanticHit {
  const SemanticHit({required this.note, required this.score});
  final Note note;
  final double score;
}

class SemanticSearchService {
  SemanticSearchService({
    required NotesRepository notes,
    required EmbeddingsRepository embeddings,
    required EmbeddingProvider embedder,
    required IndexingService indexing,
  }) : _notes = notes,
       _embeddings = embeddings,
       _embedder = embedder {
    _indexSub = indexing.changes.listen((_) => invalidateCache());
  }

  final NotesRepository _notes;
  final EmbeddingsRepository _embeddings;
  EmbeddingProvider _embedder;
  late final StreamSubscription<void> _indexSub;

  List<NoteEmbedding>? _cache;
  Future<List<NoteEmbedding>>? _loading;

  /// Permet à `main.dart` de basculer l'embedder à chaud (Local → MiniLM).
  void setEmbedder(EmbeddingProvider next) {
    if (next.modelId == _embedder.modelId) return;
    _embedder = next;
    invalidateCache();
  }

  void invalidateCache() {
    _cache = null;
  }

  Future<void> dispose() async {
    await _indexSub.cancel();
    _cache = null;
  }

  Future<List<SemanticHit>> search(
    String query, {
    int limit = AppConstants.semanticSearchLimit,
    double minScore = 0.05,
  }) async {
    final q = query.trim();
    if (q.isEmpty) return const [];

    // v1.0.x : encodage de la query via `embedAsync` pour ne pas bloquer
    // le main thread (MiniLM ONNX ~30-600 ms selon device → jank au tap
    // sur S9/POCO C75). LocalEmbedder reste sync via wrap par défaut.
    final queryVec = await _encodeQueryAsync(q);
    final embeddings = await _ensureLoaded();
    if (embeddings.isEmpty) return const [];

    // Écarte les candidats inéligibles AVANT de borner le top-K.
    //
    // Filtrer après la borne les laissait consommer les places : 4 vecteurs
    // résiduels de notes verrouillées, ou 4 notes en corbeille, au-dessus
    // d'une note ordinaire pertinente, et cette dernière n'était jamais
    // hydratée ni renvoyée. Invisible côté utilisateur — la recherche rendait
    // simplement moins de résultats que prévu, sans rien signaler. Le RAG,
    // qui appelle avec une petite `limit`, était le plus exposé.
    //
    // Une seule requête sur la colonne `id`, à chaque recherche : l'état
    // (verrouillé, en corbeille) change en dehors du cache d'embeddings, on
    // ne peut donc pas le mémoriser avec lui.
    final ineligible = await _notes.listSemanticIneligibleIds();

    // Top-K via liste triée bornée.
    final scored = <_Scored>[];
    for (final e in embeddings) {
      if (e.dim != queryVec.length) continue;
      if (ineligible.contains(e.noteId)) continue;
      final score = VectorMath.cosineNormalized(queryVec, e.vector);
      if (!score.isFinite || score < minScore) continue;
      _insertTopK(scored, _Scored(e.noteId, score), limit);
    }
    if (scored.isEmpty) return const [];

    // Hydrate les notes en un seul SELECT, puis re-trie selon les scores.
    final ids = scored.map((s) => s.noteId).toList(growable: false);
    final notes = await _notes.getMany(ids);
    final byId = <String, Note>{for (final n in notes) n.id: n};
    final hits = <SemanticHit>[];
    for (final s in scored) {
      final note = byId[s.noteId];
      if (note != null && isEligibleHit(note)) {
        hits.add(SemanticHit(note: note, score: s.score));
      }
    }
    return hits;
  }

  /// Filtre d'admission d'un résultat sémantique, appliqué au point
  /// d'**ACCÈS** et pas seulement à l'affichage.
  ///
  /// Deuxième barrière : `search` écarte déjà ces notes avant de borner son
  /// top-K (sinon elles consommeraient les places). Celle-ci rattrape le cas
  /// où l'état change entre les deux requêtes — une note mise au coffre
  /// pendant la frappe.
  ///
  /// - `isTrashed` : une note en corbeille n'est plus un résultat.
  /// - `isLocked` : défense en profondeur. Une note de coffre ne doit
  ///   JAMAIS remonter d'une recherche sémantique, même si un embedding
  ///   calculé avant sa mise au coffre a survécu. Sans ce filtre, le
  ///   vecteur résiduel transforme la barre de recherche en **oracle** sur
  ///   le contenu du coffre : le seul classement renseigne l'attaquant,
  ///   sans qu'aucun texte ne soit affiché, et sans passphrase.
  ///   `FolderVaultService.purgePlaintextEmbedding` ferme la source ;
  ///   ce filtre ferme l'usage. Les deux sont nécessaires : le premier
  ///   dépend d'un appel que tout nouveau chemin de mise au coffre devra
  ///   penser à faire, le second est inconditionnel.
  static bool isEligibleHit(Note note) => !note.isTrashed && !note.isLocked;

  // ---------------------------------------------------------------------

  /// LocalEmbedder est sync léger ; MiniLmEmbedder délègue à un isolate
  /// worker via `embedAsync` (cf. `MiniLmIsolateWorker`).
  Future<Float32List> _encodeQueryAsync(String q) async {
    final embedder = _embedder;
    if (embedder is LocalEmbedder) {
      return embedder.embedTitleAndBody(title: q, body: q);
    }
    return embedder.embedAsync(q);
  }

  Future<List<NoteEmbedding>> _ensureLoaded() async {
    final cached = _cache;
    if (cached != null) return cached;
    return _loading ??= _embeddings
        .listByModel(_embedder.modelId)
        .then((list) {
          _cache = list;
          return list;
        })
        .whenComplete(() => _loading = null);
  }

  /// Insère un score dans une liste triée descendante de taille bornée.
  static void _insertTopK(List<_Scored> list, _Scored item, int k) {
    if (list.length < k) {
      _insertSorted(list, item);
      return;
    }
    if (item.score <= list.last.score) return;
    list.removeLast();
    _insertSorted(list, item);
  }

  static void _insertSorted(List<_Scored> list, _Scored item) {
    var lo = 0;
    var hi = list.length;
    while (lo < hi) {
      final mid = (lo + hi) >> 1;
      if (list[mid].score >= item.score) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    list.insert(lo, item);
  }
}

class _Scored {
  const _Scored(this.noteId, this.score);
  final String noteId;
  final double score;
}
