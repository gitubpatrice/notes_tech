/// **Mode panique** — destruction irréversible des données sensibles.
///
/// Cible : journaliste, avocat, praticien bien-être confronté à une
/// fouille / contrainte physique. L'objectif est de rendre la base de
/// notes irrécupérable en quelques secondes, même si l'attaquant a déjà
/// le téléphone déverrouillé en main.
///
/// Stratégie de défense en profondeur — **ordre déterministe** pour
/// garantir qu'une interruption (SIGKILL système, batterie morte) laisse
/// l'état dans la position la plus sûre possible :
///
///   1. Couper la capture micro (le WAV temp est supprimé).
///   2. **DÉTRUIRE LA KEK en premier** — point de non-retour. À partir
///      de cet instant, même si le SIGKILL tombe ici, la DB chiffrée
///      AES-256-GCM est déjà cryptographiquement illisible.
///   3. **Écraser puis supprimer** le fichier DB SQLCipher et ses
///      sidecars (-journal, -wal, -shm). Filets contre les outils de
///      récupération de secteurs marqués libres (TRIM/GC mitigent déjà
///      sur eMMC moderne, mais défense en profondeur).
///   4. Whisper : .bin + cache de vérification + WAV orphelins.
///   5. Gemma : .task ~530 Mo + dispose du contexte natif (le plus
///      long step, vient en dernier des effacements lourds — la KEK
///      est déjà partie depuis 1-3 s).
///   6. Préférences : tri, dossier actif, hash Gemma accepté, modèle
///      voix actif… aucun reliquat d'usage.
///   7. Tmp : ZIPs d'export + autres résidus.
///
/// **Pourquoi KEK avant les modèles ML lourds** : les uninstalls
/// Gemma/Whisper peuvent prendre plusieurs secondes (delete + dispose
/// natif). Si la panique est interrompue à ce moment, la garantie de
/// sécurité MINIMALE (DB illisible) doit déjà être tenue. Le mode
/// panique antérieur exécutait Gemma avant KEK — corrigé suite à
/// l'audit (faille temporelle).
///
/// **Pause des background workers** (indexing, backlinks, embedder
/// coordinator) : ces services écrivent dans la DB en réaction aux
/// `notesRepo.changes`. Sans pause, une race fenêtrée peut écrire dans
/// la DB pendant son écrasement → exceptions cosmétiques. Le caller
/// peut fournir un [beforeDbWipe] qui dispose ces services proprement
/// avant `db.wipe()`.
///
/// **FLAG_SECURE** : forcé ON au début de la séquence pour empêcher
/// la capture du dialog de confirmation et de l'écran de fin dans le
/// snapshot Recents Android (résidu jusqu'au reboot sinon).
///
/// **Coffres par dossier (v0.8/v0.9)** : couvert par deux mécanismes
/// distincts. (a) Mode passphrase : la `folder_kek` n'existe qu'en RAM
/// pendant les sessions actives ; le wipe DB efface `vault_kek_wrapped`,
/// donc impossible à dériver à nouveau. (b) Mode PIN : le step
/// [PanicStep.pinKeysWipe] supprime explicitement toutes les clés
/// AndroidKeystore `vault_pin_*` AVANT la destruction de la KEK
/// SQLCipher, empêchant un attaquant qui aurait pré-extrait la DB de
/// rebrute-forcer les coffres PIN avec les clés Keystore résiduelles.
///
/// Tous les steps sont best-effort : si un échec survient, on continue
/// les autres. La garantie minimale = KEK détruite = DB illisible.
library;

import 'dart:async';

import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import '../../data/db/database.dart';
import '../ai/gemma_service.dart';
import '../secure_window_service.dart';
import '../voice/voice_service.dart';
import 'keystore_bridge.dart';
import 'vault_service.dart';

/// Bilan d'une exécution de panique. Sert au logging interne (jamais
/// exposé à l'UI publique) et permet aux tests d'asserter l'ordre.
class PanicReport {
  PanicReport({required this.startedAt});
  final DateTime startedAt;
  late final DateTime endedAt;
  final List<PanicStep> steps = [];
  final List<String> errors = [];

  Duration get duration => endedAt.difference(startedAt);

  void recordSuccess(PanicStep step) => steps.add(step);

  void recordFailure(PanicStep step, Object error) {
    steps.add(step);
    // Erreur stockée sans le détail technique pour ne pas leaker de path
    // (ex. KEK hex dans un chemin de fichier).
    errors.add('$step: ${error.runtimeType}');
  }
}

enum PanicStep {
  forceSecureWindow,
  voiceCancel,
  foldersLockAll,
  pinKeysWipe,
  kekDestroy,
  pauseBackgroundWork,
  dbWipe,
  voiceWipe,
  gemmaUninstall,
  prefsClear,
  tmpPurge,
}

/// Orchestrateur du mode panique. Construction explicite avec injection
/// pour pouvoir tester chaque step en isolation.
class PanicService {
  PanicService({
    required VoiceService voice,
    required GemmaService gemma,
    required VaultService vault,
    required AppDatabase database,
    required SecureWindowService secureWindow,
    required SharedPreferences prefs,
    Future<void> Function()? beforeDbWipe,
    Future<void> Function()? lockAllFolders,
    KeystoreBridge? keystore,
  }) : _voice = voice,
       _gemma = gemma,
       _vault = vault,
       _db = database,
       _secureWindow = secureWindow,
       _prefs = prefs,
       _beforeDbWipe = beforeDbWipe,
       _lockAllFolders = lockAllFolders,
       _keystore = keystore ?? KeystoreBridge();

  final VoiceService _voice;
  final GemmaService _gemma;
  final VaultService _vault;
  final AppDatabase _db;
  final SecureWindowService _secureWindow;
  final SharedPreferences _prefs;
  final KeystoreBridge _keystore;

  /// Hook injecté par `main.dart` pour disposer les background workers
  /// (`EmbedderCoordinator`, `IndexingService`, `BacklinksService`)
  /// AVANT que la DB soit écrasée. Sans ça, une écriture en vol dans
  /// `notesRepo.changes` peut tomber sur une DB déjà fermée et lever
  /// une exception cosmétique.
  final Future<void> Function()? _beforeDbWipe;

  /// Hook injecté par `main.dart` pour verrouiller tous les coffres par
  /// dossier (zeroize les `folder_kek` en RAM dans le map des sessions
  /// déverrouillées de `FolderVaultService`) AVANT le wipe Keystore et la
  /// destruction de la KEK SQLCipher. Sans ça, si l'utilisateur déclenche
  /// le mode panique alors qu'un coffre est déverrouillé en foreground,
  /// la `folder_kek` reste en RAM jusqu'au lifecycle paused — fenêtre
  /// d'extraction RAM théorique pendant la séquence panique.
  final Future<void> Function()? _lockAllFolders;

  /// Une seule panique à la fois — un double-tap rapide ne déclenche pas
  /// deux exécutions concurrentes (qui pourraient toutes deux tenter de
  /// fermer la même DB et provoquer des erreurs cosmétiques).
  Future<PanicReport>? _inFlight;

  bool get isInProgress => _inFlight != null;

  /// Déclenche la séquence de panique. Idempotent au sens où un double
  /// appel pendant une exécution renvoie la même Future.
  ///
  /// Retourne un [PanicReport] qui résume ce qui a été fait. Ne **lève
  /// jamais** d'exception : tout est best-effort, l'UI peut afficher la
  /// même page de fin quoi qu'il arrive.
  Future<PanicReport> trigger() {
    final pending = _inFlight;
    if (pending != null) return pending;
    final fresh = _doTrigger();
    _inFlight = fresh;
    return fresh.whenComplete(() {
      if (identical(_inFlight, fresh)) _inFlight = null;
    });
  }

  Future<PanicReport> _doTrigger() async {
    final report = PanicReport(startedAt: DateTime.now());

    // 0. Force FLAG_SECURE — empêche le snapshot Recents Android de
    //    capturer le dialogue de progression / écran de fin (résidu
    //    sinon dans /data/system_ce/0/recent_* jusqu'au reboot).
    await _runStep(report, PanicStep.forceSecureWindow, () async {
      await _secureWindow.setEnabled(true);
    });

    // 1. Coupe le micro en cours. cancelRecording supprime le WAV temp.
    //    Très rapide (~ms) — placé tôt pour libérer le AudioRecord
    //    natif avant que voiceWipe ne touche au .bin Whisper.
    await _runStep(report, PanicStep.voiceCancel, () async {
      await _voice.cancelRecording();
    });

    // 1.4 (v0.9.4). Verrouille TOUS les coffres par dossier déverrouillés
    //     en foreground : zeroize les `folder_kek` en RAM AVANT le wipe
    //     Keystore et la destruction de la KEK. Sans ça, une panique
    //     déclenchée pendant qu'un coffre est ouvert laisse la folder_kek
    //     en RAM jusqu'au lifecycle paused — fenêtre RAM exploitable
    //     pendant la séquence panique. Best-effort : si le hook lève, on
    //     continue (la KEK détruite reste la garantie minimale).
    final lockFolders = _lockAllFolders;
    if (lockFolders != null) {
      await _runStep(report, PanicStep.foldersLockAll, lockFolders);
    }

    // 1.5 (v0.9). Wipe TOUTES les clés Keystore `vault_pin_*` AVANT la
    //     destruction de la KEK. Empêche un attaquant qui aurait pré-
    //     extrait un backup de la DB de restaurer le device et bruteforcer
    //     les coffres PIN encore référencés par leur clé Keystore (le
    //     scellage hardware-bound est leur seule barrière contre l'attaque
    //     hors-device — sans la clé Keystore, le blob `vault_pin_blob`
    //     devient cryptographiquement illisible).
    //     Ne dépend pas de la DB → exécutable même si SQLCipher est déjà
    //     fermé. Best-effort : un échec ici ne bloque pas la suite (la
    //     KEK détruite + DB wipée restent la garantie minimale).
    await _runStep(report, PanicStep.pinKeysWipe, () async {
      await _keystore.deleteKeysWithPrefix(
        AppConstants.vaultPinKeystoreAliasPrefix,
      );
    });

    // 2. **POINT DE NON-RETOUR** — KEK détruite. La DB chiffrée AES-256
    //    devient cryptographiquement illisible, même si récupérée bit
    //    à bit. À partir d'ici, un SIGKILL système ne perd plus la
    //    garantie de sécurité minimale.
    await _runStep(report, PanicStep.kekDestroy, _vault.destroyKek);

    // 3. Pause des background workers (EmbedderCoordinator, Indexing,
    //    Backlinks) AVANT le wipe DB — sinon une écriture en vol via
    //    notesRepo.changes peut tomber sur une DB fermée. Best-effort
    //    via callback injecté par main.dart.
    final hook = _beforeDbWipe;
    if (hook != null) {
      await _runStep(report, PanicStep.pauseBackgroundWork, hook);
    }

    // 4. Écrase + supprime le fichier DB et ses sidecars. Défense en
    //    profondeur — la KEK est déjà détruite, mais on évite de
    //    laisser le fichier au cas où une faiblesse crypto serait
    //    découverte plus tard sur AES-256-GCM.
    await _runStep(report, PanicStep.dbWipe, _db.wipe);

    // 5. Whisper : modèles + cache de vérification + WAV orphelins.
    await _runStep(report, PanicStep.voiceWipe, _voice.wipeAll);

    // 6. Gemma : modèle .task (~530 Mo) + dispose du contexte natif.
    //    Le plus long step — vient APRÈS la garantie de sécurité (la
    //    KEK est partie depuis plusieurs steps).
    await _runStep(report, PanicStep.gemmaUninstall, _gemma.uninstall);

    // 7. Préférences : tri, dossier actif, hash Gemma accepté, modèle
    //    voix actif… aucun reliquat d'usage.
    //
    // **Whitelist** : on PRÉSERVE deux clés pour la cohérence du redémarrage,
    // conformément à PRIVACY.{fr,en}.md :
    //   - `db_encrypted_v1` : flag indiquant que la DB est chiffrée. Si on
    //     l'effaçait, le prochain démarrage déclencherait à tort une fausse
    //     "migration plain → encrypted" sur une DB déjà absente.
    //   - `secure_window_enabled` : préférence FLAG_SECURE — si on l'effaçait,
    //     le prochain démarrage perd la protection screenshot pendant que
    //     l'utilisateur reconfigure l'app.
    //
    // Tous les autres prefs (theme, locale, sort, vault_wipe_pending_*,
    // vault_auto_lock_minutes, accept_unknown_gemma_hash, etc.) sont effacés.
    // Les `vault_wipe_pending_*` doivent rester effacés — ce sont des flags
    // de reprise après crash qui n'ont plus de sens après wipe DB.
    await _runStep(report, PanicStep.prefsClear, _prefsClearWithWhitelist);

    // 8. Tmp : ZIPs d'export + autres résidus. Best-effort, Android purge.
    await _runStep(report, PanicStep.tmpPurge, _purgeTempDirectory);

    report.endedAt = DateTime.now();
    return report;
  }

  Future<void> _runStep(
    PanicReport report,
    PanicStep step,
    Future<void> Function() action,
  ) async {
    try {
      await action();
      report.recordSuccess(step);
    } catch (e) {
      // On capture mais on ne stoppe pas — chaque step est indépendant
      // et la garantie minimale (KEK destroy) doit aboutir même si un
      // step antérieur échoue (ex. Gemma déjà désinstallé).
      report.recordFailure(step, e);
    }
  }

  /// Clés SharedPreferences PRÉSERVÉES par `prefs.clear()` en mode panique
  /// — conformément à la promesse `PRIVACY.{fr,en}.md` (cohérence redémarrage).
  static const Set<String> _panicPreservedKeys = {
    AppConstants.prefKeyDbEncryptedV1,
    AppConstants.prefKeySecureWindowEnabled,
  };

  /// Efface toutes les prefs sauf celles de [_panicPreservedKeys].
  /// Implémenté en boucle `remove` plutôt que `clear` pour respecter la
  /// whitelist. Best-effort : un échec sur une clé n'arrête pas la séquence.
  Future<void> _prefsClearWithWhitelist() async {
    final keys = _prefs
        .getKeys()
        .where((k) => !_panicPreservedKeys.contains(k))
        .toList(growable: false);
    for (final k in keys) {
      try {
        await _prefs.remove(k);
      } catch (_) {
        // Best-effort
      }
    }
  }

  Future<void> _purgeTempDirectory() async {
    final tmp = await getTemporaryDirectory();
    if (!await tmp.exists()) return;
    await for (final entity in tmp.list()) {
      try {
        await entity.delete(recursive: true);
      } catch (_) {
        // Best-effort, certains fichiers peuvent être tenus par d'autres
        // processus système.
      }
    }
  }
}
