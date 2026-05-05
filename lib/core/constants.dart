/// Constantes globales de l'application.
library;

class AppConstants {
  AppConstants._();

  static const String appName = 'Notes Tech';
  static const String appVersion = '0.2.2';
  static const String appAuthor = 'Patrice Haltaya';
  static const String githubUrl = 'https://github.com/gitubpatrice/notes_tech';

  // Base de données
  static const String dbFileName = 'notes_tech.db';
  static const int dbVersion = 2;

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

  // Préférences (clés SharedPreferences)
  static const String prefKeyThemeMode = 'theme_mode';
  static const String prefKeySortMode = 'note_sort_mode';
}
