import 'dart:async';

import 'package:files_tech_voice/files_tech_voice.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// État unifié du service voix, exposé à l'UI via `Provider`.
///
/// Contrairement à `SttSessionState` qui ne couvre que la phase
/// capture/transcription, [VoiceServiceState] inclut aussi les phases
/// "modèle non installé" et "moteur prêt mais inactif" — l'UI peut donc
/// décider du widget à afficher (bouton import, bouton micro, overlay
/// recording) à partir d'un seul énumeré.
enum VoiceServiceState {
  /// Aucun modèle installé. L'UI doit pousser l'utilisateur vers le
  /// VoiceSetupScreen (notice d'import).
  needsModel,

  /// Modèle installé, moteur Whisper non encore initialisé. Première
  /// transcription chargera le moteur (lazy).
  ready,

  /// Capture micro en cours.
  recording,

  /// Capture stoppée, transcription en cours (1-5 s).
  transcribing,

  /// Erreur — voir `lastError`. L'UI peut proposer une nouvelle tentative.
  error,
}

/// Orchestre l'import du modèle, l'initialisation du moteur Whisper, la
/// capture micro et la transcription.
///
/// Architecture :
/// - Singleton enregistré dans le MultiProvider de `main.dart`.
/// - Étend [ChangeNotifier] : les widgets `Consumer<VoiceService>` se
///   rebuild aux transitions d'état.
/// - Persiste le `id` du modèle actif via SharedPreferences (clé
///   `voice.activeModelId`).
/// - Lazy-init du moteur : pas d'overhead au démarrage si l'utilisateur
///   n'utilise pas le micro pendant la session.
///
/// Pas de logique UI ici — uniquement de l'état et des actions. Les widgets
/// (`VoiceRecordButton`, `VoiceRecordingOverlay`, `VoiceSetupScreen`) ne
/// connaissent que ce service.
class VoiceService extends ChangeNotifier {
  VoiceService({
    required SharedPreferences prefs,
    SpeechToText? stt,
    SttSession? session,
  })  : _prefs = prefs,
        _stt = stt ?? WhisperGgmlStt.instance,
        _session = session ?? SttSession(stt: stt ?? WhisperGgmlStt.instance);

  static const String _kActiveModelIdKey = 'voice.activeModelId';

  final SharedPreferences _prefs;
  final SpeechToText _stt;
  final SttSession _session;

  StreamSubscription<SttSessionState>? _sessionSub;

  VoiceServiceState _state = VoiceServiceState.needsModel;
  String? _lastError;
  SttModel? _activeModel;
  Duration _lastRecordedFor = Duration.zero;

  // Public API ----------------------------------------------------------

  VoiceServiceState get state => _state;

  /// Modèle actuellement actif (chargé ou prêt à être chargé). `null` si
  /// aucun n'a encore été importé.
  SttModel? get activeModel => _activeModel;

  /// Dernière erreur affichable. `null` quand pas d'erreur ou après une
  /// nouvelle tentative réussie.
  String? get lastError => _lastError;

  /// Durée du dernier enregistrement transcrit. Utile pour télémétrie
  /// locale uniquement (jamais envoyée nulle part).
  Duration get lastRecordedFor => _lastRecordedFor;

  /// `true` si le moteur Whisper est complètement chargé et prêt à
  /// transcrire. `false` si seul le fichier .bin est en place.
  bool get isEngineLoaded => _stt.isInitialized;

  /// Bootstrap au démarrage de l'app : retrouve le modèle actif persisté,
  /// vérifie son intégrité (SHA-256 still valide), met à jour `state`.
  /// Appelé depuis `main.dart` avant `runApp`.
  Future<void> bootstrap() async {
    // Premier filet : nettoie d'éventuels WAV temp orphelins (crash en
    // pleine transcription côté précédente exécution).
    await SttModelDownloader.instance.purgeTempCaptures();

    final id = _prefs.getString(_kActiveModelIdKey);
    if (id == null) {
      _setState(VoiceServiceState.needsModel);
      return;
    }
    final model = SttModelCatalog.byId(id);
    if (model == null) {
      // Catalogue a évolué — on oublie ce modèle.
      await _prefs.remove(_kActiveModelIdKey);
      _setState(VoiceServiceState.needsModel);
      return;
    }
    final installed = await SttModelDownloader.instance.isInstalled(model);
    if (!installed) {
      _setState(VoiceServiceState.needsModel);
      return;
    }
    _activeModel = model;
    _setState(VoiceServiceState.ready);
    _attachSessionStream();
  }

  /// Importe un fichier modèle local (sélectionné via SAF) pour [model].
  /// Vérifie SHA-256, copie atomiquement dans la zone privée de l'app,
  /// l'enregistre comme modèle actif, et passe à [VoiceServiceState.ready].
  ///
  /// Lève [SttModelChecksumMismatch] / [SttDownloadFailed] si le fichier
  /// est invalide — les laisser remonter à l'UI pour affichage.
  Future<void> importModel({
    required String sourcePath,
    required SttModel model,
    void Function(SttImportProgress progress)? onProgress,
  }) async {
    await SttModelImporter.instance.importFromPath(
      sourcePath,
      model: model,
      onProgress: onProgress,
    );
    await _prefs.setString(_kActiveModelIdKey, model.id);
    _activeModel = model;
    _lastError = null;
    _setState(VoiceServiceState.ready);
    _attachSessionStream();
  }

  /// Démarre une capture micro. [VoiceServiceState.ready] requis. Lazy-init
  /// du moteur Whisper si pas encore chargé.
  ///
  /// Lève [SttPermissionDenied] si refusée, [SttModelMissing] si le fichier
  /// a été supprimé hors de l'app entre temps.
  Future<void> startRecording() async {
    final model = _activeModel;
    if (model == null) {
      _fail('Aucun modèle de transcription installé.');
      return;
    }
    if (_state == VoiceServiceState.recording ||
        _state == VoiceServiceState.transcribing) {
      return; // idempotent
    }
    try {
      if (!_stt.isInitialized) {
        await _stt.initialize(model);
      }
      await _session.start();
      // L'état effectif sera émis par le stream session → VoiceService.
    } on SttException catch (e) {
      _fail(e.message);
      rethrow;
    } catch (e) {
      _fail('Erreur démarrage capture : $e');
      rethrow;
    }
  }

  /// Stoppe la capture, transcrit, retourne le texte. Le WAV est supprimé
  /// dans tous les cas.
  Future<SttTranscription> stopAndTranscribe({String? language}) async {
    final start = _session.elapsed;
    try {
      final result = await _session.stopAndTranscribe(language: language);
      _lastRecordedFor = start;
      _lastError = null;
      return result;
    } on SttException catch (e) {
      _fail(e.message);
      rethrow;
    } catch (e) {
      _fail('Erreur transcription : $e');
      rethrow;
    }
  }

  /// Annule la capture en cours. Pas de transcription, WAV supprimé.
  Future<void> cancelRecording() async {
    await _session.cancel();
  }

  /// Désinstalle le modèle actif (mode "changer de modèle" dans Settings).
  /// Repasse en [VoiceServiceState.needsModel].
  Future<void> uninstallActiveModel() async {
    final model = _activeModel;
    if (model == null) return;
    await _stt.dispose();
    await SttModelDownloader.instance.uninstall(model);
    await _prefs.remove(_kActiveModelIdKey);
    _activeModel = null;
    _setState(VoiceServiceState.needsModel);
  }

  /// Mode panique : supprime tous les modèles installés ET les WAV temp.
  /// L'app peut continuer à tourner sans voix (fallback gracieux).
  Future<void> wipeAll() async {
    await _stt.dispose();
    await SttModelDownloader.instance.uninstallAll();
    await SttModelDownloader.instance.purgeTempCaptures();
    await _prefs.remove(_kActiveModelIdKey);
    _activeModel = null;
    _setState(VoiceServiceState.needsModel);
  }

  @override
  Future<void> dispose() async {
    await _sessionSub?.cancel();
    await _session.dispose();
    await _stt.dispose();
    super.dispose();
  }

  // Internal ------------------------------------------------------------

  void _attachSessionStream() {
    _sessionSub?.cancel();
    _sessionSub = _session.stateStream.listen(_onSessionState);
  }

  void _onSessionState(SttSessionState s) {
    switch (s) {
      case SttSessionState.recording:
        _setState(VoiceServiceState.recording);
      case SttSessionState.transcribing:
        _setState(VoiceServiceState.transcribing);
      case SttSessionState.idle:
        if (_state != VoiceServiceState.error) {
          _setState(VoiceServiceState.ready);
        }
      case SttSessionState.requestingPermission:
        // Transitoire, on ne rebuild rien. L'UI affiche déjà le bouton
        // micro pressé.
        break;
      case SttSessionState.error:
        // L'erreur a déjà été stockée par le throw, on s'aligne.
        _lastError ??= 'Erreur capture micro.';
        _setState(VoiceServiceState.error);
    }
  }

  void _fail(String message) {
    _lastError = message;
    _setState(VoiceServiceState.error);
  }

  void _setState(VoiceServiceState s) {
    if (_state == s) return;
    _state = s;
    notifyListeners();
  }
}
