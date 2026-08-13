package com.filestech.notes_tech.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Tout ce que Room **ne gère pas** dans la base héritée, en un seul endroit.
 *
 * Room possède `folders` et `notes` — deux tables ordinaires, avec clé primaire, qu'il sait
 * décrire et valider. Le reste du schéma lui échappe pour des raisons **structurelles**, pas par
 * commodité :
 *
 * ### `notes_fts` — table virtuelle FTS5
 *
 * Room ne sait annoter que du FTS**4** (`@Fts4`). Déclarer cette table comme entité produirait une
 * table FTS4, incompatible avec l'index existant. Elle est donc créée en SQL brut et interrogée
 * par `@RawQuery`.
 *
 * ### Les trois triggers
 *
 * Room n'a aucune notion de trigger. Ceux-ci maintiennent l'index FTS5 en phase avec `notes`, et
 * masquent titre/contenu/étiquettes des notes verrouillées.
 *
 * ### `note_links` — **pas de clé primaire**
 *
 * La table héritée n'en déclare aucune (`docs/02-SCHEMA-HERITE.md` §3). Or `@Entity` en exige une,
 * et la validation de schéma de Room compare les clés primaires : déclarer une clé qui n'existe
 * pas dans la base ferait échouer l'ouverture chez tous les utilisateurs.
 *
 * Les deux échappatoires ont été écartées :
 *
 * - *Ajouter la clé primaire par migration.* Ce serait modifier le schéma d'une base en
 *   production pour le confort de l'outillage. Surtout, ça casserait une propriété qu'on veut
 *   garder pendant toute la transition : **une base ouverte par cette application reste lisible
 *   par la version Flutter.** Room n'incrémente pas `user_version` et se contente d'ajouter sa
 *   `room_master_table`, que sqflite ignore. Un utilisateur peut donc revenir en arrière. Une
 *   table recréée retirerait ce filet.
 * - *Mapper le `rowid` implicite.* `PRAGMA table_info` ne le renvoie pas, donc la validation
 *   verrait une colonne attendue de plus que trouvée.
 *
 * Conséquence assumée : les lectures de `note_links` passent par `@RawQuery` avec
 * `observedEntities = [NoteEntity::class]`. Ce n'est pas un contournement de la réactivité mais sa
 * description exacte — les liens sont **dérivés** du contenu des notes et ne changent jamais sans
 * qu'une note change.
 */
internal object UnmanagedSchema {

    // ── Table des liens ──────────────────────────────────────────────────────

    private const val CREATE_NOTE_LINKS = """
        CREATE TABLE IF NOT EXISTS note_links (
          source_id          TEXT NOT NULL,
          target_id          TEXT,
          target_title       TEXT NOT NULL,
          target_title_norm  TEXT NOT NULL,
          position           INTEGER NOT NULL,
          FOREIGN KEY (source_id) REFERENCES notes(id) ON DELETE CASCADE,
          FOREIGN KEY (target_id) REFERENCES notes(id) ON DELETE SET NULL
        )
    """

    private val CREATE_NOTE_LINKS_INDICES = listOf(
        "CREATE INDEX IF NOT EXISTS idx_links_source ON note_links(source_id)",
        "CREATE INDEX IF NOT EXISTS idx_links_target ON note_links(target_id)",
        "CREATE INDEX IF NOT EXISTS idx_links_target_norm ON note_links(target_title_norm)",
    )

    // ── Index plein texte ────────────────────────────────────────────────────

    /**
     * `content='notes'` + `content_rowid='rowid'` : index à **contenu externe**. FTS5 ne stocke
     * pas une copie du texte, il pointe vers `notes` par `rowid` — d'où la règle absolue de ne
     * jamais changer un `rowid` (`docs/04-PIEGES.md` §1).
     *
     * `remove_diacritics 2` est la variante qui traite correctement les caractères composés :
     * indispensable pour du français, et **non négociable** — la changer invaliderait
     * silencieusement l'index existant, qui continuerait de répondre, mais faux.
     */
    private const val CREATE_FTS = """
        CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(
          title,
          content,
          tags,
          content='notes',
          content_rowid='rowid',
          tokenize='unicode61 remove_diacritics 2'
        )
    """

    /**
     * Expression réutilisée par les trois triggers : une note verrouillée n'expose **rien** à
     * l'index.
     *
     * Avant la v1.0.3 côté Flutter, seul `content` était vidé au verrouillage — une recherche sur
     * le **titre** ressortait quand même la note. Le masquage porte donc sur les trois colonnes.
     */
    private fun masked(alias: String, column: String) =
        "CASE WHEN $alias.encrypted_content IS NOT NULL THEN '' ELSE $alias.$column END"

    private fun ftsRow(alias: String) =
        "${masked(alias, "title")}, ${masked(alias, "content")}, ${masked(alias, "tags")}"

    private val CREATE_TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS notes_ai AFTER INSERT ON notes BEGIN
          INSERT INTO notes_fts(rowid, title, content, tags)
          VALUES (new.rowid, ${ftsRow("new")});
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS notes_ad AFTER DELETE ON notes BEGIN
          INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
          VALUES ('delete', old.rowid, ${ftsRow("old")});
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS notes_au AFTER UPDATE ON notes BEGIN
          INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
          VALUES ('delete', old.rowid, ${ftsRow("old")});
          INSERT INTO notes_fts(rowid, title, content, tags)
          VALUES (new.rowid, ${ftsRow("new")});
        END
        """,
    )

    /**
     * Crée ce que Room ne crée pas. Appelé **uniquement** sur une base neuve
     * (`RoomDatabase.Callback.onCreate`).
     *
     * Les `IF NOT EXISTS` ne sont pas de la superstition : ils rendent l'appel rejouable, ce qui
     * compte si la création est interrompue entre deux instructions.
     */
    fun createOnFreshDatabase(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_NOTE_LINKS)
        CREATE_NOTE_LINKS_INDICES.forEach(db::execSQL)
        db.execSQL(CREATE_FTS)
        CREATE_TRIGGERS.forEach(db::execSQL)
    }

    /**
     * Garantit l'existence du dossier racine, à **chaque** ouverture.
     *
     * `INSERT OR IGNORE`, donc idempotent, et surtout non destructif : un utilisateur qui a
     * renommé sa boîte de réception garde son libellé. Le nom posé ici n'est utilisé qu'à la
     * toute première création, et il est écrit en dur — c'est ce que fait la version Flutter, et
     * s'en écarter donnerait deux libellés différents selon la version qui a créé la base.
     *
     * ⚠️ `INSERT OR IGNORE` et non `INSERT OR REPLACE` : `REPLACE` remplacerait la ligne, donc
     * changerait son `rowid`, donc supprimerait en cascade toutes les notes de la boîte de
     * réception. Cf. `docs/04-PIEGES.md` §1.
     */
    fun ensureInboxFolder(db: SupportSQLiteDatabase, nowMillis: Long) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO folders
              (id, name, parent_id, color, icon, created_at, updated_at, vault_attempts)
            VALUES (?, ?, NULL, NULL, ?, ?, ?, 0)
            """,
            // `arrayOf<Any?>` explicite : sans le paramètre de type, Kotlin infère le supertype
            // commun de String et Long — une intersection réifiée que le compilateur refuse.
            arrayOf<Any?>(INBOX_ID, INBOX_DEFAULT_NAME, INBOX_ICON, nowMillis, nowMillis),
        )
    }

    private const val INBOX_ID = "inbox"
    private const val INBOX_ICON = "inbox"

    /** Libellé d'origine, écrit en dur par la version Flutter (`database.dart:869`). */
    private const val INBOX_DEFAULT_NAME = "Boîte de réception"
}
