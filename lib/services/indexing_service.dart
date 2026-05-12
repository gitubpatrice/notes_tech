/// Service d'indexation des embeddings.
///
/// Garanties :
///   - **Idempotent** : ne (re)calcule que ce qui a changé (`sourceHash`).
///   - **Idle-driven** : se déclenche au démarrage et à chaque écriture
///     de note, debounced 1 s.
///   - **Coopératif** : encode une note, yield à l'event loop, encode la
///     suivante. Throttle paramétré par embedder pour rester fluide même
///     avec MiniLM (30-60 ms FFI par note sur S24).
///   - **Auto-purge** : supprime les embeddings orphelins (notes
///     définitivement supprimées) au cours de chaque passe.
///   - **Observable** : `progress` émet `(done, total)` pendant les passes
///     et `null` quand idle. `changes` notifie qu'un lot a été écrit.
///
/// Robustesse : un échec est logué (debug) et la passe se termine sans
/// faire crasher l'app. Le `_dirty` flag rejoue les écritures arrivées
/// pendant qu'une passe tournait.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/constants.dart';
import '../data/models/note.dart';
import '../data/models/note_change.dart';
import '../data/models/note_embedding.dart';
import '../data/repositories/embeddings_repository.dart';
import '../data/repositories/notes_repository.dart';
import '../utils/hash_utils.dart';
import 'embedding/embedding_provider.dart';
import 'embedding/local_embedder.dart';

/// Snapshot de progression d'une passe d'indexation.
@immutable
class IndexingProgress {
  const IndexingProgress({
    required this.done,
    required this.total,
    required this.modelId,
  });

  final int done;
  final int total;
  final String modelId;

  double get ratio => total == 0 ? 1.0 : done / total;
  bool get finished => done >= total;
}

class IndexingService {
  IndexingService({
    required NotesRepository notes,
    required EmbeddingsRepository embeddings,
    required EmbeddingProvider embedder,
  }) : _notes = notes,
       _embeddings = embeddings,
       _embedder = embedder;

  final NotesRepository _notes;
  final EmbeddingsRepository _embeddings;
  EmbeddingProvider _embedder;

  /// Debounce long pour limiter les passes pendant édition continue. Une
  /// frappe rapide (autosave 500 ms) déclencherait sinon une nouvelle
  /// passe à chaque écriture, créant un fond CPU permanent — particulièrement
  /// coûteux quand MiniLM est actif. 3 s laisse le temps à l'utilisateur de
  /// finir sa phrase tout en restant réactif côté recherche.
  static const Duration _writeDebounce = Duration(seconds: 3);

  StreamSubscription<NoteChangeEvent>? _changesSub;
  Timer? _debounceTimer;
  bool _running = false;
  bool _dirty = false;
  bool _disposed = false;
  final _indexChanges = StreamController<void>.broadcast();
  final _progress = StreamController<IndexingProgress?>.broadcast();
  IndexingProgress? _lastProgress;

  /// Émet à chaque passe d'indexation ayant écrit ou supprimé quelque chose.
  Stream<void> get changes => _indexChanges.stream;

  /// Émet `(done, total, modelId)` pendant une passe ; `null` quand idle.
  /// Le dernier état est conservé pour les nouveaux abonnés.
  Stream<IndexingProgress?> get progress => _progress.stream;
  IndexingProgress? get currentProgress => _lastProgress;

  /// Bascule l'encodeur à chaud (Local ↔ MiniLM). Attend la fin de la passe
  /// en cours pour éviter d'écrire des embeddings tagués avec un modelId
  /// après un purge déjà effectué. Purge ensuite et relance.
  Future<void> swapEmbedder(EmbeddingProvider next) async {
    if (next.modelId == _embedder.modelId) return;
    // Attente coopérative : laisse la passe courante s'achever.
    while (_running && !_disposed) {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }
    if (_disposed) return;
    _embedder = next;
    await _embeddings.purgeOtherModels(next.modelId);
    _scheduleRun();
  }

  /// À appeler une fois après instanciation.
  ///
  /// Le purge des modèles obsolètes est différé après le 1er frame pour
  /// ne pas bloquer le bootstrap (P11). L'écoute des changements démarre
  /// immédiatement : aucune écriture utilisateur n'est perdue car la
  /// passe d'indexation est de toute façon debouncée.
  Future<void> start() async {
    _changesSub = _notes.changes.listen((_) => _scheduleRun());
    unawaited(
      Future<void>.microtask(() async {
        if (_disposed) return;
        try {
          await _embeddings.purgeOtherModels(_embedder.modelId);
        } catch (e, st) {
          if (kDebugMode) {
            debugPrint('IndexingService: purgeOtherModels — $e\n$st');
          }
        }
        _scheduleRun();
      }),
    );
  }

  Future<void> dispose() async {
    _disposed = true;
    _debounceTimer?.cancel();
    _debounceTimer = null;
    await _changesSub?.cancel();
    _changesSub = null;
    if (!_indexChanges.isClosed) await _indexChanges.close();
    if (!_progress.isClosed) await _progress.close();
  }

  void _scheduleRun() {
    if (_disposed) return;
    _debounceTimer?.cancel();
    _debounceTimer = Timer(_writeDebounce, () => unawaited(_runOnce()));
  }

  Future<int> _runOnce() async {
    if (_disposed) return 0;
    if (_running) {
      _dirty = true;
      return 0;
    }
    _running = true;
    try {
      return await _indexAll();
    } catch (e, st) {
      if (kDebugMode) {
        debugPrint('IndexingService: passe en erreur — $e\n$st');
      }
      return 0;
    } finally {
      _running = false;
      _emitProgress(null);
      if (_dirty && !_disposed) {
        _dirty = false;
        unawaited(Future<void>.delayed(Duration.zero, _runOnce));
      }
    }
  }

  Future<int> _indexAll() async {
    final notes = await _notes.listAllAlive();
    final aliveIds = notes.map((n) => n.id).toSet();

    // 1) Orphans cleanup.
    final removed = await _embeddings.deleteOrphans(aliveIds);

    if (notes.isEmpty) {
      if (removed > 0 && !_indexChanges.isClosed) _indexChanges.add(null);
      return 0;
    }

    // 2) Diff avec ce qui est déjà indexé.
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

    // 3) Encode coopératif : 1 note → yield → écrire → délai → suivante.
    // L'embedder est capturé localement pour éviter qu'un swap concurrent
    // ne mélange deux modèles dans une même passe.
    final embedder = _embedder;
    final delay = embedder is LocalEmbedder
        ? AppConstants.indexingDelayLocal
        : AppConstants.indexingDelayMiniLm;

    // v1.0.7 perf H2 — pré-charge map id→Note pour le check race A2/F6.
    // Avant : `_notes.get(note.id)` séquentiel par itération = N round-trips
    // SQLCipher (~3-8 s cumulés sur 1000 notes S9). Maintenant une seule
    // passe `listAllAlive` partagée avec l'étape 1 sert aussi de référentiel
    // race-check.
    final liveById = {for (final n in notes) n.id: n};

    var done = 0;
    for (final note in toIndex) {
      if (_disposed || !identical(_embedder, embedder)) break;
      _emitProgress(
        IndexingProgress(
          done: done,
          total: toIndex.length,
          modelId: embedder.modelId,
        ),
      );
      // v1.0.7 perf C1 — `embedAsync` route vers le worker isolate quand
      // l'embedder le supporte (MiniLM). Avant : `embed()` sync bloquait
      // le main thread 30-60 ms par note (~15-30 s cumulés sur 500 notes).
      final emb = await _encodeWith(embedder, note);
      // A2 v1.0.4 (F6) — race vs hard-delete ou vault-isation concurrente.
      // Le check final via `_notes.get(note.id)` garde un round-trip ciblé
      // pour les notes qui ont VRAIMENT pu changer pendant l'encodage (la
      // map snapshot du début ne reflète pas l'état au moment du save).
      // Pour les notes intactes vis-à-vis du snapshot, on évite le SELECT.
      final snapshot = liveById[note.id];
      Note? live = snapshot;
      if (snapshot == null || snapshot.updatedAt != note.updatedAt) {
        live = await _notes.get(note.id);
      }
      if (live == null || live.encryptedContent != null) {
        done++;
        continue;
      }
      await _embeddings.save(emb);
      done++;
      if (delay > Duration.zero) {
        await Future<void>.delayed(delay);
      } else {
        await Future<void>.delayed(Duration.zero);
      }
    }

    _emitProgress(
      IndexingProgress(
        done: done,
        total: toIndex.length,
        modelId: _embedder.modelId,
      ),
    );
    if ((done > 0 || removed > 0) && !_indexChanges.isClosed) {
      _indexChanges.add(null);
    }
    return done;
  }

  void _emitProgress(IndexingProgress? p) {
    _lastProgress = p;
    if (!_progress.isClosed) _progress.add(p);
  }

  /// Encode une note avec l'embedder fourni. LocalEmbedder reste sync
  /// (pur Dart, ~µs). MiniLM est routé vers `embedAsync` qui déporte le
  /// calcul ONNX dans un worker isolate dédié — main thread fluide même
  /// pendant l'indexation initiale de centaines de notes.
  Future<NoteEmbedding> _encodeWith(EmbeddingProvider embedder, Note n) async {
    final body = _capContent(n.content);
    final Float32List vec;
    if (embedder is LocalEmbedder) {
      vec = embedder.embedTitleAndBody(title: n.title, body: body);
    } else {
      vec = await embedder.embedAsync('${n.title}\n\n$body');
    }
    return NoteEmbedding(
      noteId: n.id,
      vector: vec,
      dim: vec.length,
      modelId: embedder.modelId,
      sourceHash: _hashSource(n),
      updatedAt: DateTime.now(),
    );
  }

  /// Tronque le contenu à `noteContentIndexLimit` caractères pour éviter
  /// tout coût catastrophique sur une note volumineuse.
  static String _capContent(String s) {
    if (s.length <= AppConstants.noteContentIndexLimit) return s;
    return s.substring(0, AppConstants.noteContentIndexLimit);
  }

  /// Hash 32 bits déterministe de (title | content) avec sentinelle.
  static int _hashSource(Note n) =>
      HashUtils.fnv1a32Pair(n.title, _capContent(n.content));

  Future<int> indexedCount() => _embeddings.count(_embedder.modelId);

  String get currentModelId => _embedder.modelId;
  int get embeddingDim => _embedder.dim;
}
