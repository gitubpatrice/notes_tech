package com.filestech.notes_tech.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Schema 9 → 10, the notes' colour (3.1.0, D-028), on the two bases a user's phone can hold.
 *
 * 1. **A Flutter base** (2.0.x, never opened by Room): `user_version` 9, no `room_master_table`. A user
 *    of the 2.0.9 updating straight to 3.1.0 has this one.
 * 2. **A base adopted by 3.0.0**: the same, plus Room's identity of schema 9. Every 3.0.0 user has it.
 *
 * Both must open, migrated: the column there, `user_version` 10, and every note kept — the sealed one
 * included, with its blob untouched. `audits/verifier-schema-room-vs-flutter.py` checks the SQL without a
 * device; this replays the real `Migration` object, through the real factory, on a real SQLCipher base.
 */
@RunWith(AndroidJUnit4::class)
class MigrationVersDixTest {

    private lateinit var context: Context
    private lateinit var fichier: File
    private lateinit var provider: DatabaseProvider
    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 13 + 1).toByte() }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        fichier = File(context.cacheDir, "migration-10/notes_tech.db")
        LegacyDatabaseFixture.create(fichier, SqlCipherRawKey.encode(kek))
        val source = object : WritableKekSource {
            override val name = "test"
            override fun load(): ByteArray = kek.copyOf()
            override fun store(kek: ByteArray) = Unit
            override fun replaceKeyAndStore(kek: ByteArray) = Unit
            override fun destroy() = Unit
        }
        provider = DatabaseProvider(
            context = context,
            factory = NotesDatabaseFactory(
                kekRepository = KekRepository(listOf(source), source, databaseExists = { true }),
                nowMillis = { 1_760_000_000_000L },
                databaseFile = { fichier },
            ),
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown(): Unit = runBlocking {
        provider.close()
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(fichier.path + it).delete() }
    }

    @Test
    fun a_flutter_base_is_migrated_to_10_and_keeps_every_note(): Unit = runBlocking {
        val avant = lignesBrutes()

        val base = provider.get()

        verifierMigree(base, avant)
    }

    @Test
    fun a_base_adopted_by_3_0_0_is_migrated_to_10_and_keeps_every_note(): Unit = runBlocking {
        poserLEmpreinteDe300()
        val avant = lignesBrutes()

        val base = provider.get()

        verifierMigree(base, avant)
        // Room rewrote its identity for schema 10: the next opening validates against 10, not 9.
        val empreinte = base.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table").use {
            it.moveToFirst()
            it.getString(0)
        }
        assertThat(empreinte).isNotEqualTo(EMPREINTE_DU_SCHEMA_9)
    }

    /**
     * 3.4.0 (D-043) — **the base of every 3.1 to 3.3 user**: schema 10, adopted by Room. It must reach 11
     * through `MIGRATION_10_11` alone, every note kept.
     */
    @Test
    fun a_base_of_3_3_0_at_10_is_migrated_to_11_and_keeps_every_note(): Unit = runBlocking {
        poserLaBaseDe330()
        val avant = lignesBrutes()

        val base = provider.get()

        verifierMigree(base, avant)
        val empreinte = base.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table").use {
            it.moveToFirst()
            it.getString(0)
        }
        assertThat(empreinte).isNotEqualTo(EMPREINTE_DU_SCHEMA_10)
    }

    private suspend fun verifierMigree(base: NotesDatabase, avant: Map<String, Pair<String, ByteArray?>>) {
        val db = base.openHelper.readableDatabase
        val version = db.query("PRAGMA user_version").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertThat(version).isEqualTo(NotesDatabase.VERSION)
        val colonnes = db.query("PRAGMA table_info(notes)").use { curseur ->
            buildList { while (curseur.moveToNext()) add(curseur.getString(1)) }
        }
        assertThat(colonnes).containsAtLeast("color_id", "expires_at", "read_once")

        // Every note is there, its content and its sealed blob as they were, and no colour yet.
        val apres = base.noteDao().listAllAlive()
        assertThat(apres.map { it.id }).containsExactlyElementsIn(avant.keys)
        apres.forEach { ligne ->
            val (contenu, blob) = avant.getValue(ligne.id)
            assertThat(ligne.content).isEqualTo(contenu)
            assertThat(ligne.encryptedContent?.toList()).isEqualTo(blob?.toList())
            assertThat(ligne.colorId).isNull()
            assertThat(ligne.expiresAt).isNull()
            assertThat(ligne.readOnce).isFalse()
        }

        // And the column takes a colour — the FTS update trigger runs on that write, and must hold.
        val premiere = apres.first()
        assertThat(base.noteWriteDao().updateColor(premiere.id, colorId = 3, updatedAt = 1L)).isEqualTo(1)
        assertThat(base.noteDao().findById(premiere.id)?.colorId).isEqualTo(3)

        // And a lifetime (3.4.0): written, then the purge erases that note and that note only.
        assertThat(base.noteWriteDao().updateLifetime(premiere.id, expiresAt = 5L, readOnce = false)).isEqualTo(1)
        assertThat(base.noteWriteDao().deleteExpired(now = 4L)).isEqualTo(0)
        assertThat(base.noteWriteDao().deleteExpired(now = 5L)).isEqualTo(1)
        assertThat(base.noteDao().findById(premiere.id)).isNull()
        assertThat(base.noteDao().listAllAlive().map { it.id }).containsExactlyElementsIn(avant.keys - premiere.id)
    }

    /** The notes as the Flutter base holds them, read with SQLCipher directly, before Room opens it. */
    private fun lignesBrutes(): Map<String, Pair<String, ByteArray?>> = ouvrirBrut().use { db ->
        db.rawQuery("SELECT id, content, encrypted_content FROM notes WHERE trashed_at IS NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) to c.getBlob(2)) }
        }
    }

    /** What 3.0.0 left in the base: Room's table, with the identity of schema 9 (`9.json`). */
    private fun poserLEmpreinteDe300() = ouvrirBrut().use { db ->
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$EMPREINTE_DU_SCHEMA_9')")
    }

    /** A base as 3.1.0 to 3.3.0 leave it: schema 10 — the colour — and Room's identity of 10. */
    private fun poserLaBaseDe330() = ouvrirBrut().use { db ->
        // The real migration object: SQLCipher's database is a SupportSQLiteDatabase.
        NotesDatabase.MIGRATION_9_10.migrate(db)
        db.execSQL("PRAGMA user_version = 10")
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$EMPREINTE_DU_SCHEMA_10')")
    }

    private fun ouvrirBrut(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        fichier.absolutePath,
        SqlCipherRawKey.encode(kek),
        null,
        SQLiteDatabase.OPEN_READWRITE,
        null,
    )

    private companion object {
        /** `identityHash` of `app/schemas/…/9.json`: the schema every 3.0.0 base was validated against. */
        const val EMPREINTE_DU_SCHEMA_9 = "5ba6eb3f27afffe903eca6b0e5e9b38d"

        /** `identityHash` of `app/schemas/.../10.json`, the schema of 3.1.0 to 3.3.0. */
        const val EMPREINTE_DU_SCHEMA_10 = "e8320016c1e5a1f8f00635be6c9cad1f"
    }
}
