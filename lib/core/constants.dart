/// Constantes globales de l'application.
library;

class AppConstants {
  AppConstants._();

  static const String appName = 'Notes Tech';
  static const String appVersion = '2.0.2';
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
  // v6 (1.0.3) : F2 — triggers FTS5 réécrits pour ne plus indexer
  // `title`/`tags` sur notes verrouillées (`encrypted_content IS NOT NULL`).
  // v7 (2.0.0) : `notes.enc_v` — version du format de `encrypted_content`
  // (1 = contenu seul, 2 = titre + contenu).
  // v8 (2.0.0) : DROP `note_embeddings` — retrait de la recherche
  // sémantique. Ces vecteurs étaient dérivés du texte EN CLAIR des notes.
  static const int dbVersion = 9;

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

  // Limites métier
  static const int noteTitleMaxLength = 200;
  static const int searchResultsLimit = 100;
  static const int recentNotesLimit = 50;
  static const int trashRetentionDays = 30;

  /// v1.1.4 — cap du contenu scanné, hérité du split de
  /// `noteContentIndexLimit = 200000`. Le volet « encodeur sémantique » a
  /// disparu avec le retrait de l'IA ; seul le cap backlinks subsiste.
  ///
  /// Borne haute du contenu parsé par `BacklinksService` pour extraire
  /// les wikilinks `[[note]]`. La regex est déjà capée à 200 chars par
  /// match, mais on plafonne aussi le scan total pour ne pas walker des
  /// notes énormes à chaque édition (debounce 500 ms). 50 ko ≈ 15 000
  /// mots, bien au-delà d'une note utile manuelle.
  static const int noteContentBacklinksLimit = 50000; // ~50 ko

  /// @Deprecated v1.1.4 — gardé pour rétrocompatibilité d'éventuels
  /// callers externes. À retirer en v1.2.0.
  @Deprecated('Use noteContentBacklinksLimit')
  static const int noteContentIndexLimit = noteContentBacklinksLimit;

  // Durées UI
  static const Duration searchDebounce = Duration(milliseconds: 200);
  static const Duration autosaveDebounce = Duration(milliseconds: 500);

  // Préférences (clés SharedPreferences)
  static const String prefKeyThemeMode = 'theme_mode';
  static const String prefKeySortMode = 'note_sort_mode';
  static const String prefKeySecureWindowEnabled = 'secure_window_enabled';

  /// `true` une fois la migration vers la base SQLite chiffrée terminée.
  /// Absent / `false` ⇒ la prochaine ouverture déclenche la migration
  /// d'une éventuelle DB en clair vers une DB chiffrée par la KEK du vault.
  static const String prefKeyDbEncryptedV1 = 'db_encrypted_v1';

  /// Purge unique des modèles orphelins laissés par l'IA embarquée (v2.0.0).
  /// Voir `_purgeOrphanModelsOnce` dans `main.dart`.
  static const String prefKeyOrphanModelsPurged = 'orphan_models_purged_v1';

  /// Locale forcée par l'utilisateur (`fr` / `en`) ou `system` pour suivre
  /// la locale du téléphone. v1.0 — i18n FR/EN.
  static const String prefKeyLocale = 'app_locale';

  /// F11 v1.1.0 — Set d'ids de notes vault dont la dernière modification
  /// a été perdue parce que le coffre s'est verrouillé entre l'édition et
  /// le flush final (dispose de l'écran après auto-lock). Lu au boot par
  /// `HomeScreen` pour afficher une bannière informative.
  static const String prefKeyVaultLostDrafts = 'vault_lost_drafts';
}
