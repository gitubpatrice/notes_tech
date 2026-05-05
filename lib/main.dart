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
import 'services/semantic_search_service.dart';
import 'services/settings_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations(<DeviceOrientation>[
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

  // Bootstraps en parallèle (~50-100 ms cumulés).
  final dateInit = initializeDateFormatting('fr_FR');
  final settingsInit = SettingsService.create();
  final dbInit = AppDatabase.instance.db;
  await dateInit;
  final settings = await settingsInit;
  final Database db = await dbInit;

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

  // L'indexation locale démarre tout de suite (sans bloquer le 1er frame).
  unawaited(indexing.start());
  unawaited(backlinks.start());

  // Coordinateur d'embedder : observe le toggle settings et swap à chaud.
  final coordinator = EmbedderCoordinator(
    settings: settings,
    indexing: indexing,
    semantic: semantic,
    activeEmbedder: activeEmbedder,
    localEmbedder: localEmbedder,
  )..start();

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider<SettingsService>.value(value: settings),
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
      ],
      child: const NotesTechApp(),
    ),
  );
}

