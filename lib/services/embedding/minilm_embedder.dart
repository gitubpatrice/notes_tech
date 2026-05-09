/// Encodeur sémantique on-device basé sur `all-MiniLM-L6-v2` quantifié int8.
///
/// Pipeline :
///   texte → BertTokenizer → input_ids/attention_mask/token_type_ids
///        → onnxruntime session.run
///        → last_hidden_state [1, seq_len, 384]
///        → mean pooling pondéré par attention_mask
///        → L2-normalisation
///        → Float32List (384)
///
/// Init : extraction du modèle depuis `assets/models/` vers le sandbox app
/// (les API ONNX prennent un chemin de fichier, pas un buffer Asset).
/// L'extraction n'a lieu qu'au premier `warmUp`.
library;

import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/services.dart' show AssetManifest, rootBundle;
import 'package:onnxruntime/onnxruntime.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../utils/vector_math.dart';
import 'bert_tokenizer.dart';
import 'embedding_provider.dart';
import 'minilm_isolate_worker.dart';

class MiniLmEmbedder implements EmbeddingProvider {
  MiniLmEmbedder({
    String modelAsset = 'assets/models/all-MiniLM-L6-v2-quant.onnx',
    String tokenizerAsset = 'assets/models/tokenizer.json',
    int maxSequenceLength = 128,
  }) : _modelAsset = modelAsset,
       _tokenizerAsset = tokenizerAsset,
       _maxSeqLen = maxSequenceLength;

  final String _modelAsset;
  final String _tokenizerAsset;
  final int _maxSeqLen;

  static const String _modelId = 'minilm-l6-v2-quant';
  static const int _dim = 384;

  OrtSession? _session;
  BertTokenizer? _tokenizer;
  bool _warmedUp = false;
  Completer<void>? _warmUpInFlight;

  /// Worker isolate pour `embedAsync` (queries interactives sans jank).
  /// Spawné lazily au premier `embedAsync` après `warmUp`. La passe
  /// d'indexation initiale (cooperative loop avec `Future.delayed`) reste
  /// sur `embed` sync — re-spawn par batch, plus économe.
  MiniLmIsolateWorker? _worker;
  Completer<MiniLmIsolateWorker>? _workerInFlight;

  @override
  String get modelId => _modelId;

  @override
  int get dim => _dim;

  // ---------------------------------------------------------------------
  // Init / dispose
  // ---------------------------------------------------------------------

  /// Vérifie la présence des assets via `AssetManifest` — zéro octet lu.
  static Future<bool> assetsAvailable({
    String modelAsset = 'assets/models/all-MiniLM-L6-v2-quant.onnx',
    String tokenizerAsset = 'assets/models/tokenizer.json',
  }) async {
    try {
      final manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
      final keys = manifest.listAssets().toSet();
      return keys.contains(modelAsset) && keys.contains(tokenizerAsset);
    } catch (_) {
      return false;
    }
  }

  @override
  Future<void> warmUp() async {
    if (_warmedUp) return;
    final inFlight = _warmUpInFlight;
    if (inFlight != null) return inFlight.future;
    final completer = Completer<void>();
    _warmUpInFlight = completer;
    try {
      OrtEnv.instance.init();
      // Chargement parallèle : tokenizer JSON + extraction modèle disque.
      final results = await Future.wait<Object>([
        BertTokenizer.loadFromAsset(_tokenizerAsset),
        _ensureModelOnDisk(),
      ]);
      final tokenizer = results[0] as BertTokenizer;
      final modelPath = results[1] as String;

      final sessionOptions = OrtSessionOptions();
      final session = OrtSession.fromFile(File(modelPath), sessionOptions);

      _tokenizer = tokenizer;
      _session = session;
      _warmedUp = true;
      completer.complete();
    } catch (e, st) {
      completer.completeError(e, st);
      rethrow;
    } finally {
      _warmUpInFlight = null;
    }
  }

  @override
  Future<void> dispose() async {
    final worker = _worker;
    _worker = null;
    if (worker != null) {
      await worker.dispose();
    }
    _session?.release();
    _session = null;
    _tokenizer = null;
    _warmedUp = false;
  }

  /// [embedAsync] : déporte le calcul ONNX dans un worker isolate
  /// persistant (cf. [MiniLmIsolateWorker]). Le main thread reste fluide
  /// pendant que le worker calcule (30-600 ms selon device).
  ///
  /// Le worker est spawné paresseusement au premier appel ; la session
  /// principale (utilisée par [embed] sync) reste indépendante pour
  /// les passes d'indexation cooperatives qui yieldent déjà entre notes.
  @override
  Future<Float32List> embedAsync(String text) async {
    if (text.isEmpty) return Float32List(_dim);
    final worker = await _ensureWorker();
    return worker.embed(text);
  }

  Future<MiniLmIsolateWorker> _ensureWorker() async {
    final existing = _worker;
    if (existing != null) return existing;
    final inFlight = _workerInFlight;
    if (inFlight != null) return inFlight.future;
    final completer = Completer<MiniLmIsolateWorker>();
    _workerInFlight = completer;
    try {
      // Pré-requis : warmUp() a déjà extrait le modèle disque + chargé
      // le tokenizer en RAM. On réutilise ces données pour spawner le
      // worker (vocab + config primitifs serialisables cross-isolate).
      if (!_warmedUp) {
        await warmUp();
      }
      final tokenizer = _tokenizer!;
      final modelPath = await _ensureModelOnDisk();
      final worker = await MiniLmIsolateWorker.spawn(
        modelPath: modelPath,
        tokenizerVocab: tokenizer.vocab,
        tokenizerConfig: minilmConfigFromTokenizer(tokenizer),
        maxSequenceLength: _maxSeqLen,
        dim: _dim,
      );
      _worker = worker;
      completer.complete(worker);
      return worker;
    } catch (e, st) {
      completer.completeError(e, st);
      rethrow;
    } finally {
      _workerInFlight = null;
    }
  }

  // ---------------------------------------------------------------------
  // Embedding
  // ---------------------------------------------------------------------

  @override
  Float32List embed(String text) {
    final session = _session;
    final tokenizer = _tokenizer;
    if (session == null || tokenizer == null) {
      throw StateError('MiniLmEmbedder: appeler warmUp() avant embed()');
    }
    if (text.isEmpty) return Float32List(_dim);

    final encoded = tokenizer.encode(text, maxLength: _maxSeqLen);
    final seqLen = encoded.length;

    final inputIds = Int64List.fromList(encoded.inputIds);
    final attentionMask = Int64List.fromList(encoded.attentionMask);
    final tokenTypeIds = Int64List.fromList(encoded.tokenTypeIds);

    final shape = [1, seqLen];
    final inputIdsTensor = OrtValueTensor.createTensorWithDataList(
      inputIds,
      shape,
    );
    final attentionTensor = OrtValueTensor.createTensorWithDataList(
      attentionMask,
      shape,
    );
    final tokenTypeTensor = OrtValueTensor.createTensorWithDataList(
      tokenTypeIds,
      shape,
    );

    final inputs = <String, OrtValue>{
      'input_ids': inputIdsTensor,
      'attention_mask': attentionTensor,
      'token_type_ids': tokenTypeTensor,
    };

    final runOptions = OrtRunOptions();
    final outputs = session.run(runOptions, inputs);
    Float32List? pooled;
    try {
      // last_hidden_state [1, seq_len, 384]
      final raw = outputs.first?.value;
      if (raw is List) {
        pooled = _meanPool(raw, encoded.attentionMask);
      } else {
        throw StateError('Sortie ONNX inattendue : ${raw.runtimeType}');
      }
    } finally {
      for (final o in outputs) {
        o?.release();
      }
      runOptions.release();
      inputIdsTensor.release();
      attentionTensor.release();
      tokenTypeTensor.release();
    }
    return VectorMath.normalizeInPlace(pooled);
  }

  // ---------------------------------------------------------------------
  // Mean pooling pondéré par le masque d'attention.
  // ---------------------------------------------------------------------

  /// `hidden` est livré par `onnxruntime` comme `List<List<List<double>>>`
  /// (batch=1 → seq_len → dim).
  Float32List _meanPool(List<dynamic> hidden, List<int> mask) {
    final batch = hidden.first as List;
    final seqLen = batch.length;
    final out = Float32List(_dim);
    var validCount = 0;
    for (var t = 0; t < seqLen; t++) {
      if (mask[t] == 0) continue;
      final tokenVec = batch[t] as List;
      if (tokenVec.length != _dim) {
        throw StateError(
          'MiniLm: dimension de sortie ${tokenVec.length} ≠ $_dim attendu',
        );
      }
      for (var i = 0; i < _dim; i++) {
        out[i] += (tokenVec[i] as num).toDouble();
      }
      validCount++;
    }
    if (validCount > 0) {
      final inv = 1.0 / validCount;
      for (var i = 0; i < _dim; i++) {
        out[i] *= inv;
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------
  // Extraction de l'asset ONNX vers un fichier local.
  // ---------------------------------------------------------------------

  Future<String> _ensureModelOnDisk() async {
    final dir = await getApplicationSupportDirectory();
    final outDir = Directory(p.join(dir.path, 'models'));
    if (!outDir.existsSync()) outDir.createSync(recursive: true);
    final outFile = File(p.join(outDir.path, p.basename(_modelAsset)));

    final data = await rootBundle.load(_modelAsset);
    final bytes = data.buffer.asUint8List(
      data.offsetInBytes,
      data.lengthInBytes,
    );

    // Évite la réécriture si la taille correspond déjà.
    if (outFile.existsSync() && outFile.lengthSync() == bytes.lengthInBytes) {
      return outFile.path;
    }
    await outFile.writeAsBytes(bytes, flush: true);
    return outFile.path;
  }
}
