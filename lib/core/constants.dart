/// Constantes globales de l'application.
library;

class AppConstants {
  AppConstants._();

  static const String appName = 'Notes Tech';
  static const String appVersion = '0.9.13';
  // NB : la clé Kotlin équivalente côté `MainActivity.kt` est
  // `flutter.secure_window_enabled` (préfixe `flutter.` ajouté
  // automatiquement par `shared_preferences` au moment de la persistance).
  static const String appAuthor = 'Patrice Haltaya';
  static const String githubUrl = 'https://github.com/gitubpatrice/notes_tech';

  // Base de données
  static const String dbFileName = 'notes_tech.db';
  // v4 (2026-05-06) : ajout colonnes vault sur `folders` (vault_salt,
  // vault_kek_wrapped, vault_iv, vault_verifier) et `encrypted_content`
  // BLOB nullable sur `notes` pour les notes verrouillées (contenu
  // chiffré AES-256-GCM avec folder_kek dérivée Argon2id).
  // v5 (2026-05-06) : mode PIN par coffre. Colonnes `vault_mode` (TEXT
  // 'passphrase'|'pin'|NULL), `vault_pin_blob`+`vault_pin_iv` (wrap
  // Keystore-bound de la folder_kek pour mode PIN), `vault_attempts`
  // (compteur tentatives PIN, auto-wipe à 5).
  static const int dbVersion = 5;

  /// Identifiant du dossier "Boîte de réception" — racine indélébile de
  /// l'arborescence, créée au premier démarrage. Les notes orphelines
  /// (dossier supprimé) y sont automatiquement réassignées.
  /// Source unique de vérité pour éviter les littéraux 'inbox' dispersés.
  static const String inboxFolderId = 'inbox';

  /// Sentinel utilisé par les widgets de filtrage pour signaler "aucun
  /// filtre dossier" (= toutes les notes). Distinct d'un id de dossier
  /// réel, ne doit jamais atteindre la couche DB.
  static const String allFoldersSentinel = '__all_folders__';

  // ─── v0.8 — Vault par dossier ────────────────────────────────────────

  /// Longueur minimale d'une passphrase de coffre. 8 caractères = seuil
  /// pragmatique : suffit à arrêter une recherche par-dessus l'épaule
  /// + force-bruteforce hors-ligne devient irréaliste vu le coût Argon2id
  /// (m=64MB, t=3) — environ 0.5 s par essai sur un GPU haut de gamme.
  /// L'utilisateur reste libre d'aller plus long.
  static const int vaultPassphraseMinLength = 8;

  /// Auto-verrouillage par défaut d'un coffre déverrouillé : 15 minutes
  /// d'inactivité. Configurable dans Réglages → Sécurité.
  static const Duration vaultDefaultAutoLock = Duration(minutes: 15);

  /// Paramètres Argon2id RFC 9106 — calibrés pour un compromis sécurité
  /// vs UX sur S9 (Snapdragon 845, 2018) : ~1-2 s par dérivation, soit
  /// la latence acceptable au tap "Déverrouiller". Sur S24 FE c'est
  /// instant. Plus haut = meilleure résistance bruteforce, moins
  /// confortable.
  static const int vaultArgon2Iterations = 3;
  static const int vaultArgon2MemoryKb = 64 * 1024; // 64 Mo
  static const int vaultArgon2Parallelism = 1;
  static const int vaultArgon2HashBytes = 32;

  /// Taille des sels CSPRNG persistés par coffre.
  static const int vaultSaltBytes = 16;

  // ─── v0.9 — Mode PIN par coffre ──────────────────────────────────────

  /// Longueur MIN d'un PIN coffre. 4 chiffres = 10 000 combinaisons,
  /// largement insuffisant en bruteforce nu — c'est le device-binding
  /// Keystore qui donne la sécurité réelle (impossible à attaquer
  /// hors-device) + l'auto-wipe à [vaultPinMaxAttempts] tentatives.
  static const int vaultPinMinLength = 4;

  /// Longueur MAX d'un PIN coffre. 6 chiffres = format usuel des
  /// écrans de verrouillage Android — au-delà l'utilisateur préférera
  /// passer en passphrase complète.
  static const int vaultPinMaxLength = 6;

  /// Tentatives PIN avant **auto-wipe** définitif du coffre (suppression
  /// des colonnes `vault_pin_blob`/`vault_pin_iv` + clé Keystore).
  /// Aligné sur le comportement écran de verrouillage Android : 5 fails
  /// → factory reset équivalent (ici : coffre irrécupérable). Les notes
  /// chiffrées restent en DB mais deviennent illisibles à jamais.
  static const int vaultPinMaxAttempts = 5;

  /// Paramètres Argon2id **allégés** pour le mode PIN. Ne servent que
  /// de seconde couche : la première ligne de défense est le scellage
  /// Keystore (device-bound). Pas la peine d'imposer 1 s par essai au
  /// déverrouillage légitime — c'est le rate-limit applicatif qui
  /// protège du bruteforce on-device.
  static const int vaultPinArgon2Iterations = 2;
  static const int vaultPinArgon2MemoryKb = 32 * 1024; // 32 Mo

  /// Préfixe des alias Keystore pour les coffres PIN. Concaténé avec
  /// `folder_id` pour unicité par coffre.
  static const String vaultPinKeystoreAliasPrefix = 'vault_pin_';

  /// Préfixe des clés SharedPreferences signalant un auto-wipe en cours
  /// pour un coffre PIN. Concaténé avec `folder_id`. Permet la reprise
  /// après crash : si le flag existe au démarrage suivant, le wipe avait
  /// été interrompu → relancé pour finir proprement.
  static const String prefKeyVaultWipePendingPrefix = 'vault_wipe_pending_';

  /// Clé SharedPreferences pour le timeout d'auto-lock (en minutes).
  /// Valeurs spéciales : `0` = jamais, `-1` = au pause de l'app uniquement.
  static const String prefKeyVaultAutoLockMinutes = 'vault_auto_lock_minutes';

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
