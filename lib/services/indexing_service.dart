/// Service d'indexation des embeddings.
///
/// - **Idempotent** : ne recalcule que ce qui a changé (`sourceHash`).
/// - **Idle-driven** : se déclenche au démarrage et à chaque écriture de note,
///   debounced 1 s pour ne pas surcharger l'UI.
/// - **Coopératif** : traite par lots, yield à l'event loop entre lots.
/// - **Robuste** : un échec sur une note ne bloque pas la chaîne.
///
/// Pour un encodeur léger (LocalEmbedder), tout reste sur le main isolate
/// car l'encodage est < 1 ms par note. Si on bascule vers un modèle ONNX
/// lourd à v0.2.1, il suffira de remplacer `_encode` par un `Isolate.run`.
library;

import 'dart:async';

import '../core/constants.dart';
import '../data/models/note.dart';
import '../data/models/note_embedding.dart';
import '../data/repositories/embeddings_repository.dart';
import '../data/repositories/notes_repository.dart';
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
  final EmbeddingProvider _embedder;

  static const int _batchSize = 32;
  static const Duration _writeDebounce = Duration(seconds: 1);

  StreamSubscription<void>? _changesSub;
  Timer? _debounceTimer;
  bool _running = false;
  bool _disposed = false;
  final _indexChanges = StreamController<void>.broadcast();

  /// Émet à chaque passe d'indexation ayant écrit quelque chose.
  Stream<void> get changes => _indexChanges.stream;

  /// À appeler une fois après instanciation.
  Future<void> start() async {
    // Au boot : purge des embeddings d'autres modèles, puis première passe.
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
    await _indexChanges.close();
  }

  void _scheduleRun() {
    if (_disposed) return;
    _debounceTimer?.cancel();
    _debounceTimer = Timer(_writeDebounce, () {
      // Fire-and-forget. Toute exception est avalée silencieusement
      // (l'indexeur ne doit jamais crasher l'app).
      unawaited(_runOnce());
    });
  }

  /// Une passe d'indexation. Si une passe est déjà en cours, no-op.
  /// Retourne le nombre de notes (re)indexées.
  Future<int> _runOnce() async {
    if (_running || _disposed) return 0;
    _running = true;
    try {
      return await _indexAll();
    } catch (_) {
      return 0;
    } finally {
      _running = false;
    }
  }

  Future<int> _indexAll() async {
    // 1) Liste plate de toutes les notes vivantes (non corbeille, non archive).
    final notes = await _notes.listAllAlive();
    if (notes.isEmpty) return 0;

    // 2) Hash de source connu côté DB pour décider quoi recalculer.
    final knownHashes =
        await _embeddings.sourceHashes(_embedder.modelId);

    final toIndex = <Note>[];
    for (final n in notes) {
      final h = _hashSource(n);
      if (knownHashes[n.id] != h) toIndex.add(n);
    }
    if (toIndex.isEmpty) return 0;

    // 3) Encode + écrit en lots.
    var done = 0;
    for (var start = 0;
        start < toIndex.length && !_disposed;
        start += _batchSize) {
      final end = (start + _batchSize).clamp(0, toIndex.length);
      final batch = toIndex.sublist(start, end);
      final embeddings = batch.map(_encode).toList(growable: false);
      await _embeddings.saveAll(embeddings);
      done += batch.length;
      // Yield à l'event loop : conserve la fluidité UI.
      await Future<void>.delayed(Duration.zero);
    }
    if (done > 0 && !_indexChanges.isClosed) _indexChanges.add(null);
    return done;
  }

  NoteEmbedding _encode(Note n) {
    final embedder = _embedder;
    final vec = embedder is LocalEmbedder
        ? embedder.embedTitleAndBody(title: n.title, body: n.content)
        : embedder.embed('${n.title}\n\n${n.content}');
    return NoteEmbedding(
      noteId: n.id,
      vector: vec,
      dim: vec.length,
      modelId: embedder.modelId,
      sourceHash: _hashSource(n),
      updatedAt: DateTime.now(),
    );
  }

  /// Hash 32 bits de (title|content). FNV-1a, déterministe, rapide.
  static int _hashSource(Note n) {
    const offset = 0x811c9dc5;
    const prime = 0x01000193;
    var h = offset;
    void mix(String s) {
      for (var i = 0; i < s.length; i++) {
        h ^= s.codeUnitAt(i) & 0xFF;
        h = (h * prime) & 0xFFFFFFFF;
      }
    }

    mix(n.title);
    mix('');
    mix(n.content);
    return h;
  }

  /// Pour usage debug.
  Future<int> indexedCount() =>
      _embeddings.count(_embedder.modelId);

  String get currentModelId => _embedder.modelId;
  int get embeddingDim => AppConstants.embeddingDim;
}
