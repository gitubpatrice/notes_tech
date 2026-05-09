import 'dart:async';

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
  }) : _evictGemma = evictGemma,
       _evictVoice = evictVoice;

  final Future<void> Function() _evictGemma;
  final Future<void> Function() _evictVoice;

  _Holder _holder = _Holder.none;

  /// Mutex sériel : empêche un `requestVoice()` et un `requestGemma()`
  /// concurrents de lire `_holder` au même moment puis d'écrire des
  /// décisions contradictoires (ex. les deux pensent qu'aucun n'évince
  /// l'autre → 2 moteurs ML en RAM → OOM sur 4 Go). Toutes les
  /// transitions passent par cette chaîne.
  Future<void> _chain = Future<void>.value();

  /// Timeout dur sur chaque éviction. Si le dispose natif (JNI MediaPipe
  /// ou whisper.cpp) hang sur OOM extrême ou état corrompu, on ne bloque
  /// pas l'UI indéfiniment : on assume que la RAM se libérera via le GC
  /// natif éventuel et on force le transfert de verrou.
  static const Duration _evictTimeout = Duration(seconds: 5);

  /// Le service voix appelle ceci AVANT `WhisperGgmlStt.initialize()`.
  /// Si Gemma détient le verrou, on l'évince (libération RAM) puis on
  /// transfère le verrou à voix. Timeout dur sur l'éviction.
  Future<void> requestVoice() => _serialize(() async {
    if (_holder == _Holder.voice) return;
    if (_holder == _Holder.gemma) {
      await _evictWithTimeout(_evictGemma);
    }
    _holder = _Holder.voice;
  });

  /// Le service Gemma appelle ceci AVANT `gemma.warmUp()`. Si voix détient
  /// le verrou, on l'évince. Si voix est en plein recording, on évincera
  /// quand même le moteur Whisper. Timeout dur sur l'éviction.
  Future<void> requestGemma() => _serialize(() async {
    if (_holder == _Holder.gemma) return;
    if (_holder == _Holder.voice) {
      await _evictWithTimeout(_evictVoice);
    }
    _holder = _Holder.gemma;
  });

  /// Wrappe `evict()` avec un timeout dur de [_evictTimeout]. Si l'éviction
  /// hang (JNI bloqué, OOM extrême), on continue après le timeout :
  /// l'utilisateur ne perd pas l'UI sur un moteur ML cassé. Le `_holder`
  /// sera réécrit par le caller, donc le moteur potentiellement encore
  /// présent en RAM sera évincé naturellement au prochain cold start.
  static Future<void> _evictWithTimeout(Future<void> Function() evict) async {
    try {
      await evict().timeout(_evictTimeout);
    } on TimeoutException {
      // Best-effort : on log en debug uniquement, pas de telemetry.
      // L'objectif est de NE PAS bloquer l'UI si le natif hang.
    } catch (_) {
      // Best-effort : si evict throw, on continue (le caller décide
      // d'attribuer le verrou quand même — sinon dead-lock total si
      // l'eviction est foireuse).
    }
  }

  Future<void> _serialize(Future<void> Function() body) {
    final next = _chain.then((_) => body());
    // Catch sur la chaîne pour qu'une éviction qui throw ne bloque pas les
    // appels suivants. L'erreur est propagée au caller via `next`.
    _chain = next.catchError((_) {});
    return next;
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
