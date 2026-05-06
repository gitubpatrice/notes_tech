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

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_gemma/flutter_gemma.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../core/constants.dart';

class GemmaService {
  GemmaService({String expectedSha256 = AppConstants.gemmaModelSha256})
      : _expectedSha256 = expectedSha256.toLowerCase();

  final String _expectedSha256;

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

  /// Gate de sérialisation pour `ask` / `stopGeneration`.
  /// Garantit qu'une opération sur le chat (close, recréation, génération)
  /// ne chevauche pas une autre, même en cas d'utilisateur très rapide.
  Future<void> _gate = Future<void>.value();

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
  ///
  /// Sécurité v0.5 :
  ///  - SHA-256 calculé en streaming (pas de re-lecture après copie).
  ///  - Le fichier est écrit en `.tmp` puis vérifié contre
  ///    `_expectedSha256` AVANT le rename atomique vers la destination
  ///    finale. Mismatch ⇒ suppression du `.tmp` et exception détaillée.
  ///  - L'override `acceptUnknownHash` permet à l'utilisateur averti
  ///    d'accepter une variante non listée (toggle Réglages).
  Stream<({int copied, int total})> importFromFile(
    File source, {
    bool acceptUnknownHash = false,
  }) async* {
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

    final digestSink = _DigestSink();
    final hashSink = sha256.startChunkedConversion(digestSink);
    final input = source.openRead();
    final output = tmp.openWrite();
    var copied = 0;
    var lastYielded = 0;
    try {
      await for (final chunk in input) {
        output.add(chunk);
        hashSink.add(chunk);
        copied += chunk.length;
        if (copied - lastYielded >= _importYieldEvery || copied == size) {
          await output.flush();
          lastYielded = copied;
          yield (copied: copied, total: size);
        }
      }
      await output.flush();
      await output.close();
      hashSink.close();
    } catch (_) {
      await output.close();
      if (tmp.existsSync()) tmp.deleteSync();
      rethrow;
    }

    final actualHex = digestSink.value.toString().toLowerCase();
    if (!_constantTimeEquals(actualHex, _expectedSha256) &&
        !acceptUnknownHash) {
      if (tmp.existsSync()) tmp.deleteSync();
      throw GemmaHashMismatchException(
        expected: _expectedSha256,
        actual: actualHex,
      );
    }

    if (dest.existsSync()) dest.deleteSync();
    await tmp.rename(dest.path);
  }

  /// Supprime le modèle installé (libère l'espace disque).
  ///
  /// Utilise `delete()` async (et non `deleteSync`) : sur 530 Mo, le
  /// `unlink()` bloque l'isolate Dart 50-300 ms sur eMMC moyen,
  /// ce qui freezerait visiblement le `CircularProgressIndicator` du
  /// mode panique. La forme async libère l'event loop.
  Future<void> uninstall() async {
    await dispose();
    final f = await _modelFile();
    if (await f.exists()) {
      await f.delete();
    }
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

  /// Sérialise une opération sur le chat via `_gate`.
  /// Toute opération ask/stop passe par cette file pour éviter
  /// les races sur `_chat?.close()` et `model.createChat()`.
  Future<T> _serialize<T>(Future<T> Function() op) {
    final completer = Completer<T>();
    _gate = _gate.then((_) async {
      try {
        completer.complete(await op());
      } catch (e, st) {
        completer.completeError(e, st);
      }
    });
    return completer.future;
  }

  /// Pose une question. Yield les morceaux de texte au fil de la génération.
  /// Concurrent-safe via le gate.
  /// Le contexte est réinitialisé à chaque appel (Q&A, pas de chat suivi).
  Stream<String> ask(String prompt) async* {
    if (_chat == null) {
      throw const _GemmaException('Modèle non chargé. Appeler warmUp() avant.');
    }
    if (_busy) {
      throw const _GemmaException('Une génération est déjà en cours.');
    }
    var cleaned = prompt.trim();
    if (cleaned.isEmpty) return;
    if (cleaned.length > _maxPromptChars) {
      cleaned = _safeSubstring(cleaned, _maxPromptChars);
    }

    _busy = true;
    try {
      // Phase 1 (sérialisée) : reset chat + push prompt.
      await _serialize<void>(() async {
        await _resetChat();
        await _chat!
            .addQueryChunk(Message.text(text: cleaned, isUser: true));
      });

      // Phase 2 : streaming. Le stream natif n'a pas de close-on-cancel,
      // mais `stopGeneration` ferme la session ce qui le coupe.
      yield* _chat!.generateChatResponseAsync().map((r) {
        if (r is TextResponse) return r.token;
        return '';
      }).where((t) => t.isNotEmpty);
    } finally {
      _busy = false;
    }
  }

  /// Force l'arrêt d'une génération en cours via reset chat sérialisé.
  Future<void> stopGeneration() async {
    if (!_busy && _chat != null) return;
    _busy = false;
    await _serialize<void>(_resetChat);
  }

  /// Compare deux chaînes hex en temps constant : XOR octet par octet
  /// jusqu'à la fin systématique, sans court-circuit. Garde-fou contre
  /// une attaque par timing si l'attaquant pouvait observer la latence
  /// d'import (peu probable en local, mais coût nul).
  static bool _constantTimeEquals(String a, String b) {
    if (a.length != b.length) return false;
    var diff = 0;
    for (var i = 0; i < a.length; i++) {
      diff |= a.codeUnitAt(i) ^ b.codeUnitAt(i);
    }
    return diff == 0;
  }

  /// Substring qui ne coupe pas une surrogate pair UTF-16.
  static String _safeSubstring(String s, int max) {
    if (s.length <= max) return s;
    var end = max;
    if (end > 0 && (s.codeUnitAt(end - 1) & 0xFC00) == 0xD800) {
      end -= 1; // évite de couper le high surrogate orphelin
    }
    return s.substring(0, end);
  }

  Future<void> _resetChat() async {
    final model = _model;
    if (model == null) return;
    final old = _chat;
    _chat = null; // ferme la fenêtre où ask() pourrait toucher l'ancienne session
    try {
      await old?.close();
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

/// Sink one-shot pour récupérer le `Digest` final d'un
/// `sha256.startChunkedConversion`.
class _DigestSink implements Sink<Digest> {
  Digest? _value;
  @override
  void add(Digest data) => _value = data;
  @override
  void close() {}
  Digest get value {
    final v = _value;
    if (v == null) {
      throw StateError('Hash non calculé : sink fermé sans données.');
    }
    return v;
  }
}

/// L'utilisateur a importé un fichier dont le SHA-256 ne correspond
/// pas au modèle officiel attendu. Soit le fichier est corrompu,
/// soit il s'agit d'une variante non listée. L'utilisateur peut
/// activer le toggle `acceptUnknownHash` dans les réglages avancés
/// pour passer outre en connaissance de cause.
class GemmaHashMismatchException implements Exception {
  const GemmaHashMismatchException({
    required this.expected,
    required this.actual,
  });
  final String expected;
  final String actual;
  @override
  String toString() =>
      'Empreinte SHA-256 inattendue.\n'
      'Attendu : $expected\n'
      'Calculé : $actual\n'
      'Si tu fais confiance au fichier, active "Accepter un modèle '
      'non vérifié" dans Réglages → Avancé.';
}
