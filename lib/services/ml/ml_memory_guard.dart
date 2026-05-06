/// Coordinateur RAM pour les moteurs ML on-device.
///
/// Notes Tech embarque deux modèles ML lourds :
/// - **Gemma 3 1B int4** (~530 Mo en RAM avec contexte) — Q&A
/// - **Whisper base q5_1** (~150-300 Mo en RAM avec session active) — voix
///
/// Sur un téléphone à 4 Go (POCO C75, S9), charger les deux simultanément
/// peut déclencher un OOM kill par Android. Ce coordinateur garantit qu'**un
/// seul moteur** détient le verrou à la fois — l'autre est évincé (libère
/// son contexte natif) avant le chargement.
///
/// Pourquoi pas un singleton avec compteurs ? Parce que les services Gemma
/// et Voice sont indépendants et ne doivent pas se connaître mutuellement
/// (couplage). Ce guard est l'unique point de couplage, injecté à chacun
/// via DI dans `main.dart`.
///
/// Architecture :
/// - Pas de notion de priorité : c'est l'**ordre des appels** qui décide.
///   Si l'utilisateur dicte (acquire `voice`) puis demande Q&A (acquire
///   `gemma`), Whisper est évincé.
/// - Pas de retry / queue : chaque acquisition est synchrone côté caller —
///   l'éviction du voisin est awaitée avant de continuer.
/// - Pas d'état persistant : le guard n'a pas de mémoire entre lancements ;
///   les services repartent de zéro à chaque cold start (lazy load).
class MlMemoryGuard {
  /// [evictGemma] : ferme le contexte Gemma (modèle + chat MediaPipe) et
  /// libère sa RAM native. Doit être idempotent (appel multiples sans état
  /// chargé = no-op).
  ///
  /// [evictVoice] : ferme le moteur Whisper (libère le `.bin` mappé en
  /// mémoire native). Idempotent.
  MlMemoryGuard({
    required Future<void> Function() evictGemma,
    required Future<void> Function() evictVoice,
  })  : _evictGemma = evictGemma,
        _evictVoice = evictVoice;

  final Future<void> Function() _evictGemma;
  final Future<void> Function() _evictVoice;

  _Holder _holder = _Holder.none;

  /// Le service voix appelle ceci AVANT `WhisperGgmlStt.initialize()`.
  /// Si Gemma détient le verrou, on l'évince (libération RAM) puis on
  /// transfère le verrou à voix.
  Future<void> requestVoice() async {
    if (_holder == _Holder.voice) return;
    if (_holder == _Holder.gemma) {
      await _evictGemma();
    }
    _holder = _Holder.voice;
  }

  /// Le service Gemma appelle ceci AVANT `gemma.warmUp()`. Si voix détient
  /// le verrou, on l'évince. Si voix est en plein recording, on évincera
  /// quand même le moteur Whisper — la session de capture continue (le
  /// moteur n'est nécessaire qu'au moment de la transcription, qui se
  /// rechargera lazy).
  Future<void> requestGemma() async {
    if (_holder == _Holder.gemma) return;
    if (_holder == _Holder.voice) {
      await _evictVoice();
    }
    _holder = _Holder.gemma;
  }

  /// Libère le verrou côté voix. À appeler quand `VoiceService` dispose ou
  /// désinstalle son modèle. Idempotent.
  void releaseVoice() => _release(_Holder.voice);

  /// Libère le verrou côté Gemma. À appeler quand `GemmaService` dispose
  /// ou que le modèle est désinstallé. Idempotent.
  void releaseGemma() => _release(_Holder.gemma);

  void _release(_Holder holder) {
    if (_holder == holder) _holder = _Holder.none;
  }
}

/// Énumération privée — l'API publique du guard n'expose que des méthodes
/// nommées (`requestVoice` / `requestGemma`), pas d'enum à passer en
/// paramètre. Évite des appels inversés par erreur côté caller.
enum _Holder { none, voice, gemma }
