/// Worker isolate persistant pour `MiniLmEmbedder`.
///
/// **Pourquoi** : `OrtSession.run` est synchrone et bloque le main thread
/// 30-200 ms par requête (S24 FE) à 200-600 ms (S9, POCO C75). Sur la
/// recherche sémantique interactive, ça produit un jank visible au tap.
///
/// **Architecture** : un Isolate persistant détient `OrtSession` +
/// `BertTokenizer` (chargés une fois au spawn) et répond aux requêtes
/// d'embedding via `SendPort`. La main thread reste fluide pendant que
/// le worker calcule.
///
/// **Limitations connues** :
/// - `OrtSession` n'est pas portable cross-isolate → on l'instancie côté
///   worker uniquement, à partir d'un PATH fichier (déjà extrait disque
///   par `MiniLmEmbedder._ensureModelOnDisk` côté main).
/// - `BertTokenizer` est passé sous forme de `Map<String,dynamic>` JSON
///   (primitif, serialisable cross-isolate) puis re-parsé côté worker.
/// - `RootIsolateToken` non requis : on évite tout `rootBundle` côté
///   worker en passant directement les données déjà décodées.
library;

import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:onnxruntime/onnxruntime.dart';

import '../../utils/vector_math.dart';
import 'bert_tokenizer.dart';

/// API publique : un worker prêt à embed après `spawn()`.
class MiniLmIsolateWorker {
  MiniLmIsolateWorker._(this._isolate, this._sendPort, this._fromWorker);

  final Isolate _isolate;
  final SendPort _sendPort;
  final ReceivePort _fromWorker;
  // Multiplexe les réponses par id de requête (anti race entre 2 embed
  // concurrents — on autorise pour l'avenir, sinon serial).
  int _nextRequestId = 0;
  final Map<int, Completer<Float32List>> _pending = {};
  StreamSubscription<dynamic>? _replySub;
  bool _disposed = false;

  /// Spawne un nouvel isolate avec session ONNX + tokenizer pré-chargés.
  ///
  /// [modelPath] : chemin disque vers le `.onnx` (déjà extrait par le
  /// caller). [tokenizerVocab] et [tokenizerConfig] : données extraites
  /// du `tokenizer.json` (passables cross-isolate car primitives).
  static Future<MiniLmIsolateWorker> spawn({
    required String modelPath,
    required Map<String, int> tokenizerVocab,
    required MiniLmTokenizerConfig tokenizerConfig,
    required int maxSequenceLength,
    required int dim,
  }) async {
    final fromWorker = ReceivePort();
    final ready = Completer<SendPort>();
    final args = _SpawnArgs(
      modelPath: modelPath,
      vocab: tokenizerVocab,
      config: tokenizerConfig,
      maxSeqLen: maxSequenceLength,
      dim: dim,
      mainSendPort: fromWorker.sendPort,
    );
    final isolate = await Isolate.spawn<_SpawnArgs>(_workerEntry, args,
        debugName: 'MiniLmWorker');
    // Le worker poste son SendPort en premier message, puis tout le reste
    // est multiplexé par requestId.
    late final StreamSubscription<dynamic> sub;
    sub = fromWorker.listen((msg) {
      if (msg is SendPort && !ready.isCompleted) {
        ready.complete(msg);
      }
    });
    final SendPort workerSend;
    try {
      workerSend = await ready.future
          .timeout(const Duration(seconds: 30));
    } catch (e) {
      await sub.cancel();
      isolate.kill(priority: Isolate.immediate);
      fromWorker.close();
      rethrow;
    }
    final worker = MiniLmIsolateWorker._(isolate, workerSend, fromWorker);
    // Bascule l'écoute des messages sur le router de réponses.
    await sub.cancel();
    worker._replySub = fromWorker.listen(worker._onWorkerMessage);
    return worker;
  }

  void _onWorkerMessage(dynamic msg) {
    if (msg is! _EmbedResponse) return;
    final completer = _pending.remove(msg.requestId);
    if (completer == null || completer.isCompleted) return;
    if (msg.error != null) {
      completer.completeError(StateError(msg.error!));
    } else if (msg.vector != null) {
      completer.complete(msg.vector!);
    } else {
      completer.completeError(StateError('MiniLmWorker: empty response'));
    }
  }

  /// Encode un texte en vecteur 384D. Lève si le worker est disposé.
  Future<Float32List> embed(String text) {
    if (_disposed) {
      return Future.error(StateError('MiniLmIsolateWorker disposed'));
    }
    final id = _nextRequestId++;
    final completer = Completer<Float32List>();
    _pending[id] = completer;
    _sendPort.send(_EmbedRequest(requestId: id, text: text));
    return completer.future;
  }

  /// Tue l'isolate et libère les ressources. Idempotent.
  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    // Drain les pending (le caller verra une erreur).
    for (final c in _pending.values) {
      if (!c.isCompleted) {
        c.completeError(StateError('MiniLmIsolateWorker disposed mid-flight'));
      }
    }
    _pending.clear();
    await _replySub?.cancel();
    _replySub = null;
    _isolate.kill(priority: Isolate.immediate);
    _fromWorker.close();
  }
}

/// Config tokenizer sérialisable (passable cross-isolate). Construit
/// par `MiniLmEmbedder` depuis le JSON parsé.
class MiniLmTokenizerConfig {
  const MiniLmTokenizerConfig({
    required this.unkToken,
    required this.clsToken,
    required this.sepToken,
    required this.padToken,
    required this.continuingPrefix,
    required this.maxInputCharsPerWord,
  });
  final int unkToken;
  final int clsToken;
  final int sepToken;
  final int padToken;
  final String continuingPrefix;
  final int maxInputCharsPerWord;
}

/// Helper public : extrait `MiniLmTokenizerConfig` depuis un BertTokenizer
/// déjà chargé côté main.
MiniLmTokenizerConfig minilmConfigFromTokenizer(BertTokenizer t) =>
    MiniLmTokenizerConfig(
      unkToken: t.unkToken,
      clsToken: t.clsToken,
      sepToken: t.sepToken,
      padToken: t.padToken,
      continuingPrefix: t.continuingPrefix,
      maxInputCharsPerWord: t.maxInputCharsPerWord,
    );

class _SpawnArgs {
  const _SpawnArgs({
    required this.modelPath,
    required this.vocab,
    required this.config,
    required this.maxSeqLen,
    required this.dim,
    required this.mainSendPort,
  });
  final String modelPath;
  final Map<String, int> vocab;
  final MiniLmTokenizerConfig config;
  final int maxSeqLen;
  final int dim;
  final SendPort mainSendPort;
}

class _EmbedRequest {
  const _EmbedRequest({required this.requestId, required this.text});
  final int requestId;
  final String text;
}

class _EmbedResponse {
  const _EmbedResponse({
    required this.requestId,
    this.vector,
    this.error,
  });
  final int requestId;
  final Float32List? vector;
  final String? error;
}

/// Point d'entrée de l'isolate worker. Init session+tokenizer une fois
/// puis boucle `await for` sur les requêtes.
Future<void> _workerEntry(_SpawnArgs args) async {
  // Init côté worker — coût payé une seule fois au démarrage.
  OrtEnv.instance.init();
  final session = OrtSession.fromFile(
    File(args.modelPath),
    OrtSessionOptions(),
  );
  // Reconstruit BertTokenizer depuis vocab + config (re-utilise le ctor
  // privé via factory exposée — voir bert_tokenizer.dart).
  final tokenizer = bertTokenizerFromConfig(
    vocab: args.vocab,
    unkToken: args.config.unkToken,
    clsToken: args.config.clsToken,
    sepToken: args.config.sepToken,
    padToken: args.config.padToken,
    continuingPrefix: args.config.continuingPrefix,
    maxInputCharsPerWord: args.config.maxInputCharsPerWord,
  );

  final fromMain = ReceivePort();
  args.mainSendPort.send(fromMain.sendPort);

  await for (final msg in fromMain) {
    if (msg is! _EmbedRequest) continue;
    try {
      final vec = _embedSync(
        session: session,
        tokenizer: tokenizer,
        text: msg.text,
        maxSeqLen: args.maxSeqLen,
        dim: args.dim,
      );
      args.mainSendPort.send(
        _EmbedResponse(requestId: msg.requestId, vector: vec),
      );
    } catch (e) {
      args.mainSendPort.send(
        _EmbedResponse(requestId: msg.requestId, error: e.toString()),
      );
    }
  }
}

/// Encode un texte en vecteur. Sync, exécuté dans le worker isolate.
/// Reproduit la logique de `MiniLmEmbedder.embed` mais sans dépendre
/// d'une instance.
Float32List _embedSync({
  required OrtSession session,
  required BertTokenizer tokenizer,
  required String text,
  required int maxSeqLen,
  required int dim,
}) {
  if (text.isEmpty) return Float32List(dim);
  final encoded = tokenizer.encode(text, maxLength: maxSeqLen);
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
    final raw = outputs.first?.value;
    if (raw is List) {
      final batch = raw.first as List;
      final s = batch.length;
      final out = Float32List(dim);
      var validCount = 0;
      for (var t = 0; t < s; t++) {
        if (encoded.attentionMask[t] == 0) continue;
        final tokenVec = batch[t] as List;
        for (var i = 0; i < dim; i++) {
          out[i] += (tokenVec[i] as num).toDouble();
        }
        validCount++;
      }
      if (validCount > 0) {
        final inv = 1.0 / validCount;
        for (var i = 0; i < dim; i++) {
          out[i] *= inv;
        }
      }
      pooled = out;
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
