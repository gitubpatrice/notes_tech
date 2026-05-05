/// Service d'indexation des embeddings.
///
/// - **Idempotent** : ne (re)calcule que ce qui a changé (`sourceHash`).
/// - **Idle-driven** : se déclenche au démarrage et à chaque écriture de note,
///   debounced 1 s pour ne pas surcharger l'UI.
/// - **Coopératif** : traite par lots, yield à l'event loop entre lots.
/// - **Robuste** : un échec sur une passe ne bloque pas la chaîne ;
///   un `_dirty` est posé quand une passe est demandée pendant qu'une autre tourne.
/// - **Auto-purge** : supprime les embeddings orphelins (notes définitivement
///   supprimées) au cours de chaque passe.
///
/// Pour MiniLM (encodage 30-60 ms/note), un déport en isolate est prévu pour
/// v0.3 ; à ce stade, on borne le batch et on insère des yields entre items
/// pour limiter le jank.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/constants.dart';
import '../data/models/note.dart';
import '../data/models/note_embedding.dart';
import '../data/repositories/embeddings_repository.dart';
import '../data/repositories/notes_repository.dart';
import '../utils/hash_utils.dart';
import 'embedding/embedding_provider.dart';
import 'embedding/local_embedder.dart';

class IndexingService {
  IndexingService({
    required NotesRepository notes,
    required EmbeddingsRepository embeddings,
    required EmbeddingProvider embedder,
  })  : _notes = notes,
        _embeddings = embeddings,
        _embedder = embedder;

  final NotesRepository _notes;
  final EmbeddingsRepository _embeddings;
  EmbeddingProvider _embedder;

  static const int _batchSize = 16;
  static const Duration _writeDebounce = Duration(seconds: 1);

  StreamSubscription<void>? _changesSub;
  Timer? _debounceTimer;
  bool _running = false;
  bool _dirty = false;
  bool _disposed = false;
  final _indexChanges = StreamController<void>.broadcast();

  /// Émet à chaque passe d'indexation ayant écrit ou supprimé quelque chose.
  Stream<void> get changes => _indexChanges.stream;

  /// Permet de basculer d'encodeur à chaud (Local → MiniLM lorsque le warmUp
  /// asynchrone se termine). Déclenche une réindexation complète.
  Future<void> swapEmbedder(EmbeddingProvider next) async {
    if (next.modelId == _embedder.modelId) return;
    _embedder = next;
    await _embeddings.purgeOtherModels(next.modelId);
    _scheduleRun();
  }

  /// À appeler une fois après instanciation.
  Future<void> start() async {
    await _embeddings.purgeOtherModels(_embedder.modelId);
    _changesSub = _notes.changes.listen((_) => _scheduleRun());
    _scheduleRun();
  }

  Future<void> dispose() async {
    _disposed = true;
    _debounceTimer?.cancel();
    _debounceTimer = null;
    await _changesSub?.cancel();
    _changesSub = null;
    if (!_indexChanges.isClosed) await _indexChanges.close();
  }

  void _scheduleRun() {
    if (_disposed) return;
    _debounceTimer?.cancel();
    _debounceTimer = Timer(_writeDebounce, () {
      unawaited(_runOnce());
    });
  }

  Future<int> _runOnce() async {
    if (_disposed) return 0;
    if (_running) {
      _dirty = true;
      return 0;
    }
    _running = true;
    try {
      final n = await _indexAll();
      // Si une demande est arrivée pendant la passe, en relance une nouvelle.
      if (_dirty && !_disposed) {
        _dirty = false;
        unawaited(Future<void>.delayed(Duration.zero, _runOnce));
      }
      return n;
    } catch (e, st) {
      if (kDebugMode) {
        debugPrint('IndexingService: passe en erreur — $e\n$st');
      }
      return 0;
    } finally {
      _running = false;
    }
  }

  Future<int> _indexAll() async {
    final notes = await _notes.listAllAlive();
    final aliveIds = notes.map((n) => n.id).toSet();

    // 1) Orphans : embeddings dont la note est partie.
    final removed = await _embeddings.deleteOrphans(aliveIds);

    if (notes.isEmpty) {
      if (removed > 0 && !_indexChanges.isClosed) _indexChanges.add(null);
      return 0;
    }

    // 2) Hashes connus côté DB pour décider quoi recalculer.
    final knownHashes = await _embeddings.sourceHashes(_embedder.modelId);

    final toIndex = <Note>[];
    for (final n in notes) {
      final h = _hashSource(n);
      if (knownHashes[n.id] != h) toIndex.add(n);
    }

    if (toIndex.isEmpty) {
      if (removed > 0 && !_indexChanges.isClosed) _indexChanges.add(null);
      return 0;
    }

    // 3) Encode + écrit en lots.
    var done = 0;
    for (var start = 0;
        start < toIndex.length && !_disposed;
        start += _batchSize) {
      final end = (start + _batchSize).clamp(0, toIndex.length);
      final batch = toIndex.sublist(start, end);
      final out = <NoteEmbedding>[];
      for (final n in batch) {
        out.add(_encode(n));
        // Yield entre chaque encodage MiniLM pour conserver la fluidité UI.
        await Future<void>.delayed(Duration.zero);
      }
      await _embeddings.saveAll(out);
      done += batch.length;
    }
    if ((done > 0 || removed > 0) && !_indexChanges.isClosed) {
      _indexChanges.add(null);
    }
    return done;
  }

  NoteEmbedding _encode(Note n) {
    final embedder = _embedder;
    final body = _capContent(n.content);
    final vec = embedder is LocalEmbedder
        ? embedder.embedTitleAndBody(title: n.title, body: body)
        : embedder.embed('${n.title}\n\n$body');
    return NoteEmbedding(
      noteId: n.id,
      vector: vec,
      dim: vec.length,
      modelId: embedder.modelId,
      sourceHash: _hashSource(n),
      updatedAt: DateTime.now(),
    );
  }

  /// Tronque le contenu à `noteContentIndexLimit` caractères.
  /// Évite tout coût catastrophique sur une note volumineuse importée.
  static String _capContent(String s) {
    if (s.length <= AppConstants.noteContentIndexLimit) return s;
    return s.substring(0, AppConstants.noteContentIndexLimit);
  }

  /// Hash 32 bits déterministe de (title | content) avec séparateur sentinelle.
  static int _hashSource(Note n) =>
      HashUtils.fnv1a32Pair(n.title, _capContent(n.content));

  /// Pour usage debug / about screen.
  Future<int> indexedCount() => _embeddings.count(_embedder.modelId);

  String get currentModelId => _embedder.modelId;
  int get embeddingDim => _embedder.dim;
}
