package com.filestech.notes_tech.data.local

import com.filestech.notes_tech.data.local.entity.FolderEntity
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Fabrique une base **exactement telle que la version Flutter la crée**, pour que les tests
 * s'exécutent contre le vrai schéma hérité et non contre celui que Room voudrait produire.
 *
 * ## Pourquoi ce fichier existe
 *
 * Toute la phase 2 repose sur une affirmation : *l'application Kotlin ouvre la base déjà présente
 * chez les utilisateurs*. Un test qui laisserait Room créer la base vérifierait que Room sait lire
 * ce que Room a écrit — c'est-à-dire rien du tout. Le DDL ci-dessous est donc recopié
 * **caractère pour caractère** depuis `notes_tech/lib/data/db/database.dart`, `_createSchemaV1`.
 *
 * ⚠️ Ce fichier est une **copie**, donc une source de divergence possible. Si le DDL d'origine
 * change, celui-ci doit changer aussi, sans quoi les tests continueront de passer sur un schéma
 * qui n'existe plus. Référence : `docs/02-SCHEMA-HERITE.md` §3.
 */
object LegacyDatabaseFixture {

    /** `AppConstants.dbVersion` côté Flutter. Écrit dans `user_version`, comme le fait sqflite. */
    const val LEGACY_USER_VERSION = 9

    private val SCHEMA = listOf(
        """
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
        )
        """,
        "CREATE INDEX idx_folders_parent ON folders(parent_id)",
        """
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
        )
        """,
        """
        CREATE INDEX idx_notes_folder_active
        ON notes(folder_id, archived, trashed_at, updated_at DESC)
        """,
        "CREATE INDEX idx_notes_trashed ON notes(trashed_at)",
        "CREATE INDEX idx_notes_updated ON notes(updated_at)",
        """
        CREATE VIRTUAL TABLE notes_fts USING fts5(
          title,
          content,
          tags,
          content='notes',
          content_rowid='rowid',
          tokenize='unicode61 remove_diacritics 2'
        )
        """,
        """
        CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN
          INSERT INTO notes_fts(rowid, title, content, tags)
          VALUES (
            new.rowid,
            CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.title END,
            CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.content END,
            CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.tags END
          );
        END
        """,
        """
        CREATE TRIGGER notes_ad AFTER DELETE ON notes BEGIN
          INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
          VALUES (
            'delete',
            old.rowid,
            CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.title END,
            CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.content END,
            CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.tags END
          );
        END
        """,
        """
        CREATE TRIGGER notes_au AFTER UPDATE ON notes BEGIN
          INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
          VALUES (
            'delete',
            old.rowid,
            CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.title END,
            CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.content END,
            CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.tags END
          );
          INSERT INTO notes_fts(rowid, title, content, tags)
          VALUES (
            new.rowid,
            CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.title END,
            CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.content END,
            CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.tags END
          );
        END
        """,
        """
        CREATE TABLE note_links (
          source_id          TEXT NOT NULL,
          target_id          TEXT,
          target_title       TEXT NOT NULL,
          target_title_norm  TEXT NOT NULL,
          position           INTEGER NOT NULL,
          FOREIGN KEY (source_id) REFERENCES notes(id) ON DELETE CASCADE,
          FOREIGN KEY (target_id) REFERENCES notes(id) ON DELETE SET NULL
        )
        """,
        "CREATE INDEX idx_links_source ON note_links(source_id)",
        "CREATE INDEX idx_links_target ON note_links(target_id)",
        "CREATE INDEX idx_links_target_norm ON note_links(target_title_norm)",
    )

    /** Identifiants du jeu d'essai, exposés pour que les assertions ne devinent rien. */
    object Fixtures {
        const val FOLDER_WORK = "folder-work"
        const val FOLDER_VAULT = "folder-vault"
        const val NOTE_PLAIN = "note-plain"
        const val NOTE_LOCKED = "note-locked"
        const val NOTE_LINK_SOURCE = "note-plain"

        /** Contient un mot accentué : l'index utilise `remove_diacritics 2`, ce qui doit rendre
         *  « reunion » et « réunion » équivalents. */
        const val PLAIN_TITLE = "Réunion budget"
        const val PLAIN_CONTENT = "Points à traiter et [[Archive 2025]] à relire."

        /** Titre d'une note VERROUILLÉE. Ne doit ressortir d'AUCUNE recherche. */
        const val LOCKED_TITLE = "Codes bancaires"
        const val LOCKED_TAG = "confidentiel"
    }

    /**
     * Crée le fichier, y pose le schéma hérité et un jeu d'essai, puis ferme.
     *
     * @param rawKey le matériel de clé au format `x'<hex>'`, tel que
     *   [SqlCipherRawKey.encode] le produit. C'est **le** point que ce test doit exercer : si le
     *   format était faux, rien de ce qui suit ne fonctionnerait.
     */
    fun create(file: File, rawKey: ByteArray) {
        file.parentFile?.mkdirs()
        // Un reliquat d'exécution précédente fausserait le test en le faisant passer sur une base
        // déjà migrée par Room — donc déjà pourvue de `room_master_table`, ce qui est exactement
        // le cas qu'on ne veut PAS exercer.
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(file.path + it).delete() }

        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            rawKey.copyOf(),
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY,
            null,
        )
        try {
            db.execSQL("PRAGMA foreign_keys = ON")
            SCHEMA.forEach(db::execSQL)
            seed(db)
            // sqflite écrit la version du schéma ici ; sans elle, Room croirait à une base neuve
            // et lancerait `onCreate`, ce qui masquerait tout le comportement testé.
            db.execSQL("PRAGMA user_version = $LEGACY_USER_VERSION")
        } finally {
            db.close()
        }
    }

    private fun seed(db: SQLiteDatabase) {
        val now = 1_700_000_000_000L

        db.execSQL(
            "INSERT INTO folders (id, name, parent_id, color, icon, created_at, updated_at, " +
                "vault_attempts) VALUES (?, ?, NULL, NULL, ?, ?, ?, 0)",
            arrayOf<Any?>(FolderEntity.INBOX_ID, "Boîte de réception", "inbox", now, now),
        )
        db.execSQL(
            "INSERT INTO folders (id, name, parent_id, color, icon, created_at, updated_at, " +
                "vault_attempts) VALUES (?, ?, NULL, NULL, NULL, ?, ?, 0)",
            arrayOf<Any?>(Fixtures.FOLDER_WORK, "Travail", now, now),
        )
        // Dossier coffre : colonnes `vault_*` renseignées comme le ferait un vrai coffre
        // passphrase. Les octets sont factices — ce test ne déchiffre rien, il vérifie que les
        // colonnes BLOB traversent la couche Room sans altération.
        db.execSQL(
            "INSERT INTO folders (id, name, parent_id, color, icon, created_at, updated_at, " +
                "vault_salt, vault_kek_wrapped, vault_iv, vault_verifier, vault_mode, " +
                "vault_attempts) VALUES (?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, 'passphrase', 2)",
            arrayOf<Any?>(
                Fixtures.FOLDER_VAULT, "Coffre", now, now,
                ByteArray(16) { it.toByte() },
                ByteArray(60) { (it * 3).toByte() },
                ByteArray(12) { (it + 1).toByte() },
                ByteArray(32) { (it * 7).toByte() },
            ),
        )

        db.execSQL(
            "INSERT INTO notes (id, title, content, encrypted_content, folder_id, tags, pinned, " +
                "favorite, archived, trashed_at, created_at, updated_at, enc_v) " +
                "VALUES (?, ?, ?, NULL, ?, 'budget', 1, 0, 0, NULL, ?, ?, 1)",
            arrayOf<Any?>(
                Fixtures.NOTE_PLAIN, Fixtures.PLAIN_TITLE, Fixtures.PLAIN_CONTENT,
                Fixtures.FOLDER_WORK, now, now,
            ),
        )
        // Note VERROUILLÉE : `content` et `title` sont vidés en clair, tout est dans le blob.
        // Les triggers doivent l'exclure de l'index — c'est la promesse de non-divulgation.
        db.execSQL(
            "INSERT INTO notes (id, title, content, encrypted_content, folder_id, tags, pinned, " +
                "favorite, archived, trashed_at, created_at, updated_at, enc_v) " +
                "VALUES (?, '', '', ?, ?, ?, 0, 0, 0, NULL, ?, ?, 2)",
            arrayOf<Any?>(
                Fixtures.NOTE_LOCKED,
                ByteArray(80) { (it * 5).toByte() },
                Fixtures.FOLDER_VAULT,
                Fixtures.LOCKED_TAG,
                now,
                now,
            ),
        )

        db.execSQL(
            "INSERT INTO note_links (source_id, target_id, target_title, target_title_norm, " +
                "position) VALUES (?, NULL, ?, ?, ?)",
            arrayOf<Any?>(Fixtures.NOTE_LINK_SOURCE, "Archive 2025", "archive 2025", 24),
        )
    }

    /**
     * Écrit dans l'index le titre d'une note verrouillée, en **contournant** les triggers.
     *
     * Sert au test de non-régression du masquage : si un jour les triggers cessent de masquer, ce
     * test doit échouer. Sans un moyen de produire l'état fautif, on ne testerait que la
     * situation nominale — et le masquage est précisément ce dont dépend la promesse de
     * non-divulgation.
     */
    fun leakLockedNoteIntoIndex(file: File, rawKey: ByteArray) {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            rawKey.copyOf(),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        )
        try {
            db.execSQL(
                "INSERT INTO notes_fts(rowid, title, content, tags) " +
                    "SELECT rowid, ?, '', '' FROM notes WHERE id = ?",
                arrayOf<Any?>(Fixtures.LOCKED_TITLE, Fixtures.NOTE_LOCKED),
            )
        } finally {
            db.close()
        }
    }
}
