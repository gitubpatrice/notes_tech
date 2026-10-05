# Schéma hérité — référence vérifiée

> **Ce fichier est la source de vérité du portage.** Toutes les valeurs ci-dessous ont été relevées
> dans le code Flutter publié en **2.0.3 (versionCode 51)**, commit `7180a2c`, le 2026-08-13.
> Aucune n'est déduite ou reconstituée de mémoire.
>
> ⚠️ Une seule valeur fausse ici = base illisible ou coffre inouvrable chez l'utilisateur.
> Toute correction doit citer le fichier et la ligne d'origine.

---

## 1. Emplacement du fichier de base

Côté Flutter, `database.dart:75` ouvre la base dans `getApplicationDocumentsDirectory()`,
**pas** dans le répertoire `databases/` standard d'Android.

Sur Android, `path_provider.getApplicationDocumentsDirectory()` renvoie
`context.getDir("flutter", Context.MODE_PRIVATE)`, soit :

```
/data/user/0/com.filestech.notes_tech/app_flutter/notes_tech.db
```

Côté Kotlin on obtient le **même** répertoire sans chemin en dur :

```kotlin
File(context.getDir("flutter", Context.MODE_PRIVATE), "notes_tech.db")
```

Nom de fichier : `notes_tech.db` — `core/constants.dart:16`.

> **La base n'est pas déplacée** vers `databases/`. Cf. décision D-003.

## 2. Paramètres d'ouverture SQLCipher

| Paramètre | Valeur | Source |
|---|---|---|
| Format de clé | `x'<64 caractères hex>'` (**clé brute**, pas de KDF) | `database.dart:463` |
| `cipher_compatibility` | `4` | `database.dart:474` |
| `foreign_keys` | `ON` | `database.dart:479` |
| `journal_mode` | `WAL` | `database.dart:482` |
| `synchronous` | `NORMAL` | `database.dart:491` |
| `temp_store` | `MEMORY` | `database.dart:492` |
| `cache_size` | `-32000` (32 Mo) | `database.dart:497` |
| `mmap_size` | **délibérément absent** — retiré en v1.0.7.1 (hang au démarrage à froid sur certaines builds natives) | `database.dart:498-501` |

### Le fait décisif du portage

`sqflite_sqlcipher 3.4.0` embarque **`net.zetetic:sqlcipher-android:4.10.0`**
(`android/build.gradle:44`) et transmet le mot de passe en `String` à
`SQLiteDatabase.openDatabase(path, password, …)` (`Database.java:62`).

`agenda_tech` utilise **le même artefact**, en 4.16.0. Même moteur, même famille.

SQLCipher reconnaît le motif `x'…'` **dans le matériel de clé lui-même**, quelle que soit la voie
d'arrivée (`PRAGMA key` ou `sqlite3_key`) : longueur `keySize*2 + 3` = 67, préfixe `x'`, corps
hexadécimal. Il l'utilise alors comme **clé brute** et ne dérive rien.

⇒ Côté Kotlin, il faut passer à `SupportOpenHelperFactory` les **67 octets ASCII** de la chaîne
`x'<hex>'` — surtout pas les 32 octets bruts de la KEK, qui seraient traités comme une passphrase
et passés à PBKDF2. Cf. décision D-004.

## 3. Tables

Version de schéma : **9** (`core/constants.dart:31`).

### `folders`

```sql
CREATE TABLE folders (
  id                  TEXT PRIMARY KEY NOT NULL,
  name                TEXT NOT NULL,
  parent_id           TEXT,
  color               INTEGER,
  icon                TEXT,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  vault_salt          BLOB,
  vault_kek_wrapped   BLOB,
  vault_iv            BLOB,
  vault_verifier      BLOB,
  vault_mode          TEXT,
  vault_pin_blob      BLOB,
  vault_pin_iv        BLOB,
  vault_attempts      INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE SET NULL
);
CREATE INDEX idx_folders_parent ON folders(parent_id);
```

`vault_mode` ∈ {`'passphrase'`, `'pin'`, `NULL`}. `NULL` sur toutes les colonnes `vault_*`
signifie « ce dossier n'est pas un coffre ».

Le dossier racine `inbox` est **indélébile** et recréé à chaque ouverture par un
`INSERT OR IGNORE` (`database.dart:864`). Son libellé d'origine est `'Boîte de réception'`,
en dur dans la base — ce n'est pas une chaîne localisée.

### `notes`

```sql
CREATE TABLE notes (
  id                  TEXT PRIMARY KEY NOT NULL,
  title               TEXT NOT NULL,
  content             TEXT NOT NULL,
  encrypted_content   BLOB,
  folder_id           TEXT NOT NULL,
  tags                TEXT NOT NULL DEFAULT '',
  pinned              INTEGER NOT NULL DEFAULT 0,
  favorite            INTEGER NOT NULL DEFAULT 0,
  archived            INTEGER NOT NULL DEFAULT 0,
  trashed_at          INTEGER,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  enc_v               INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE
);
CREATE INDEX idx_notes_folder_active ON notes(folder_id, archived, trashed_at, updated_at DESC);
CREATE INDEX idx_notes_trashed ON notes(trashed_at);
CREATE INDEX idx_notes_updated ON notes(updated_at);
```

`encrypted_content` non-NULL = **note verrouillée dans un coffre**. `content` est alors vide en
clair. `enc_v` donne le format du blob :

| `enc_v` | Contenu du blob |
|---|---|
| `1` | contenu seul — le titre reste en clair dans `title` |
| `2` | titre **et** contenu — `title` est vidée |

### `note_links`

```sql
CREATE TABLE note_links (
  source_id          TEXT NOT NULL,
  target_id          TEXT,
  target_title       TEXT NOT NULL,
  target_title_norm  TEXT NOT NULL,
  position           INTEGER NOT NULL,
  FOREIGN KEY (source_id) REFERENCES notes(id) ON DELETE CASCADE,
  FOREIGN KEY (target_id) REFERENCES notes(id) ON DELETE SET NULL
);
CREATE INDEX idx_links_source ON note_links(source_id);
CREATE INDEX idx_links_target ON note_links(target_id);
CREATE INDEX idx_links_target_norm ON note_links(target_title_norm);
```

Pas de clé primaire — c'est une table de faits, réécrite en bloc pour une note donnée.
`target_id` `NULL` = lien fantôme vers un titre qui n'existe pas encore.

### `notes_fts` — index plein texte

```sql
CREATE VIRTUAL TABLE notes_fts USING fts5(
  title, content, tags,
  content='notes',
  content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2'
);
```

Table **externe-contenu** indexée sur le `rowid` de `notes`. Maintenue par trois triggers
(`notes_ai`, `notes_ad`, `notes_au`) qui masquent `title`/`content`/`tags` par
`CASE WHEN encrypted_content IS NOT NULL THEN '' ELSE … END`.

> Ce masquage n'est pas cosmétique : avant la v1.0.3, seul `content` était vidé au verrouillage,
> et une recherche sur le **titre** ressortait quand même une note de coffre.

⚠️ **Conséquence directe sur les DAO** : le couple `content='notes'` + `content_rowid='rowid'`
rend l'index dépendant du `rowid`. Tout ce qui change un `rowid` casse l'index **et** déclenche
les cascades de clés étrangères. Cf. [04-PIEGES.md](04-PIEGES.md) §1.

## 4. Paramètres cryptographiques des coffres

Source : `core/constants.dart:62-100`, `services/security/folder_vault_service.dart:15-21`.

### Coffre passphrase

| Paramètre | Valeur |
|---|---|
| KDF | Argon2id (RFC 9106) |
| Itérations (`t`) | **3** |
| Mémoire (`m`) | **65 536 Kio** (64 Mo) |
| Parallélisme (`p`) | **1** |
| Longueur de sortie | **32** octets |
| Sel | **16** octets, persisté dans `folders.vault_salt` |
| Longueur min. passphrase | 8 caractères |

### Coffre PIN

| Paramètre | Valeur |
|---|---|
| Itérations (`t`) | **2** |
| Mémoire (`m`) | **32 768 Kio** (32 Mo) |
| Parallélisme, sortie, sel | identiques au mode passphrase |
| Longueur PIN | 4 à 6 chiffres |
| Tentatives avant auto-effacement | **5** |
| Alias Keystore | `vault_pin_<folder_id>` |

La sécurité réelle du mode PIN vient du **scellage Keystore lié à l'appareil**, pas de l'entropie
du PIN. Argon2id allégé n'y est qu'une seconde couche.

### Enveloppe de chiffrement des notes

```
nonce (12 octets) || ciphertext || tag GCM (16 octets)
```

AES-256-GCM. `folder_vault_service.dart:21`.

### Clés Keystore des coffres PIN

`KeystoreBridge.kt` du projet Flutter est repris tel quel (moins la couche MethodChannel) :

- AES-256-GCM, un alias par coffre, `setUserAuthenticationRequired(false)`
- `setRandomizedEncryptionRequired(true)` — IV généré par le Keystore
- `setUnlockedDeviceRequired(true)` à partir de l'API 28
- StrongBox tenté d'abord, repli TEE silencieux
- **Validation `isInsideSecureHardware`** : une clé qui retombe en logiciel est supprimée et
  l'erreur `KEYSTORE_SOFTWARE_ONLY` remonte, pour que l'interface propose un coffre passphrase

## 5. Préférences persistées

Côté Flutter, `shared_preferences` préfixe **automatiquement** ses clés par `flutter.`.
Les clés réellement présentes dans le fichier `SharedPreferences` sont donc :

| Clé logique (`constants.dart`) | Clé réelle sur disque |
|---|---|
| `theme_mode` | `flutter.theme_mode` |
| `note_sort_mode` | `flutter.note_sort_mode` |
| `secure_window_enabled` | `flutter.secure_window_enabled` |
| `app_locale` | `flutter.app_locale` |
| `vault_auto_lock_minutes` | `flutter.vault_auto_lock_minutes` |
| `vault_lost_drafts` | `flutter.vault_lost_drafts` |
| `vault_wipe_pending_<folder_id>` | `flutter.vault_wipe_pending_<folder_id>` |
| `db_encrypted_v1` | `flutter.db_encrypted_v1` |
| `orphan_models_purged_v1` | `flutter.orphan_models_purged_v1` |

⚠️ `vault_wipe_pending_*` porte une **reprise après incident** : si le flag existe au démarrage,
c'est qu'un auto-effacement de coffre a été interrompu et doit être terminé. Le portage doit
lire ces clés, pas repartir d'un DataStore vide.
