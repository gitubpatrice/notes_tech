/// Point d'entrée — initialisation parallèle puis injection de dépendances.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:provider/provider.dart';
import 'package:sqflite/sqflite.dart' show Database;

import 'app.dart';
import 'data/db/database.dart';
import 'data/db/embeddings_dao.dart';
import 'data/db/folders_dao.dart';
import 'data/db/notes_dao.dart';
import 'data/repositories/embeddings_repository.dart';
import 'data/repositories/folders_repository.dart';
import 'data/repositories/notes_repository.dart';
import 'services/embedding/embedding_provider.dart';
import 'services/embedding/local_embedder.dart';
import 'services/embedding/minilm_embedder.dart';
import 'services/indexing_service.dart';
import 'services/semantic_search_service.dart';
import 'services/settings_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations(<DeviceOrientation>[
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

  // Initialisations indépendantes lancées en parallèle.
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

  // Encodeur : MiniLM si modèle ONNX présent, sinon repli sur LocalEmbedder.
  final embedder = await _selectEmbedder();

  final indexing = IndexingService(
    notes: notesRepo,
    embeddings: embeddingsRepo,
    embedder: embedder,
  );
  final semantic = SemanticSearchService(
    notes: notesRepo,
    embeddings: embeddingsRepo,
    embedder: embedder,
  );

  // Démarrage de l'indexation après runApp pour ne pas retarder le 1er frame.
  unawaited(indexing.start());

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider<SettingsService>.value(value: settings),
        Provider<NotesRepository>.value(value: notesRepo),
        Provider<FoldersRepository>.value(value: foldersRepo),
        Provider<EmbeddingsRepository>.value(value: embeddingsRepo),
        Provider<EmbeddingProvider>.value(value: embedder),
        Provider<IndexingService>.value(value: indexing),
        Provider<SemanticSearchService>.value(value: semantic),
      ],
      child: const NotesTechApp(),
    ),
  );
}

/// Sélection de l'encodeur :
/// 1. tente MiniLM si ses assets sont bundlés et que le warmUp réussit
/// 2. sinon repli silencieux sur LocalEmbedder (toujours disponible)
///
/// Toute exception MiniLM est capturée — l'app doit toujours démarrer.
Future<EmbeddingProvider> _selectEmbedder() async {
  try {
    final available = await MiniLmEmbedder.assetsAvailable();
    if (!available) return const LocalEmbedder();
    final m = MiniLmEmbedder();
    await m.warmUp();
    return m;
  } catch (e, st) {
    if (kDebugMode) {
      debugPrint('MiniLM indisponible, repli LocalEmbedder : $e\n$st');
    }
    return const LocalEmbedder();
  }
}
