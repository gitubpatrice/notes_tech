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

import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/services.dart' show rootBundle;
import 'package:onnxruntime/onnxruntime.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../utils/vector_math.dart';
import 'bert_tokenizer.dart';
import 'embedding_provider.dart';

class MiniLmEmbedder implements EmbeddingProvider {
  MiniLmEmbedder({
    String modelAsset = 'assets/models/all-MiniLM-L6-v2-quant.onnx',
    String tokenizerAsset = 'assets/models/tokenizer.json',
    int maxSequenceLength = 128,
  })  : _modelAsset = modelAsset,
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

  @override
  String get modelId => _modelId;

  @override
  int get dim => _dim;

  // ---------------------------------------------------------------------
  // Init / dispose
  // ---------------------------------------------------------------------

  /// Vérifie la présence des assets sans tout charger.
  /// Utilisé au démarrage pour décider Local vs MiniLM.
  static Future<bool> assetsAvailable({
    String modelAsset = 'assets/models/all-MiniLM-L6-v2-quant.onnx',
    String tokenizerAsset = 'assets/models/tokenizer.json',
  }) async {
    try {
      // `loadStructured` n'existe pas → on tente un load léger.
      // `load` charge tout en mémoire ; on évite ça pour le modèle.
      // Astuce : `rootBundle.load` met en cache une fois résolu, et la
      // détection d'absence est instantanée. Pour le modèle, on ne charge
      // que les premiers octets via `loadStructuredBinaryData` ? Indispo.
      // Compromis : on tente un load complet, pratique car appelé qu'au démarrage.
      await rootBundle.load(modelAsset);
      await rootBundle.loadString(tokenizerAsset);
      return true;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<void> warmUp() async {
    if (_warmedUp) return;
    OrtEnv.instance.init();
    final tokenizer = await BertTokenizer.loadFromAsset(_tokenizerAsset);
    final modelPath = await _ensureModelOnDisk();

    final sessionOptions = OrtSessionOptions();
    final session = OrtSession.fromFile(File(modelPath), sessionOptions);

    _tokenizer = tokenizer;
    _session = session;
    _warmedUp = true;
  }

  @override
  Future<void> dispose() async {
    _session?.release();
    _session = null;
    _tokenizer = null;
    _warmedUp = false;
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
    final inputIdsTensor =
        OrtValueTensor.createTensorWithDataList(inputIds, shape);
    final attentionTensor =
        OrtValueTensor.createTensorWithDataList(attentionMask, shape);
    final tokenTypeTensor =
        OrtValueTensor.createTensorWithDataList(tokenTypeIds, shape);

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
