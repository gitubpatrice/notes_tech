/// Coordinateur d'embedder : observe le toggle "Recherche sémantique
/// avancée" dans Settings et bascule l'embedder à chaud.
///
/// Garanties :
/// - **Convergence** : un toggle rapide ON/OFF/ON ne perd jamais
///   d'intention — la boucle `_converge` réapplique tant que l'état
///   actif diffère de la pref.
/// - **Feedback erreur** : `lastError` (ValueNotifier) expose la dernière
///   raison d'échec à l'UI (Settings).
/// - **Fail-fast pref** : si MiniLM est indisponible (assets absents ou
///   warmUp KO), la pref est repassée à `false` pour refléter la réalité.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

import 'embedding/embedding_provider.dart';
import 'embedding/local_embedder.dart';
import 'embedding/minilm_embedder.dart';
import 'indexing_service.dart';
import 'semantic_search_service.dart';
import 'settings_service.dart';

class EmbedderCoordinator {
  EmbedderCoordinator({
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

  /// Dernière erreur d'upgrade/downgrade (null si tout va bien).
  /// Consommé par l'écran Settings pour afficher un message clair.
  final ValueNotifier<String?> lastError = ValueNotifier<String?>(null);

  MiniLmEmbedder? _miniLm;
  bool _converging = false;

  void start() {
    _settings.addListener(_onSettingsChanged);
    unawaited(_converge());
  }

  Future<void> dispose() async {
    _settings.removeListener(_onSettingsChanged);
    await _miniLm?.dispose();
    _miniLm = null;
    lastError.dispose();
  }

  void _onSettingsChanged() => unawaited(_converge());

  Future<void> _converge() async {
    if (_converging) return;
    _converging = true;
    try {
      // Réapplique tant que l'état actif diffère de la pref cible.
      while (true) {
        final target = _settings.semanticSearchEnabled;
        final isMiniLm = _active.value is MiniLmEmbedder;
        if (target == isMiniLm) break;
        if (target) {
          await _upgrade();
        } else {
          await _downgrade();
        }
      }
    } finally {
      _converging = false;
    }
  }

  Future<void> _upgrade() async {
    final available = await MiniLmEmbedder.assetsAvailable();
    if (!available) {
      lastError.value = 'Modèle MiniLM absent de l\'APK.';
      await _settings.setSemanticSearchEnabled(false);
      return;
    }
    try {
      final m = _miniLm ?? MiniLmEmbedder();
      await m.warmUp();
      _miniLm = m;
      _semantic.setEmbedder(m);
      await _indexing.swapEmbedder(m);
      _active.value = m;
      lastError.value = null;
    } catch (e, st) {
      if (kDebugMode) debugPrint('Upgrade MiniLM échoué : $e\n$st');
      lastError.value = 'Chargement du modèle sémantique échoué.';
      await _settings.setSemanticSearchEnabled(false);
    }
  }

  Future<void> _downgrade() async {
    try {
      _semantic.setEmbedder(_local);
      await _indexing.swapEmbedder(_local);
      _active.value = _local;
      lastError.value = null;
    } catch (e, st) {
      if (kDebugMode) debugPrint('Downgrade vers Local échoué : $e\n$st');
      lastError.value = 'Bascule vers le mode léger échouée.';
    }
  }
}
