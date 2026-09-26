package com.filestech.notes_tech.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.data.local.LegacyDatabaseFixture.Fixtures
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.google.common.truth.Truth.assertThat
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 🔴 **A deleted text does not stay in the database** — security audit of 2026-09-26, K1, K2 and P3.
 *
 * The FTS5 triggers delete with a marker only: the terms and positions of a note put in a vault, or
 * emptied from the trash, stayed in `notes_fts_data` for whoever opens the database with its key.
 * And the vault's bulk gestures left the links of a sealed note in place. Measured on a real SQLCipher
 * database — the legacy fixture, as notes_tech 2.x leaves it — opened by the app's own factory.
 *
 * How a term is looked for: in the raw blocks of `notes_fts_data`. A leaf stores its terms
 * prefix-compressed, so the search is on the token without its first letters — and each test first
 * checks that the token IS found while it is indexed, which proves the search can see it.
 */
@RunWith(AndroidJUnit4::class)
class EffacementDansLaBaseTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File
    private var database: NotesDatabase? = null

    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 13 + 5).toByte() }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        databaseFile = File(context.cacheDir, "effacement-fixture/notes_tech.db")
        LegacyDatabaseFixture.create(databaseFile, SqlCipherRawKey.encode(kek))
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(databaseFile.path + it).delete() }
    }

    /** What 2.x left behind — a note indexed, then deleted — is gone once the app has opened the base. */
    @Test
    fun the_residue_left_by_the_published_app_is_purged_at_the_first_opening() {
        val jeton = jetonUnique()
        brut { db ->
            ecrireUneNote(db, "residue", "Old $jeton")
            assertThat(indexContient(db, jeton)).isTrue()
            db.execSQL("DELETE FROM notes WHERE id = 'residue'")
            // The published app's state: the text is still in the index, under a delete marker.
            assertThat(indexContient(db, jeton)).isTrue()
        }

        val db = ouvrir().openHelper.writableDatabase

        assertThat(indexContient(db, jeton)).isFalse()
        val option = db.query("SELECT v FROM notes_fts_config WHERE k = 'secure-delete'").use {
            it.moveToFirst() && it.getInt(0) == 1
        }
        assertThat(option).isTrue()
        assertThat(compter(db, "PRAGMA secure_delete")).isEqualTo(1)
    }

    /** A note emptied from the trash, or put in a vault, after the opening: its words leave the index. */
    @Test
    fun a_text_deleted_after_the_opening_leaves_the_index() {
        val db = ouvrir().openHelper.writableDatabase
        val supprime = jetonUnique()
        val scelle = jetonUnique()
        ecrireUneNote(db, "trashed", "Trash $supprime")
        ecrireUneNote(db, "sealed", "Vault $scelle")
        assertThat(indexContient(db, supprime)).isTrue()
        assertThat(indexContient(db, scelle)).isTrue()

        db.execSQL("DELETE FROM notes WHERE id = 'trashed'")
        // Sealed as `lockNote` does: the triggers index '' from then on, and delete the old values.
        db.execSQL("UPDATE notes SET content = '', title = '', encrypted_content = x'00' WHERE id = 'sealed'")

        assertThat(indexContient(db, supprime)).isFalse()
        assertThat(indexContient(db, scelle)).isFalse()
    }

    /** Links written for a sealed note before the vault's bulk gestures detached them: repaired. */
    @Test
    fun the_links_of_a_sealed_note_are_repaired_at_the_opening() {
        brut { db ->
            // From the sealed note, a link — its `[[…]]` title in clear.
            db.execSQL(
                "INSERT INTO note_links (source_id, target_id, target_title, target_title_norm, position) " +
                    "VALUES (?, NULL, 'Bank', 'bank', 0)",
                arrayOf<Any?>(Fixtures.NOTE_LOCKED),
            )
            // To the sealed note, a link from an ordinary note, still resolved.
            db.execSQL(
                "INSERT INTO note_links (source_id, target_id, target_title, target_title_norm, position) " +
                    "VALUES (?, ?, 'Codes', 'codes', 3)",
                arrayOf<Any?>(Fixtures.NOTE_PLAIN, Fixtures.NOTE_LOCKED),
            )
        }

        val db = ouvrir().openHelper.writableDatabase

        val depuisLeCoffre = compter(db, "SELECT count(*) FROM note_links WHERE source_id = ?", Fixtures.NOTE_LOCKED)
        val versLeCoffre = compter(db, "SELECT count(*) FROM note_links WHERE target_id = ?", Fixtures.NOTE_LOCKED)
        assertThat(depuisLeCoffre).isEqualTo(0)
        assertThat(versLeCoffre).isEqualTo(0)
        // The ordinary note keeps its link, now dangling — its text still says `[[Codes]]`.
        val fantome = compter(
            db,
            "SELECT count(*) FROM note_links WHERE source_id = ? AND target_title_norm = 'codes' AND target_id IS NULL",
            Fixtures.NOTE_PLAIN,
        )
        assertThat(fantome).isEqualTo(1)
    }

    private fun compter(db: SupportSQLiteDatabase, sql: String, vararg args: Any?): Int =
        db.query(sql, args).use { curseur ->
            curseur.moveToFirst()
            curseur.getInt(0)
        }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** A token the tokenizer keeps whole, and no other term of the fixture begins like. */
    private fun jetonUnique() = "zqx" + UUID.randomUUID().toString().replace("-", "").take(12)

    /** The token without its first three letters: what a prefix-compressed leaf still stores whole. */
    private fun indexContient(db: SupportSQLiteDatabase, jeton: String): Boolean {
        val cherche = jeton.drop(3).toByteArray(Charsets.US_ASCII)
        return db.query("SELECT block FROM notes_fts_data").use { curseur ->
            var trouve = false
            while (!trouve && curseur.moveToNext()) {
                val bloc = curseur.getBlob(0) ?: continue
                trouve = contient(bloc, cherche)
            }
            trouve
        }
    }

    private fun indexContient(db: SQLiteDatabase, jeton: String): Boolean {
        val cherche = jeton.drop(3).toByteArray(Charsets.US_ASCII)
        return db.rawQuery("SELECT block FROM notes_fts_data", null).use { curseur ->
            var trouve = false
            while (!trouve && curseur.moveToNext()) {
                val bloc = curseur.getBlob(0) ?: continue
                trouve = contient(bloc, cherche)
            }
            trouve
        }
    }

    private fun contient(bloc: ByteArray, motif: ByteArray): Boolean =
        (0..bloc.size - motif.size).any { debut -> motif.indices.all { bloc[debut + it] == motif[it] } }

    private fun ecrireUneNote(db: SupportSQLiteDatabase, id: String, content: String) = db.execSQL(
        INSERT_NOTE,
        arrayOf<Any?>(id, content, Fixtures.FOLDER_WORK),
    )

    private fun ecrireUneNote(db: SQLiteDatabase, id: String, content: String) = db.execSQL(
        INSERT_NOTE,
        arrayOf<Any?>(id, content, Fixtures.FOLDER_WORK),
    )

    /** The legacy file opened by SQLCipher alone, as notes_tech 2.x would — before the app ever did. */
    private fun brut(bloc: (SQLiteDatabase) -> Unit) {
        val db = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            SqlCipherRawKey.encode(kek),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        )
        try {
            bloc(db)
        } finally {
            db.close()
        }
    }

    private fun ouvrir(): NotesDatabase {
        val source = FixedKekSource(kek)
        val repository = KekRepository(
            sources = listOf(source),
            primary = source,
            databaseExists = { databaseFile.exists() },
        )
        return NotesDatabaseFactory(
            kekRepository = repository,
            nowMillis = { FIXED_NOW },
            databaseFile = { databaseFile },
        ).build(context).also { database = it }
    }

    private class FixedKekSource(private val kek: ByteArray) : WritableKekSource {
        override val name = "test"
        override fun load(): ByteArray = kek.copyOf()
        override fun store(kek: ByteArray) = Unit
        override fun replaceKeyAndStore(kek: ByteArray) = Unit
        override fun destroy() = Unit
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L

        const val INSERT_NOTE =
            "INSERT INTO notes (id, title, content, encrypted_content, folder_id, tags, pinned, favorite, " +
                "archived, trashed_at, created_at, updated_at, enc_v) " +
                "VALUES (?, 'Title', ?, NULL, ?, '', 0, 0, 0, NULL, 0, 0, 1)"
    }
}
