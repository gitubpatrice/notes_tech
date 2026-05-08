/// Point d'entrée — initialisation parallèle puis injection de dépendances.
///
/// Stratégie de démarrage :
///   1. Bootstrap minimal (settings + DB + repos) → runApp avec LocalEmbedder.
///   2. Si l'utilisateur a activé la recherche sémantique avancée
///      dans les réglages, MiniLM est chargé en arrière-plan puis
///      pris en relais à chaud (swap d'embedder + reindex incrémental).
///   3. Le toggle peut être basculé à tout moment depuis Settings :
///      le `_EmbedderCoordinator` réagit à la prefs et upgrade/downgrade
///      sans relancer l'app.
///
/// Le 1er frame n'attend jamais ONNX ou MediaPipe.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_sqlcipher/sqflite.dart' show Database;

import 'app.dart';
import 'data/db/database.dart';
import 'data/db/embeddings_dao.dart';
import 'data/db/folders_dao.dart';
import 'data/db/links_dao.dart';
import 'data/db/notes_dao.dart';
import 'data/repositories/embeddings_repository.dart';
import 'data/repositories/folders_repository.dart';
import 'data/repositories/links_repository.dart';
import 'data/repositories/notes_repository.dart';
import 'services/ai/gemma_service.dart';
import 'services/backlinks_service.dart';
import 'services/embedder_coordinator.dart';
import 'services/embedding/embedding_provider.dart';
import 'services/embedding/local_embedder.dart';
import 'services/indexing_service.dart';
import 'services/ml/ml_memory_guard.dart';
import 'services/secure_window_service.dart';
import 'services/security/folder_vault_service.dart';
import 'services/security/panic_service.dart';
import 'services/security/vault_service.dart';
import 'services/semantic_search_service.dart';
import 'services/settings_service.dart';
import 'services/voice/voice_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations(<DeviceOrientation>[
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

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

  // Couche données.
  final notesRepo = NotesRepository(NotesDao(db));
  final foldersRepo = FoldersRepository(FoldersDao(db));
  final embeddingsRepo = EmbeddingsRepository(EmbeddingsDao(db));
  final linksRepo = LinksRepository(LinksDao(db));

  // Démarrage immédiat avec l'encodeur léger.
  const localEmbedder = LocalEmbedder();
  final activeEmbedder = ValueNotifier<EmbeddingProvider>(localEmbedder);

  final indexing = IndexingService(
    notes: notesRepo,
    embeddings: embeddingsRepo,
    embedder: localEmbedder,
  );
  final semantic = SemanticSearchService(
    notes: notesRepo,
    embeddings: embeddingsRepo,
    embedder: localEmbedder,
    indexing: indexing,
  );
  final gemma = GemmaService();

  // Service de backlinks `[[Titre]]` — écoute les changements de notes
  // pour réindexer en arrière-plan (debounced).
  final backlinks = BacklinksService(notes: notesRepo, links: linksRepo);

  // Service voix (v0.6) — partage la même instance SharedPreferences que
  // le reste de l'app pour cohérence et pour réduire le coût d'init.
  // Le bootstrap retrouve un éventuel modèle déjà installé et purge les
  // WAV temp orphelins d'un crash précédent.
  final voicePrefs = await SharedPreferences.getInstance();
  // Coordination RAM Gemma ↔ Whisper sur téléphones 4 Go (POCO C75, S9).
  // Forward references via closures pour éviter les dépendances circulaires
  // entre VoiceService et GemmaService.
  late final VoiceService voice;
  final mlGuard = MlMemoryGuard(
    evictGemma: () async {
      if (gemma.isReady) await gemma.dispose();
    },
    evictVoice: () async => voice.unloadEngine(),
  );
  voice = VoiceService(prefs: voicePrefs, mlGuard: mlGuard);
  unawaited(voice.bootstrap());

  // Mode panique (v0.7) — orchestrateur d'effacement irréversible. Tous
  // les services qu'il a besoin de wiper lui sont injectés explicitement
  // pour rester testable. Pas de Singleton magique : un test peut créer
  // un PanicService avec des fakes.
  //
  // `beforeDbWipe` ferme proprement les background workers (Embedder
  // Coordinator, Indexing, Backlinks) AVANT que la DB soit écrasée :
  // sans ça, une écriture en vol via `notesRepo.changes` pourrait
  // tomber sur une DB fermée et lever une exception cosmétique.

  // Coordinateur d'embedder : observe le toggle settings et swap à chaud.
  // Démarré AVANT `indexing.start()` pour qu'un toggle MiniLM=ON déjà
  // persisté soit honoré dès la première passe d'indexation, sans
  // qu'une passe locale soit lancée puis tuée par le swap (B4).
  final coordinator = EmbedderCoordinator(
    settings: settings,
    indexing: indexing,
    semantic: semantic,
    activeEmbedder: activeEmbedder,
    localEmbedder: localEmbedder,
  )..start();

  // L'indexation et l'indexation des liens démarrent ensuite, sans
  // bloquer le 1er frame.
  unawaited(indexing.start());
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
    gemma: gemma,
    vault: vault,
    database: AppDatabase.instance,
    secureWindow: secureWindow,
    prefs: voicePrefs,
    lockAllFolders: () async => folderVault.lockAll(),
    beforeDbWipe: () async {
      // Ordre : coordinator d'abord (libère les listeners de settings),
      // puis indexing (annule le throttle pending), puis backlinks
      // (ferme son StreamSubscription sur notesRepo.changes).
      // Toutes les méthodes retournent Future<void>, on les attend en
      // séquence : on veut que les services soient EFFECTIVEMENT
      // arrêtés avant que `db.wipe()` ne ferme la base.
      //
      // P3-2 : timeout par dispose pour ne JAMAIS bloquer la séquence
      // panique. 2 s c'est large pour un dispose normal (ms) ; au-delà,
      // on assume qu'un service est bloqué et on continue le wipe — le
      // mode panique doit aller au bout coûte que coûte.
      const timeout = Duration(seconds: 2);
      await coordinator
          .dispose()
          .timeout(timeout, onTimeout: () {});
      await indexing
          .dispose()
          .timeout(timeout, onTimeout: () {});
      await backlinks
          .dispose()
          .timeout(timeout, onTimeout: () {});
    },
  );

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider<SettingsService>.value(value: settings),
        Provider<VaultService>.value(value: vault),
        Provider<SecureWindowService>.value(value: secureWindow),
        Provider<NotesRepository>.value(value: notesRepo),
        Provider<FoldersRepository>.value(value: foldersRepo),
        Provider<EmbeddingsRepository>.value(value: embeddingsRepo),
        Provider<LinksRepository>(
          create: (_) => linksRepo,
          dispose: (_, r) => r.dispose(),
        ),
        ChangeNotifierProvider<ValueNotifier<EmbeddingProvider>>.value(
          value: activeEmbedder,
        ),
        Provider<IndexingService>(
          create: (_) => indexing,
          dispose: (_, s) => s.dispose(),
        ),
        Provider<SemanticSearchService>(
          create: (_) => semantic,
          dispose: (_, s) => s.dispose(),
        ),
        Provider<GemmaService>(
          create: (_) => gemma,
          dispose: (_, s) => s.dispose(),
        ),
        Provider<BacklinksService>(
          create: (_) => backlinks,
          dispose: (_, s) => s.dispose(),
        ),
        Provider<EmbedderCoordinator>(
          create: (_) => coordinator,
          dispose: (_, c) => c.dispose(),
        ),
        ChangeNotifierProvider<VoiceService>.value(value: voice),
        ChangeNotifierProvider<FolderVaultService>.value(value: folderVault),
        Provider<MlMemoryGuard>.value(value: mlGuard),
        Provider<PanicService>.value(value: panic),
        // Variante nullable pour les call sites optionnels (`context.read<
        // MlMemoryGuard?>()?.requestGemma()` dans ai_chat_screen) — Provider
        // résout le type non-nullable mais le call site accepte un null
        // graceful si la valeur n'est pas dispo (test).
        Provider<MlMemoryGuard?>.value(value: mlGuard),
      ],
      child: const NotesTechApp(),
    ),
  );
}

