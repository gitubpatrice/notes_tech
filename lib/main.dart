/// Point d'entrée — initialisation parallèle puis injection de dépendances.
///
/// Stratégie de démarrage :
///   1. Bootstrap minimal (settings, DB) → runApp avec LocalEmbedder.
///   2. Détection MiniLM en arrière-plan ; si dispo, warmUp puis swap
///      à chaud côté IndexingService + SemanticSearchService.
/// Ainsi le 1er frame n'attend jamais le chargement ONNX (~1-2 s).
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
import 'services/ai/gemma_service.dart';
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

  // Initialisations bloquantes parallèles (toutes < 100 ms).
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

  // L'indexation locale démarre tout de suite (sans bloquer le 1er frame).
  unawaited(indexing.start());

  // Service IA — singleton paresseux, ne charge le modèle qu'à la demande
  // depuis l'écran de chat.
  final gemma = GemmaService();

  // Tente de basculer sur MiniLM en arrière-plan.
  unawaited(_tryUpgradeToMiniLm(
    indexing: indexing,
    semantic: semantic,
    activeEmbedder: activeEmbedder,
  ));

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider<SettingsService>.value(value: settings),
        Provider<NotesRepository>.value(value: notesRepo),
        Provider<FoldersRepository>.value(value: foldersRepo),
        Provider<EmbeddingsRepository>.value(value: embeddingsRepo),
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
      ],
      child: const NotesTechApp(),
    ),
  );
}

/// Charge MiniLM en background si les assets sont présents et le warmUp OK.
/// En cas d'échec, on reste sur LocalEmbedder sans bruit.
Future<void> _tryUpgradeToMiniLm({
  required IndexingService indexing,
  required SemanticSearchService semantic,
  required ValueNotifier<EmbeddingProvider> activeEmbedder,
}) async {
  try {
    final available = await MiniLmEmbedder.assetsAvailable();
    if (!available) return;
    final m = MiniLmEmbedder();
    await m.warmUp();
    semantic.setEmbedder(m);
    await indexing.swapEmbedder(m);
    activeEmbedder.value = m;
  } catch (e, st) {
    if (kDebugMode) {
      debugPrint('MiniLM indisponible, on reste sur LocalEmbedder : $e\n$st');
    }
  }
}
