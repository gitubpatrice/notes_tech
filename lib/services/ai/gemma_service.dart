/// Service de Q&A on-device basé sur Gemma 3 1B int4 via MediaPipe.
///
/// Cycle de vie :
///   1. `isModelInstalled()` : true si le `.task` existe dans le sandbox app.
///   2. `importFromFile(source)` : copie un .task choisi par l'utilisateur (SAF)
///      vers `<appSupport>/models/gemma3-1b-it-int4.task` (rename atomique).
///   3. `warmUp()` : installe + active le modèle dans flutter_gemma.
///      Tente le backend GPU (OpenCL) puis retombe sur CPU si indisponible.
///   4. `ask(prompt)` : streaming token par token. Idempotent et sériel
///      (un seul `ask` à la fois ; les appels concurrents échouent fast).
///   5. `dispose()` : ferme la session MediaPipe.
///
/// Sécurité :
/// - Aucune permission INTERNET requise (Gemma tourne 100% local).
/// - Validation taille (>= 100 Mo, <= 2 Go) avant copie.
/// - Path passé à flutter_gemma toujours composé depuis constantes ;
///   jamais d'input utilisateur direct.
/// - `_maxPromptChars` borne le prompt final pour éviter un OOM côté JNI.
/// - `dispose()` log les erreurs en debug, silencieux en release.
library;

import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter_gemma/flutter_gemma.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

class GemmaService {
  GemmaService();

  /// Nom canonique du fichier modèle dans le sandbox.
  static const String _modelFileName = 'gemma3-1b-it-int4.task';

  /// Tailles raisonnables pour Gemma 3 1B int4 (limite haute large
  /// pour absorber d'éventuelles variantes).
  static const int _minModelSizeBytes = 100 * 1024 * 1024; // 100 Mo
  static const int _maxModelSizeBytes = 2 * 1024 * 1024 * 1024; // 2 Go

  /// Fenêtre Gemma 3 1B = jusqu'à 32K tokens, mais coût mémoire monte vite.
  /// 4096 laisse ~3000 tokens pour le contexte RAG (top-K=4, cap 1000) +
  /// ~1000 tokens pour la réponse, sans saturer la RAM sur S24.
  static const int _maxTokens = 4096;

  /// Borne stricte sur le prompt envoyé à MediaPipe.
  /// 16 000 caractères ≈ 4 000 tokens en français.
  static const int _maxPromptChars = 16000;

  /// Granularité du yield de progression durant l'import (4 Mo).
  /// Plus grossier = moins de rebuilds UI.
  static const int _importYieldEvery = 4 * 1024 * 1024;

  InferenceModel? _model;
  InferenceChat? _chat;
  bool _initializing = false;
  bool _busy = false;
  Completer<void>? _warmUpInFlight;

  // ---------------------------------------------------------------------
  // Détection / installation
  // ---------------------------------------------------------------------

  Future<File> _modelFile() async {
    final dir = await getApplicationSupportDirectory();
    final modelsDir = Directory(p.join(dir.path, 'models'));
    if (!modelsDir.existsSync()) {
      modelsDir.createSync(recursive: true);
    }
    return File(p.join(modelsDir.path, _modelFileName));
  }

  /// True si un modèle valide est présent dans le sandbox app.
  /// Effectue un cleanup des `.tmp` orphelins (ex. crash en pleine copie).
  Future<bool> isModelInstalled() async {
    final f = await _modelFile();
    await _cleanupTempFiles(f.parent);
    if (!f.existsSync()) return false;
    final size = f.lengthSync();
    return size >= _minModelSizeBytes && size <= _maxModelSizeBytes;
  }

  Future<int> installedSizeBytes() async {
    final f = await _modelFile();
    return f.existsSync() ? f.lengthSync() : 0;
  }

  Future<void> _cleanupTempFiles(Directory modelsDir) async {
    if (!modelsDir.existsSync()) return;
    try {
      for (final entity in modelsDir.listSync()) {
        if (entity is File && entity.path.endsWith('.tmp')) {
          try {
            entity.deleteSync();
          } catch (_) {}
        }
      }
    } catch (_) {}
  }

  /// Copie un .task choisi par l'utilisateur vers le sandbox app.
  /// Stream `(copied, total)` à intervalles de `_importYieldEvery` octets
  /// pour ne pas saturer la boucle UI sur de gros fichiers.
  Stream<({int copied, int total})> importFromFile(File source) async* {
    if (!source.existsSync()) {
      throw const _GemmaException('Fichier source introuvable');
    }
    final size = source.lengthSync();
    if (size < _minModelSizeBytes) {
      throw _GemmaException(
        'Fichier trop petit (${size ~/ (1024 * 1024)} Mo) — '
        'pas un modèle Gemma valide.',
      );
    }
    if (size > _maxModelSizeBytes) {
      throw _GemmaException(
        'Fichier trop gros (${size ~/ (1024 * 1024)} Mo) — '
        'limite ${_maxModelSizeBytes ~/ (1024 * 1024)} Mo.',
      );
    }

    final dest = await _modelFile();
    final tmp = File('${dest.path}.tmp');
    if (tmp.existsSync()) tmp.deleteSync();

    final input = source.openRead();
    final output = tmp.openWrite();
    var copied = 0;
    var lastYielded = 0;
    try {
      await for (final chunk in input) {
        output.add(chunk);
        copied += chunk.length;
        if (copied - lastYielded >= _importYieldEvery || copied == size) {
          await output.flush();
          lastYielded = copied;
          yield (copied: copied, total: size);
        }
      }
      await output.flush();
      await output.close();
    } catch (_) {
      await output.close();
      if (tmp.existsSync()) tmp.deleteSync();
      rethrow;
    }

    if (dest.existsSync()) dest.deleteSync();
    await tmp.rename(dest.path);
  }

  /// Supprime le modèle installé (libère l'espace disque).
  Future<void> uninstall() async {
    await dispose();
    final f = await _modelFile();
    if (f.existsSync()) f.deleteSync();
  }

  // ---------------------------------------------------------------------
  // WarmUp / chat
  // ---------------------------------------------------------------------

  bool get isReady => _model != null && _chat != null;
  bool get isInitializing => _initializing;
  bool get isBusy => _busy;

  Future<void> warmUp() async {
    if (isReady) return;
    final inFlight = _warmUpInFlight;
    if (inFlight != null) return inFlight.future;
    final completer = Completer<void>();
    _warmUpInFlight = completer;
    _initializing = true;
    try {
      final f = await _modelFile();
      if (!f.existsSync()) {
        throw const _GemmaException('Modèle Gemma non installé');
      }

      await FlutterGemma
          .installModel(modelType: ModelType.gemmaIt)
          .fromFile(f.path)
          .install();

      final model = await _createModelWithFallback();
      final chat = await _newChat(model);

      _model = model;
      _chat = chat;
      completer.complete();
    } catch (e, st) {
      completer.completeError(e, st);
      rethrow;
    } finally {
      _initializing = false;
      _warmUpInFlight = null;
    }
  }

  /// Tente d'instancier le modèle sur GPU (OpenCL) ; en cas d'échec,
  /// retombe silencieusement sur CPU. Le warmUp réussit tant qu'au
  /// moins un backend marche.
  Future<InferenceModel> _createModelWithFallback() async {
    Object? gpuError;
    try {
      return await FlutterGemmaPlugin.instance.createModel(
        modelType: ModelType.gemmaIt,
        maxTokens: _maxTokens,
        preferredBackend: PreferredBackend.gpu,
      );
    } catch (e) {
      gpuError = e;
      if (kDebugMode) debugPrint('Gemma GPU indisponible : $e');
    }
    try {
      return await FlutterGemmaPlugin.instance.createModel(
        modelType: ModelType.gemmaIt,
        maxTokens: _maxTokens,
        preferredBackend: PreferredBackend.cpu,
      );
    } catch (e) {
      throw _GemmaException(
        'Échec d\'initialisation du modèle (GPU=$gpuError, CPU=$e)',
      );
    }
  }

  Future<InferenceChat> _newChat(InferenceModel model) {
    return model.createChat(
      temperature: 0.4,
      topK: 40,
      topP: 0.95,
      randomSeed: math.Random().nextInt(0x7fffffff),
    );
  }

  /// Pose une question. Yield les morceaux de texte au fil de la génération.
  /// Concurrent-safe : un seul `ask` à la fois.
  /// Le contexte est réinitialisé à chaque appel (Q&A, pas de chat suivi).
  Stream<String> ask(String prompt) async* {
    if (_chat == null) {
      throw const _GemmaException('Modèle non chargé. Appeler warmUp() avant.');
    }
    if (_busy) {
      throw const _GemmaException(
        'Une génération est déjà en cours.',
      );
    }
    var cleaned = prompt.trim();
    if (cleaned.isEmpty) return;
    if (cleaned.length > _maxPromptChars) {
      cleaned = cleaned.substring(0, _maxPromptChars);
    }

    _busy = true;
    try {
      // Repart d'un chat vierge → contexte RAG injecté à neuf, pas de fuite.
      await _resetChat();
      await _chat!.addQueryChunk(Message.text(text: cleaned, isUser: true));

      yield* _chat!.generateChatResponseAsync().map((r) {
        if (r is TextResponse) return r.token;
        return '';
      }).where((t) => t.isNotEmpty);
    } finally {
      _busy = false;
    }
  }

  /// Force l'arrêt d'une génération en cours en réinitialisant le chat.
  /// `flutter_gemma` 0.14 n'a pas d'API d'annulation explicite — recréer
  /// la session est le moyen propre d'arrêter l'inférence native.
  Future<void> stopGeneration() async {
    if (!_busy) return;
    await _resetChat();
    _busy = false;
  }

  Future<void> _resetChat() async {
    final model = _model;
    if (model == null) return;
    try {
      await _chat?.close();
    } catch (e) {
      if (kDebugMode) debugPrint('Gemma _resetChat close: $e');
    }
    _chat = await _newChat(model);
  }

  Future<void> dispose() async {
    try {
      await _chat?.close();
    } catch (e) {
      if (kDebugMode) debugPrint('Gemma dispose chat: $e');
    }
    try {
      await _model?.close();
    } catch (e) {
      if (kDebugMode) debugPrint('Gemma dispose model: $e');
    }
    _chat = null;
    _model = null;
    _busy = false;
  }
}

class _GemmaException implements Exception {
  const _GemmaException(this.message);
  final String message;
  @override
  String toString() => 'GemmaException: $message';
}
