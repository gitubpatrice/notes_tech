/// Recherche par similarité cosinus sur les embeddings indexés.
///
/// - Charge les vecteurs en mémoire à la demande (lazy + cache).
/// - Recalcule la query → vecteur, puis dot product (vecteurs L2-normalisés).
/// - Retourne le top-K avec score, joint aux notes via NotesRepository.
///
/// Stratégie de cache : la liste des embeddings est rechargée si la table
/// a vu des écritures depuis le dernier load. On ré-écoute le stream
/// `embeddingsChanged` (cf. IndexingService) pour invalider.
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
  })  : _notes = notes,
        _embeddings = embeddings,
        _embedder = embedder;

  final NotesRepository _notes;
  final EmbeddingsRepository _embeddings;
  final EmbeddingProvider _embedder;

  List<NoteEmbedding>? _cache;
  Future<List<NoteEmbedding>>? _loading;

  /// Invalide le cache (appelé après modifications de l'index).
  void invalidateCache() {
    _cache = null;
  }

  Future<List<SemanticHit>> search(
    String query, {
    int limit = AppConstants.semanticSearchLimit,
    double minScore = 0.05,
  }) async {
    final q = query.trim();
    if (q.isEmpty) return const [];

    final queryVec = _encodeQuery(q);
    final embeddings = await _ensureLoaded();
    if (embeddings.isEmpty) return const [];

    // Top-K via min-heap léger (liste triée maintenue de taille `limit`).
    final scored = <_Scored>[];
    for (final e in embeddings) {
      if (e.dim != queryVec.length) continue;
      final score = VectorMath.cosineNormalized(queryVec, e.vector);
      if (score < minScore) continue;
      _insertTopK(scored, _Scored(e.noteId, score), limit);
    }
    if (scored.isEmpty) return const [];

    // Hydrate les notes (ordre préservé).
    final hits = <SemanticHit>[];
    for (final s in scored) {
      final note = await _notes.get(s.noteId);
      if (note != null && !note.isTrashed) {
        hits.add(SemanticHit(note: note, score: s.score));
      }
    }
    return hits;
  }

  // ---------------------------------------------------------------------

  Float32List _encodeQuery(String q) {
    final embedder = _embedder;
    return embedder is LocalEmbedder
        ? embedder.embedTitleAndBody(title: q, body: q)
        : embedder.embed(q);
  }

  Future<List<NoteEmbedding>> _ensureLoaded() async {
    final cached = _cache;
    if (cached != null) return cached;
    return _loading ??= _embeddings
        .listByModel(_embedder.modelId)
        .then((list) {
      _cache = list;
      return list;
    }).whenComplete(() => _loading = null);
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
