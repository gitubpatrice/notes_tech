package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.repository.DureesDeVie
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.LinksRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.security.clipboard.SensitiveClipboard
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.ui.navigation.Destination
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.inject.Inject

/**
 * The checklists of 3.5.0 through the real editor ViewModel, the real repositories and a passphrase vault
 * sealed for real: a box ticked from the reading (D-047), the menu's checklist (D-048), Enter continuing
 * a list (D-049) — each written into the note and SAVED, a vault note's encrypted like a keystroke.
 *
 * The rules themselves are tested on the JVM (`TaskBoxesTest`, `ListesDeMarkdownTest`); this class checks
 * that the ViewModel applies them and that what it writes reaches the database.
 *
 * ⚠️ It writes to the app's real database — one folder per test and its notes, with a random suffix,
 * deleted in [tearDown] — as `FermetureDuCoffreTest` does.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CasesDeLaNoteTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var notes: NotesRepository

    @Inject
    lateinit var durees: DureesDeVie

    @Inject
    lateinit var links: LinksRepository

    @Inject
    lateinit var exporter: NoteExporter

    @Inject
    lateinit var folders: FoldersRepository

    @Inject
    lateinit var vaults: FolderVaultService

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var clipboard: SensitiveClipboard

    private val suffixe = UUID.randomUUID().toString().take(8)
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val magasin = ViewModelStore()
    private val dossiers = mutableListOf<String>()

    @Before
    fun setUp() {
        hilt.inject()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        withContext(Dispatchers.Main) { magasin.clear() }
        portee.cancel()
        dossiers.forEach { dossier ->
            vaults.lock(dossier)
            notes.listAllAlive().filter { it.folderId == dossier }.forEach { notes.deletePermanently(it.id) }
            folders.delete(dossier)
        }
    }

    @Test
    fun a_box_ticked_in_the_reading_is_written_and_saved(): Unit = runBlocking {
        val note = noteOrdinaire(LISTE)
        val editeur = ouvrir(note)

        withContext(Dispatchers.Main) {
            editeur.basculerCase(2)
            editeur.saveNow()
        }

        assertThat(editeur.state.value.content.text).isEqualTo("- [x] Lait\n- [x] Pain")
        attendreLeContenu(note, "- [x] Lait\n- [x] Pain")
    }

    /** A vault note is saved sealed, as when it is typed in: read back through a new editor, ticked. */
    @Test
    fun a_box_ticked_in_a_vault_note_is_saved_encrypted(): Unit = runBlocking {
        val note = noteDeCoffre(LISTE)
        val editeur = ouvrir(note)
        val avant = notes.find(note)!!.updatedAt

        withContext(Dispatchers.Main) {
            editeur.basculerCase(13)
            editeur.saveNow()
        }

        withTimeout(ATTENTE_MS) { while (notes.find(note)!!.updatedAt == avant) delay(SONDE_MS) }
        assertThat(notes.find(note)!!.isLocked).isTrue()
        assertThat(texteRelu(note)).isEqualTo("- [ ] Lait\n- [ ] Pain")
    }

    /** The guard: an offset that no longer points at a box writes nothing. */
    @Test
    fun a_stale_offset_writes_nothing(): Unit = runBlocking {
        val note = noteOrdinaire(LISTE)
        val editeur = ouvrir(note)

        withContext(Dispatchers.Main) {
            editeur.basculerCase(0)
            editeur.basculerCase(40)
        }

        assertThat(editeur.state.value.content.text).isEqualTo(LISTE)
    }

    @Test
    fun the_checklist_entry_and_Enter_write_into_the_note(): Unit = runBlocking {
        val note = noteOrdinaire("Lait")
        val editeur = ouvrir(note)

        withContext(Dispatchers.Main) {
            editeur.onContentChange(TextFieldValue("Lait", TextRange(4)))
            editeur.basculerListeACocher()
        }
        assertThat(editeur.state.value.content.text).isEqualTo("- [ ] Lait")
        assertThat(editeur.state.value.content.selection).isEqualTo(TextRange(10))

        // Enter at the end, as the field sends it: one line break typed at the cursor.
        withContext(Dispatchers.Main) {
            editeur.onContentChange(TextFieldValue("- [ ] Lait\n", TextRange(11)))
            editeur.saveNow()
        }
        assertThat(editeur.state.value.content.text).isEqualTo("- [ ] Lait\n- [ ] ")
        assertThat(editeur.state.value.content.selection).isEqualTo(TextRange(17))
        attendreLeContenu(note, "- [ ] Lait\n- [ ] ")
    }

    private suspend fun noteOrdinaire(contenu: String): String {
        val dossier = folders.create("Plain $suffixe").id.also { dossiers += it }
        return notes.create(folderId = dossier, title = "Courses $suffixe", content = contenu).id
    }

    /** A note sealed in a passphrase vault created and left open for the test. */
    private suspend fun noteDeCoffre(contenu: String): String {
        val dossier = folders.create("Vault $suffixe").id.also { dossiers += it }
        vaults.createPassphraseVault(dossier, PHRASE)
        val note = notes.create(folderId = dossier, title = "Courses $suffixe", content = contenu)
        assertThat(notes.find(note.id)!!.isLocked).isTrue()
        return note.id
    }

    private suspend fun ouvrir(note: String): NoteEditorViewModel {
        val editeur = withContext(Dispatchers.Main) { editeur(note) }
        withTimeout(ATTENTE_MS) { editeur.state.first { !it.loading } }
        return editeur
    }

    /** The note's text as a NEW editor reads it — decrypted, for a vault note whose vault is open. */
    private suspend fun texteRelu(note: String): String {
        val autre = withContext(Dispatchers.Main) {
            ViewModelProvider.create(ViewModelStore(), fabrique(note))[NoteEditorViewModel::class]
        }
        return withTimeout(ATTENTE_MS) { autre.state.first { !it.loading } }.content.text
    }

    private suspend fun attendreLeContenu(note: String, attendu: String) = withTimeout(ATTENTE_MS) {
        while (notes.find(note)?.content != attendu) delay(SONDE_MS)
    }

    /** The editor of [noteId], as the navigation would build it — cleared in [tearDown]. */
    private fun editeur(noteId: String): NoteEditorViewModel =
        ViewModelProvider.create(magasin, fabrique(noteId))[NoteEditorViewModel::class]

    private fun fabrique(noteId: String) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = NoteEditorViewModel(
            notes = notes,
            links = links,
            exporter = exporter,
            folders = folders,
            durees = durees,
            vaults = vaults,
            settings = settings,
            clipboard = clipboard,
            applicationScope = portee,
            savedState = SavedStateHandle(mapOf(Destination.ARG_NOTE_ID to noteId)),
        ) as T
    }

    private companion object {
        const val LISTE = "- [ ] Lait\n- [x] Pain"
        const val PHRASE = "correct horse battery staple"
        const val ATTENTE_MS = 20_000L
        const val SONDE_MS = 50L
    }
}
