/// Point d'entrée — initialisation puis injection de dépendances.
///
/// Bootstrap minimal : réglages, base chiffrée, repositories, puis
/// `runApp`. Aucun modèle n'est chargé au démarrage — l'application ne
/// dépend plus d'aucun moteur d'inférence.
library;

import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:path_provider/path_provider.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_sqlcipher/sqflite.dart' show Database;

import 'app.dart';
import 'core/constants.dart';
import 'data/db/database.dart';
import 'data/db/folders_dao.dart';
import 'data/db/links_dao.dart';
import 'data/db/notes_dao.dart';
import 'data/repositories/folders_repository.dart';
import 'data/repositories/links_repository.dart';
import 'data/repositories/notes_repository.dart';
import 'services/backlinks_service.dart';
import 'services/first_launch_flag.dart';
import 'services/ml/ml_memory_guard.dart';
import 'services/secure_window_service.dart';
import 'services/security/folder_vault_service.dart';
import 'services/security/panic_service.dart';
import 'services/security/vault_service.dart';
import 'services/settings_service.dart';
import 'services/voice/voice_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // v1.0.7.1 hotfix — rollback de la parallélisation M5.
  // Symptôme terrain : page blanche bloquée sur certains devices/scenarios
  // de cold boot. On revient au pattern v1.0.6 (await strict avant tout
  // autre bootstrap) qui est éprouvé. Coût série assumé (~80-200 ms sur
  // S9 froid) — préférable à un risque de blocage de l'initialisation.
  //
  await SystemChrome.setPreferredOrientations(<DeviceOrientation>[
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

  // F4 v1.0.3 — purge `cache/exports/` (résidu ZIP plaintext si le process
  // a été tué pendant un share sheet précédent). Best-effort, fire-and-forget.
  unawaited(_purgeExportsCache());

  // v1.1.7 — récupère l'espace laissé par l'IA retirée. Une seule fois.
  unawaited(_purgeOrphanModelsOnce());

  // VaultService injecté avant tout accès DB : `AppDatabase` réutilisera
  // la même instance (source de vérité unique pour la KEK, testable).
  final vault = VaultService();
  AppDatabase.instance.useVault(vault);

  // Bootstraps en parallèle (~50-100 ms cumulés).
  // v1.0 : init FR + EN — la locale active est résolue côté UI via
  // `AppLocalizations` (suit la locale système ou le choix utilisateur).
  final dateInitFr = initializeDateFormatting('fr_FR');
  final dateInitEn = initializeDateFormatting('en_US');
  final settingsInit = SettingsService.create();
  final dbInit = AppDatabase.instance.db;
  await dateInitFr;
  await dateInitEn;
  final settings = await settingsInit;
  final Database db = await dbInit;

  // FLAG_SECURE est appliqué côté natif dans `MainActivity.onCreate` à
  // partir de la pref persistée — l'appel ci-dessous est défensif :
  // garantit l'alignement Dart ⇄ natif si la pref a été modifiée
  // pendant que l'activity était en pause.
  final secureWindow = SecureWindowService();
  unawaited(secureWindow.setEnabled(settings.secureWindowEnabled));

  // Couche données. `foldersRepo` est construit AVANT `notesRepo` : ce
  // dernier lui délègue la garde d'invariant du coffre, qui refuse de
  // persister le contenu en clair d'une note appartenant à un dossier
  // coffre (cf. `VaultPlaintextWriteException`).
  final foldersRepo = FoldersRepository(FoldersDao(db));
  final notesRepo = NotesRepository(
    NotesDao(db),
    isVaultFolder: foldersRepo.isVaultFolder,
  );
  final linksRepo = LinksRepository(LinksDao(db));

  // Service de backlinks `[[Titre]]` — écoute les changements de notes
  // pour réindexer en arrière-plan (debounced).
  final backlinks = BacklinksService(notes: notesRepo, links: linksRepo);

  // Service voix (v0.6) — partage la même instance SharedPreferences que
  // le reste de l'app pour cohérence et pour réduire le coût d'init.
  // Le bootstrap retrouve un éventuel modèle déjà installé et purge les
  // WAV temp orphelins d'un crash précédent.
  final voicePrefs = await SharedPreferences.getInstance();
  // Arbitrage RAM des modèles lourds sur téléphones 4 Go (POCO C75, S9).
  // Depuis le retrait de l'IA, Whisper est le SEUL consommateur : le garde
  // ne libère plus rien en face, il sérialise juste les chargements.
  // Forward reference via closure pour éviter la dépendance circulaire.
  late final VoiceService voice;
  final mlGuard = MlMemoryGuard(evictVoice: () async => voice.unloadEngine());
  voice = VoiceService(prefs: voicePrefs, mlGuard: mlGuard);
  unawaited(voice.bootstrap());

  // Mode panique (v0.7) — orchestrateur d'effacement irréversible. Tous
  // les services qu'il a besoin de wiper lui sont injectés explicitement
  // pour rester testable. Pas de Singleton magique : un test peut créer
  // un PanicService avec des fakes.
  //
  // `beforeDbWipe` ferme proprement le worker de backlinks AVANT que la
  // DB soit écrasée : sans ça, une écriture en vol via `notesRepo.changes`
  // pourrait tomber sur une DB fermée et lever une exception cosmétique.

  unawaited(backlinks.start());

  // FolderVaultService (v0.8) — orchestrateur des coffres par dossier.
  // Lit le timeout d'auto-lock depuis Settings au démarrage ; le widget
  // _VaultAutoLockTile appelle setAutoLockAfter quand l'utilisateur
  // change la valeur.
  final folderVault = FolderVaultService(
    folders: foldersRepo,
    notes: notesRepo,
    autoLockAfter: Duration(minutes: settings.vaultAutoLockMinutes),
  );
  // v0.9 — reprend les auto-wipes de coffres PIN interrompus par un
  // crash ou un kill app entre les steps internes (delete Keystore →
  // delete locked notes → demote folder). Idempotent, fire-and-forget.
  unawaited(folderVault.resumePendingWipes());

  // PanicService instancié ICI car son hook `beforeDbWipe` capture
  // coordinator / indexing / backlinks pour les disposer avant le
  // wipe DB (cf. doc panic_service.dart).
  final panic = PanicService(
    voice: voice,
    vault: vault,
    database: AppDatabase.instance,
    secureWindow: secureWindow,
    prefs: voicePrefs,
    lockAllFolders: () async => folderVault.lockAll(),
    beforeDbWipe: () async {
      // `backlinks` ferme son StreamSubscription sur `notesRepo.changes`.
      // On l'attend : le service doit être EFFECTIVEMENT arrêté avant que
      // `db.wipe()` ne ferme la base, sinon une écriture en vol tomberait
      // sur une base fermée.
      //
      // P3-2 : timeout par dispose pour ne JAMAIS bloquer la séquence
      // panique. 2 s c'est large pour un dispose normal (ms) ; au-delà,
      // on assume qu'un service est bloqué et on continue le wipe — le
      // mode panique doit aller au bout coûte que coûte.
      const timeout = Duration(seconds: 2);
      await backlinks.dispose().timeout(timeout, onTimeout: () {});
    },
  );

  // v1.1.1 — splash de presentation Files Tech au tout premier lancement
  // uniquement. SharedPreferences deja hydrate via voicePrefs ci-dessus,
  // lecture peu couteuse.
  final showSplash = await FirstLaunchFlag.shouldShow();

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider<SettingsService>.value(value: settings),
        Provider<VaultService>.value(value: vault),
        Provider<SecureWindowService>.value(value: secureWindow),
        Provider<NotesRepository>.value(value: notesRepo),
        Provider<FoldersRepository>.value(value: foldersRepo),
        Provider<LinksRepository>(
          create: (_) => linksRepo,
          dispose: (_, r) => r.dispose(),
        ),
        Provider<BacklinksService>(
          create: (_) => backlinks,
          dispose: (_, s) => s.dispose(),
        ),
        ChangeNotifierProvider<VoiceService>.value(value: voice),
        ChangeNotifierProvider<FolderVaultService>.value(value: folderVault),
        Provider<PanicService>.value(value: panic),
      ],
      child: NotesTechApp(showSplash: showSplash),
    ),
  );
}

/// Supprime, UNE SEULE FOIS, les modèles devenus orphelins avec le retrait
/// de l'IA embarquée (v1.1.7).
///
/// Un utilisateur qui avait importé Gemma garde sinon **jusqu'à 530 Mo** dans
/// le stockage privé de l'application : un fichier que plus aucun code ne lit,
/// qu'il ne voit pas, et qu'il ne peut effacer qu'en vidant les données de
/// l'app — ce qui détruirait aussi ses notes. L'application vient de passer
/// de 127 à 27 Mo ; lui laisser un demi-gigaoctet de cadavre serait absurde.
///
/// ⚠️ Cible UNIQUEMENT `<appSupport>/models/`, qui ne contenait que le `.task`
/// Gemma et le cache MiniLM. Le modèle de dictée vocale vit dans
/// `<appSupport>/stt/` (cf. `files_tech_voice/stt_model_downloader.dart`) et
/// n'est PAS touché — vérifié avant d'écrire cette fonction, parce que se
/// tromper de dossier ici effacerait un modèle que l'utilisateur a dû
/// télécharger et importer à la main.
///
/// Idempotent et gardé par une préférence : le coût au boot est une lecture
/// de pref une fois la purge faite.
Future<void> _purgeOrphanModelsOnce() async {
  try {
    final prefs = await SharedPreferences.getInstance();
    if (prefs.getBool(AppConstants.prefKeyOrphanModelsPurged) ?? false) return;
    final dir = await getApplicationSupportDirectory();
    final models = Directory('${dir.path}/models');
    if (await models.exists()) {
      await models.delete(recursive: true);
    }
    await prefs.setBool(AppConstants.prefKeyOrphanModelsPurged, true);
  } catch (_) {
    // Best-effort : réessayé au prochain démarrage, le drapeau n'est posé
    // qu'en cas de succès.
  }
}

/// F4 v1.0.3 — purge `cache/exports/` au boot. Si le process a été tué
/// pendant un Share sheet (panic, OOM, force stop), le `finally`
/// supprimant le ZIP plaintext n'a pas pu tirer → résidu sur disque.
/// Le boot suivant nettoie. Best-effort.
Future<void> _purgeExportsCache() async {
  try {
    final tmpDir = await getTemporaryDirectory();
    final exportsDir = Directory('${tmpDir.path}/exports');
    if (await exportsDir.exists()) {
      await exportsDir.delete(recursive: true);
    }
  } catch (_) {
    // Best-effort : pas de cache à purger ou perm refusée.
  }
}
