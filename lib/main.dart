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

  // L'indexation locale démarre tout de suite (sans bloquer le 1er frame).
  unawaited(indexing.start());

  // Coordinateur d'embedder : observe le toggle settings et swap à chaud.
  final coordinator = _EmbedderCoordinator(
    settings: settings,
    indexing: indexing,
    semantic: semantic,
    activeEmbedder: activeEmbedder,
    localEmbedder: localEmbedder,
  )..startListening();

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
        Provider<_EmbedderCoordinator>(
          create: (_) => coordinator,
          dispose: (_, c) => c.dispose(),
        ),
      ],
      child: const NotesTechApp(),
    ),
  );
}

/// Réagit aux changements du toggle "Recherche sémantique avancée"
/// dans Settings et bascule l'embedder à chaud.
///
/// - OFF → LocalEmbedder (par défaut)
/// - ON  → tente MiniLM en arrière-plan ; si succès, swap.
class _EmbedderCoordinator {
  _EmbedderCoordinator({
    required SettingsService settings,
    required IndexingService indexing,
    required SemanticSearchService semantic,
    required ValueNotifier<EmbeddingProvider> activeEmbedder,
    required LocalEmbedder localEmbedder,
  })  : _settings = settings,
        _indexing = indexing,
        _semantic = semantic,
        _active = activeEmbedder,
        _local = localEmbedder;

  final SettingsService _settings;
  final IndexingService _indexing;
  final SemanticSearchService _semantic;
  final ValueNotifier<EmbeddingProvider> _active;
  final LocalEmbedder _local;

  MiniLmEmbedder? _miniLm;
  bool _busy = false;
  bool _lastEnabled = false;

  void startListening() {
    _lastEnabled = _settings.semanticSearchEnabled;
    _settings.addListener(_onSettingsChanged);
    if (_lastEnabled) unawaited(_upgrade());
  }

  Future<void> dispose() async {
    _settings.removeListener(_onSettingsChanged);
    await _miniLm?.dispose();
    _miniLm = null;
  }

  void _onSettingsChanged() {
    final enabled = _settings.semanticSearchEnabled;
    if (enabled == _lastEnabled) return;
    _lastEnabled = enabled;
    if (enabled) {
      unawaited(_upgrade());
    } else {
      unawaited(_downgrade());
    }
  }

  Future<void> _upgrade() async {
    if (_busy) return;
    _busy = true;
    try {
      final available = await MiniLmEmbedder.assetsAvailable();
      if (!available) {
        if (kDebugMode) debugPrint('MiniLM assets absents, upgrade ignoré.');
        return;
      }
      final m = _miniLm ?? MiniLmEmbedder();
      await m.warmUp();
      _miniLm = m;
      _semantic.setEmbedder(m);
      await _indexing.swapEmbedder(m);
      _active.value = m;
    } catch (e, st) {
      if (kDebugMode) {
        debugPrint('Upgrade MiniLM échoué : $e\n$st');
      }
    } finally {
      _busy = false;
    }
  }

  Future<void> _downgrade() async {
    if (_busy) return;
    _busy = true;
    try {
      _semantic.setEmbedder(_local);
      await _indexing.swapEmbedder(_local);
      _active.value = _local;
      // On garde le `_miniLm` chargé en RAM si l'utilisateur réactive vite.
      // Le dispose se fera à la fin du process.
    } catch (e, st) {
      if (kDebugMode) {
        debugPrint('Downgrade vers Local échoué : $e\n$st');
      }
    } finally {
      _busy = false;
    }
  }
}
