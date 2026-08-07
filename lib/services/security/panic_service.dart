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
///   5. Fichiers de modèles hérités : `<appSupport>/models/`, résidu des
///      versions ≤ 1.1.6 qui embarquaient une IA (jusqu'à 530 Mo).
///   6. Préférences : tri, dossier actif, modèle voix actif… aucun
///      reliquat d'usage.
///   7. Tmp : ZIPs d'export + autres résidus.
///
/// **Pourquoi KEK avant les effacements lourds** : la désinstallation des
/// modèles peut prendre plusieurs secondes (delete + dispose natif). Si la
/// panique est interrompue à ce moment, la garantie de sécurité MINIMALE
/// (DB illisible) doit déjà être tenue. Un ordre antérieur plaçait les
/// modèles avant la KEK — corrigé suite à l'audit (faille temporelle).
///
/// **Pause des background workers** (`BacklinksService`) : ces services
/// écrivent dans la DB en réaction aux
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
import 'dart:io';

import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import '../../core/exceptions.dart';
import '../../data/db/database.dart';
import '../legacy_model_files.dart';
import '../note_actions.dart';
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

  /// v1.1.5 — vide immédiatement le presse-papiers (une note copiée via
  /// `NoteActions.copyMarkdown` y est en clair jusqu'à l'auto-clear 60 s).
  /// Placé tôt, comme voiceCancel : coupe l'exposition immédiate.
  clipboardClear,
  foldersLockAll,
  pinKeysWipe,
  kekDestroy,
  pauseBackgroundWork,
  dbWipe,
  voiceWipe,

  /// Purge `<appSupport>/models/` — le dossier où vivaient le modèle Gemma
  /// importé par l'utilisateur et le cache MiniLM.
  ///
  /// Renommée depuis `embedderWipe` en v1.1.7 : elle portait le nom d'un
  /// composant supprimé, alors que son travail réel — effacer des fichiers
  /// hérités — lui a survécu.
  ///
  /// ⚠️ CONSERVÉE malgré le retrait de l'IA, et c'est délibéré : un
  /// utilisateur qui met à jour depuis une version ≤ 1.1.6 a encore ces
  /// fichiers sur son téléphone (jusqu'à 530 Mo pour Gemma). Le mode panique
  /// doit continuer de les effacer.
  ///
  /// ⚠️ À NE PAS CONFONDRE avec une AUTRE étape, `gemmaUninstall`, qui a bien
  /// été supprimée, elle : elle passait par un service qui n'existe plus,
  /// donc elle ne s'exécutait plus, et une étape déclarée qui ne tourne
  /// jamais est un mensonge dans `PanicReport.steps`. Cette distinction a
  /// déjà été mal lue une fois en relecture : ne pas supprimer l'étape
  /// ci-dessous en croyant appliquer ce paragraphe.
  legacyModelsWipe,
  prefsClear,

  /// v1.1.4 — purge `cache/exports/` (sandbox cache, hors temp).
  /// Distinguée de [tmpPurge] pour que `PanicReport.steps` reste sans ambiguïté.
  exportsWipe,
  tmpPurge,
}

/// Orchestrateur du mode panique. Construction explicite avec injection
/// pour pouvoir tester chaque step en isolation.
class PanicService {
  PanicService({
    required VoiceService voice,
    required VaultService vault,
    required AppDatabase database,
    required SecureWindowService secureWindow,
    required SharedPreferences prefs,
    Future<void> Function()? beforeDbWipe,
    Future<void> Function()? lockAllFolders,
    KeystoreBridge? keystore,
  }) : _voice = voice,
       _vault = vault,
       _db = database,
       _secureWindow = secureWindow,
       _prefs = prefs,
       _beforeDbWipe = beforeDbWipe,
       _lockAllFolders = lockAllFolders,
       _keystore = keystore ?? KeystoreBridge();

  final VoiceService _voice;
  final VaultService _vault;
  final AppDatabase _db;
  final SecureWindowService _secureWindow;
  final SharedPreferences _prefs;
  final KeystoreBridge _keystore;

  /// Hook injecté par `main.dart` pour disposer les background workers
  /// (`BacklinksService`) AVANT que la DB soit écrasée. Sans ça, une écriture en vol dans
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

    // 1.2 (v1.1.5). Vide immédiatement le presse-papiers. Une note copiée
    //     (NoteActions.copyMarkdown) y est en clair jusqu'à l'auto-clear
    //     60 s ; en panique on n'attend pas ce délai. cancelAndClear efface
    //     inconditionnellement (contrairement à l'auto-clear qui vérifie
    //     l'ownership) + annule le timer + reset le snapshot. Best-effort.
    await _runStep(
      report,
      PanicStep.clipboardClear,
      NoteActions.cancelAndClear,
    );

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

    // 3. Pause des background workers (BacklinksService) AVANT le wipe
    //    DB — sinon une écriture en vol via
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

    // 6. Fichiers de modèles hérités (`<appSupport>/models/`) : le .task
    //    Gemma importé et le cache MiniLM des versions ≤ 1.1.6. Plus aucun
    //    code n'écrit dans ce dossier, mais un utilisateur qui met à jour
    //    l'a toujours — jusqu'à 530 Mo. Zéro résidu de modèle post-panique.
    //    Vient APRÈS la garantie de sécurité : la KEK est partie depuis
    //    plusieurs steps.
    await _runStep(report, PanicStep.legacyModelsWipe, _wipeLegacyModelFiles);

    // 7. Préférences : tri, dossier actif, modèle voix actif… aucun
    //    reliquat d'usage.
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
    // vault_auto_lock_minutes, etc.) sont effacés.
    // Les `vault_wipe_pending_*` doivent rester effacés — ce sont des flags
    // de reprise après crash qui n'ont plus de sens après wipe DB.
    await _runStep(report, PanicStep.prefsClear, _prefsClearWithWhitelist);

    // F7 v1.1.0 — purge `cache/exports/` (sous-dossier ZIP partage). Avant :
    // `_purgeTempDirectory` (étape 8) ratait ce dossier qui est dans
    // `getApplicationCacheDirectory()` et non `getTemporaryDirectory()`.
    // Un export en cours de Share (Intent EXTRA_STREAM) restait en clair.
    await _runStep(report, PanicStep.exportsWipe, _wipeExportsCache);

    // 8. Tmp : ZIPs d'export + autres résidus. Best-effort, Android purge.
    await _runStep(report, PanicStep.tmpPurge, _purgeTempDirectory);

    report.endedAt = DateTime.now();
    return report;
  }

  /// Purge le dossier `exports/` qui contient les ZIP en CLAIR produits
  /// avant un partage (`settings_screen._exportAllNotes`). Si le process
  /// meurt entre le Share et la purge du boot, un attaquant root lit le
  /// contenu exporté.
  ///
  /// ⚠️ LES DEUX EMPLACEMENTS SONT PURGÉS, délibérément. L'export écrit
  /// dans `getTemporaryDirectory()/exports/` ; cette méthode ne visait que
  /// `getApplicationCacheDirectory()/exports/`, et son commentaire affirmait
  /// même qu'il s'agissait d'un dossier « hors temp ». Sur Android les deux
  /// résolvent en pratique vers `context.getCacheDir()`, mais s'en remettre
  /// à cette équivalence est un pari : elle dépend de la version du plugin
  /// `path_provider` et peut changer sans que rien ne le signale ici. Purger
  /// les deux coûte un `exists()` de plus et supprime la question.
  ///
  /// Relevé par une relecture externe (GPT-5.2) comme surface T1.
  Future<void> _wipeExportsCache() async {
    var echecs = 0;
    for (final futureDir in <Future<Directory>>[
      getTemporaryDirectory(),
      getApplicationCacheDirectory(),
    ]) {
      try {
        final base = await futureDir;
        final exportsDir = Directory('${base.path}/exports');
        if (await exportsDir.exists()) {
          await exportsDir.delete(recursive: true);
        }
      } catch (_) {
        // Indépendant d'un emplacement à l'autre : si l'un échoue, l'autre
        // doit quand même être tenté. Mais on ne le tait pas — un ZIP
        // d'export en clair qui survit doit apparaître dans le rapport.
        echecs++;
      }
    }
    if (echecs > 0) {
      throw DatabaseException(
        "Cache d'exports non purgé ($echecs emplacement(s))",
      );
    }
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
      // step antérieur échoue (ex. modèle déjà désinstallé).
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
  /// ⚠️ LÈVE si une préférence résiste, et c'est le point.
  ///
  /// Chaque `remove` était enveloppé dans un `catch (_)` muet : l'étape
  /// remontait « OK » vers `PanicReport` même quand rien n'avait été effacé.
  /// L'écran de fin s'appuie désormais sur ce rapport pour décider s'il
  /// annonce un effacement complet — un rapport optimiste redevient donc un
  /// mensonge à l'utilisateur. Relevé en CRITIQUE par une relecture externe
  /// (GPT-5.5) sur le correctif précédent.
  ///
  /// On continue malgré tout la boucle : une clé récalcitrante ne doit pas
  /// empêcher d'effacer les suivantes. C'est à la FIN qu'on signale.
  Future<void> _prefsClearWithWhitelist() async {
    final keys = _prefs
        .getKeys()
        .where((k) => !_panicPreservedKeys.contains(k))
        .toList(growable: false);
    var survivantes = 0;
    for (final k in keys) {
      try {
        await _prefs.remove(k);
      } catch (_) {
        survivantes++;
      }
    }
    if (survivantes > 0) {
      throw DatabaseException(
        'Préférences non effacées : $survivantes sur ${keys.length}',
      );
    }
  }

  /// ⚠️ LÈVE si une entrée résiste — même raison que
  /// `_prefsClearWithWhitelist` : un ZIP d'export en clair verrouillé restait
  /// sur le disque pendant que l'étape se déclarait réussie.
  Future<void> _purgeTempDirectory() async {
    final tmp = await getTemporaryDirectory();
    if (!await tmp.exists()) return;
    final survivantsSensibles = <String>[];
    await for (final entity in tmp.list()) {
      final nom = entity.path.split(RegExp(r'[\/]')).last;
      try {
        await entity.delete(recursive: true);
      } catch (_) {
        // Certains fichiers peuvent être tenus par un autre processus. On
        // continue la boucle, mais on ne signale QUE ce qui nous appartient.
        if (_estArtefactSensible(nom)) survivantsSensibles.add(nom);
      }
    }
    if (survivantsSensibles.isNotEmpty) {
      throw DatabaseException(
        'Fichiers temporaires non effacés : ${survivantsSensibles.join(', ')}',
      );
    }
  }

  /// Dit si une entrée du dossier temporaire est un artefact de CETTE
  /// application susceptible de contenir du contenu de note.
  ///
  /// ⚠️ SANS CE FILTRE, LE MODE PANIQUE SE DÉCLARERAIT TOUJOURS INCOMPLET.
  /// `getTemporaryDirectory()` est partagé : plugins Flutter, composants
  /// système et caches divers y écrivent, et certains tiennent leurs fichiers
  /// ouverts. Faire échouer l'étape sur n'importe quel résidu transformait
  /// l'avertissement « effacement incomplet » en alarme permanente — donc en
  /// bruit qu'on apprend à ignorer, exactement au moment où il doit être cru.
  /// Relevé par une relecture externe (GPT-5.5).
  ///
  /// Ce qui nous appartient : le dossier `exports/`, les ZIP d'export, les
  /// `.md` d'export unitaire et les WAV de dictée.
  static bool _estArtefactSensible(String nom) {
    final n = nom.toLowerCase();
    return n == 'exports' ||
        n.endsWith('.zip') ||
        n.endsWith('.md') ||
        n.endsWith('.wav');
  }

  /// Purge `<appSupport>/models/` : modèle Gemma importé et cache MiniLM,
  /// résidus des versions ≤ 1.1.6. Ce dossier n'est plus alimenté par aucun
  /// code — seul un utilisateur venu d'une version antérieure en a encore.
  /// Cohérence avec `voiceWipe` : zéro résidu de modèle après une panique.
  /// Best-effort.
  ///
  /// Le boot purge déjà ce dossier une fois (`_purgeOrphanModelsOnce` dans
  /// `main.dart`) ; ce double geste est voulu : la panique ne doit dépendre
  /// d'aucune purge antérieure ayant réussi.
  Future<void> _wipeLegacyModelFiles() async {
    // Le retour est volontairement ignoré : `_runStep` enregistre déjà
    // l'issue de l'étape, et la panique continue quoi qu'il arrive.
    await LegacyModelFiles.purge();
  }
}
