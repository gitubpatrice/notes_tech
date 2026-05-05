/// Constantes globales de l'application.
library;

class AppConstants {
  AppConstants._();

  static const String appName = 'Notes Tech';
  static const String appVersion = '0.5.0';
  // NB : la clé Kotlin équivalente côté `MainActivity.kt` est
  // `flutter.secure_window_enabled` (préfixe `flutter.` ajouté
  // automatiquement par `shared_preferences` au moment de la persistance).
  static const String appAuthor = 'Patrice Haltaya';
  static const String githubUrl = 'https://github.com/gitubpatrice/notes_tech';

  // Base de données
  static const String dbFileName = 'notes_tech.db';
  static const int dbVersion = 3;

  /// SHA-256 du modèle Gemma 3 1B int4 officiel (gemma3-1b-it-int4.task,
  /// 554 661 243 octets, publié sur Kaggle/HuggingFace).
  /// Vérifié à l'import pour garantir l'intégrité du modèle.
  /// Si l'utilisateur souhaite importer une variante différente, il doit
  /// activer le toggle `acceptUnknownGemmaHash` dans les réglages avancés.
  static const String gemmaModelSha256 =
      'e3d981c01aeaaac69a84ffa0d4be13281b3176731063f1bea1c9fe6887bd9dee';

  // Recherche sémantique (la dim et le modelId réels sont portés par
  // l'EmbeddingProvider actif — voir LocalEmbedder / MiniLmEmbedder).
  static const int semanticSearchLimit = 50;

  // Limites métier
  static const int noteTitleMaxLength = 200;
  static const int searchResultsLimit = 100;
  static const int recentNotesLimit = 50;
  static const int trashRetentionDays = 30;

  /// Borne haute (en caractères) du contenu d'une note transmis à
  /// l'encodeur sémantique. Au-delà, on tronque pour éviter les coûts
  /// catastrophiques (DoS via collage massif). La note elle-même n'est
  /// pas tronquée — seul l'embedding voit cette version raccourcie.
  static const int noteContentIndexLimit = 200000; // ≈ 200 ko de texte

  // Durées UI
  static const Duration searchDebounce = Duration(milliseconds: 200);
  static const Duration autosaveDebounce = Duration(milliseconds: 500);

  // Throttle d'indexation : laisse l'UI respirer entre chaque encodage.
  // Calibré pour Samsung S24 (CPU MiniLM ~30-60 ms par note).
  static const Duration indexingDelayLocal = Duration.zero;
  static const Duration indexingDelayMiniLm = Duration(milliseconds: 80);

  // Préférences (clés SharedPreferences)
  static const String prefKeyThemeMode = 'theme_mode';
  static const String prefKeySortMode = 'note_sort_mode';
  static const String prefKeySemanticSearchEnabled = 'semantic_search_enabled';
  static const String prefKeySecureWindowEnabled = 'secure_window_enabled';
  static const String prefKeyAcceptUnknownGemmaHash =
      'accept_unknown_gemma_hash';
  /// `true` une fois la migration vers la base SQLite chiffrée terminée.
  /// Absent / `false` ⇒ la prochaine ouverture déclenche la migration
  /// d'une éventuelle DB en clair vers une DB chiffrée par la KEK du vault.
  static const String prefKeyDbEncryptedV1 = 'db_encrypted_v1';
}
