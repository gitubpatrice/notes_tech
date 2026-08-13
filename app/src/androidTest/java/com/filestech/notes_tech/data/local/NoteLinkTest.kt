package com.filestech.notes_tech.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.data.local.entity.NoteEntity
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rétroliens `[[Titre]]` — le comportement des clés étrangères et des liens fantômes.
 *
 * Ces tests portent sur des règles qui ne vivent nulle part dans le code Kotlin : elles sont dans
 * le **schéma hérité**, sous forme de clés étrangères. Les vérifier ailleurs qu'en exécutant du
 * vrai SQLite reviendrait à tester ce qu'on croit qu'elles font.
 */
@RunWith(AndroidJUnit4::class)
class NoteLinkTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File
    private var database: NotesDatabase? = null
    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 7 + 1).toByte() }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        databaseFile = File(context.cacheDir, "links-fixture/notes_tech.db")
        LegacyDatabaseFixture.create(databaseFile, SqlCipherRawKey.encode(kek))
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(databaseFile.path + it).delete() }
    }

    @Test
    fun les_liens_sont_remplaces_en_bloc(): Unit = runBlocking {
        val db = openDatabase()
        val writer = NoteLinkWriter(db)
        val source = LegacyDatabaseFixture.Fixtures.NOTE_PLAIN

        // Le jeu d'essai en contient déjà un ; le remplacement doit l'effacer, pas s'y ajouter.
        assertThat(db.noteLinkDao().outgoing(source).first()).hasSize(1)

        writer.replaceLinksOf(
            source,
            listOf(
                OutgoingLink(targetId = null, targetTitle = "Idées", targetTitleNorm = "idees", position = 3),
                OutgoingLink(targetId = null, targetTitle = "Plan", targetTitleNorm = "plan", position = 42),
            ),
        )

        val liens = db.noteLinkDao().outgoing(source).first()
        assertThat(liens.map { it.targetTitle }).containsExactly("Idées", "Plan").inOrder()
        // L'ordre suit `position`, qui est le décalage dans le texte — pas l'ordre d'insertion.
        assertThat(liens.map { it.position }).containsExactly(3, 42).inOrder()
    }

    @Test
    fun un_lien_fantome_se_resout_quand_sa_cible_apparait(): Unit = runBlocking {
        val db = openDatabase()
        val writer = NoteLinkWriter(db)
        val source = LegacyDatabaseFixture.Fixtures.NOTE_PLAIN

        writer.replaceLinksOf(
            source,
            listOf(OutgoingLink(null, "Archive 2025", "archive 2025", 0)),
        )
        assertThat(db.noteLinkDao().dangling().first()).hasSize(1)

        db.noteWriteDao().insert(note(id = "note-archive", title = "Archive 2025"))
        writer.resolveDanglingTargets("note-archive", "archive 2025")

        assertThat(db.noteLinkDao().dangling().first()).isEmpty()
        assertThat(db.noteLinkDao().backlinks("note-archive", "archive 2025").first().map(NoteEntity::id))
            .containsExactly(source)
    }

    @Test
    fun renommer_une_note_redonne_leur_statut_de_fantome_aux_liens_qui_la_visaient(): Unit = runBlocking {
        val db = openDatabase()
        val writer = NoteLinkWriter(db)
        db.noteWriteDao().insert(note(id = "note-cible", title = "Ancien titre"))
        writer.replaceLinksOf(
            LegacyDatabaseFixture.Fixtures.NOTE_PLAIN,
            listOf(OutgoingLink("note-cible", "Ancien titre", "ancien titre", 0)),
        )

        // La note est renommée : le lien `[[Ancien titre]]` ne doit plus y mener, sinon il
        // conduirait à une note qui ne porte plus ce titre.
        writer.unresolveByMismatch("note-cible", "nouveau titre")

        assertThat(db.noteLinkDao().backlinks("note-cible", "cible").first()).isEmpty()
        assertThat(db.noteLinkDao().dangling().first()).hasSize(1)
    }

    @Test
    fun une_note_verrouillee_n_apparait_jamais_dans_les_retroliens(): Unit = runBlocking {
        val db = openDatabase()
        val writer = NoteLinkWriter(db)
        db.noteWriteDao().insert(note(id = "note-cible", title = "Cible"))

        // Le lien part d'une note de COFFRE. L'afficher en rétrolien divulguerait son titre depuis
        // une note qui, elle, n'est pas protégée.
        writer.replaceLinksOf(
            LegacyDatabaseFixture.Fixtures.NOTE_LOCKED,
            listOf(OutgoingLink("note-cible", "Cible", "cible", 0)),
        )

        assertThat(db.noteLinkDao().backlinks("note-cible", "cible").first()).isEmpty()
    }

    @Test
    fun supprimer_une_note_emporte_ses_liens_sortants_et_rend_fantomes_les_entrants(): Unit = runBlocking {
        val db = openDatabase()
        val writer = NoteLinkWriter(db)
        db.noteWriteDao().insert(note(id = "note-cible", title = "Cible"))
        writer.replaceLinksOf(
            LegacyDatabaseFixture.Fixtures.NOTE_PLAIN,
            listOf(OutgoingLink("note-cible", "Cible", "cible", 0)),
        )

        db.noteWriteDao().deletePermanently("note-cible")

        // Deux comportements DIFFÉRENTS, tous deux portés par le schéma hérité et non par le code :
        //   - ON DELETE SET NULL sur `target_id` → le lien survit, redevenu fantôme ;
        //   - le texte `[[Cible]]` reste écrit dans la note source, donc le lien doit rester.
        assertThat(db.noteLinkDao().dangling().first()).hasSize(1)

        // Et dans l'autre sens : supprimer la SOURCE emporte bien ses liens (ON DELETE CASCADE).
        db.noteWriteDao().deletePermanently(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)
        assertThat(db.noteLinkDao().dangling().first()).isEmpty()
    }

    private fun note(id: String, title: String) = NoteEntity(
        id = id,
        title = title,
        content = "",
        encryptedContent = null,
        folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
        tags = "",
        pinned = false,
        favorite = false,
        archived = false,
        trashedAt = null,
        createdAt = 1L,
        updatedAt = 1L,
        encVersion = 1,
    )

    private fun openDatabase(): NotesDatabase {
        val source = object : WritableKekSource {
            override val name = "test"
            override fun load(): ByteArray = kek.copyOf()
            override fun store(kek: ByteArray) = Unit
            override fun replaceKeyAndStore(kek: ByteArray) = Unit
        }
        val repository = KekRepository(listOf(source), source, databaseExists = { true })
        return NotesDatabaseFactory(
            kekRepository = repository,
            nowMillis = { 1_700_000_000_000L },
            databaseFile = { databaseFile },
        ).build(context).also { database = it }
    }
}
