package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.data.prefs.AppSettings
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
 * 🔴 **A vault that closes takes its plaintext off the editor** — security audit of 2026-09-26, V1.
 *
 * `lockAll()` (app in the background) and the inactivity sweep wiped the keys only: the editor kept
 * the decrypted title and body, on screen and copyable at the return, with no session. Tested through
 * the real editor ViewModel, the real repositories and a passphrase vault sealed for real.
 *
 * ⚠️ It writes to the app's real database — one folder and its notes, with a random suffix, deleted in
 * [tearDown] — and restores the list of lost drafts it touches.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FermetureDuCoffreTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var notes: NotesRepository

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
    private var brouillonsPerdus: List<String> = emptyList()

    @Before
    fun setUp() {
        hilt.inject()
        brouillonsPerdus = settings.vaultLostDrafts()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        withContext(Dispatchers.Main) { magasin.clear() }
        portee.cancel()
        settings.clearVaultLostDrafts()
        brouillonsPerdus.forEach(settings::addVaultLostDraft)
        dossiers.forEach { dossier ->
            vaults.lock(dossier)
            notes.listAllAlive().filter { it.folderId == dossier }.forEach { notes.deletePermanently(it.id) }
            folders.delete(dossier)
        }
    }

    @Test
    fun locking_every_vault_drops_the_plaintext_and_asks_for_the_secret(): Unit = runBlocking {
        val note = noteDeCoffre(content = "PIN 4242 $suffixe")
        val editeur = withContext(Dispatchers.Main) { editeur(note) }
        val ouverte = withTimeout(ATTENTE_MS) { editeur.state.first { !it.loading } }
        assertThat(ouverte.content.text).isEqualTo("PIN 4242 $suffixe")

        vaults.lockAll()

        val fermee = withTimeout(ATTENTE_MS) { editeur.state.first { it.lockedVault != null } }
        assertThat(fermee.title).isEmpty()
        assertThat(fermee.content.text).isEmpty()
        assertThat(fermee.originalContent).isEmpty()
        // Nothing was typed: nothing is reported lost.
        assertThat(fermee.lostToVaultLock).isFalse()
        assertThat(settings.vaultLostDrafts()).doesNotContain(note)
    }

    /** Text typed and not yet saved cannot be written without the key: it is said, not dropped silently. */
    @Test
    fun text_typed_before_the_lock_is_reported_lost(): Unit = runBlocking {
        val note = noteDeCoffre(content = "draft")
        val editeur = withContext(Dispatchers.Main) { editeur(note) }
        withTimeout(ATTENTE_MS) { editeur.state.first { !it.loading } }

        withContext(Dispatchers.Main) {
            editeur.onContentChange(TextFieldValue("draft, then more"))
            vaults.lockAll()
        }

        val fermee = withTimeout(ATTENTE_MS) { editeur.state.first { it.lockedVault != null } }
        assertThat(fermee.content.text).isEmpty()
        assertThat(fermee.lostToVaultLock).isTrue()
        assertThat(settings.vaultLostDrafts()).contains(note)
    }

    /** The copy of the menu, the one gesture that exports the text, needs a live session. */
    @Test
    fun the_copy_of_a_closed_vault_note_copies_nothing(): Unit = runBlocking {
        val note = noteDeCoffre(content = "secret $suffixe")
        val editeur = withContext(Dispatchers.Main) { editeur(note) }
        withTimeout(ATTENTE_MS) { editeur.state.first { !it.loading } }
        vaults.lockAll()
        withTimeout(ATTENTE_MS) { editeur.state.first { it.lockedVault != null } }

        withContext(Dispatchers.Main) { editeur.copierEnMarkdown() }

        val issue = withTimeout(ATTENTE_MS) { editeur.action.first { !it.enCours } }
        assertThat(issue.copiee).isFalse()
    }

    /** The control: an ordinary note is not touched by a vault closing. */
    @Test
    fun an_ordinary_note_stays_on_screen_when_the_vaults_close(): Unit = runBlocking {
        val dossier = folders.create("Plain $suffixe").id.also { dossiers += it }
        val note = notes.create(folderId = dossier, title = "Groceries $suffixe", content = "milk").id
        val editeur = withContext(Dispatchers.Main) { editeur(note) }
        withTimeout(ATTENTE_MS) { editeur.state.first { !it.loading } }
        noteDeCoffre(content = "other") // a vault open, so that lockAll() has something to close

        vaults.lockAll()
        withTimeout(ATTENTE_MS) { vaults.unlockedFolderIds.first { it.isEmpty() } }

        val etat = editeur.state.value
        assertThat(etat.lockedVault).isNull()
        assertThat(etat.content.text).isEqualTo("milk")
    }

    /** A note sealed in a passphrase vault created and left open for the test. */
    private suspend fun noteDeCoffre(content: String): String {
        val dossier = folders.create("Vault $suffixe").id.also { dossiers += it }
        vaults.createPassphraseVault(dossier, PHRASE)
        val note = notes.create(folderId = dossier, title = "Bank $suffixe", content = content)
        assertThat(notes.find(note.id)!!.isLocked).isTrue()
        return note.id
    }

    /** The editor of [noteId], as the navigation would build it — cleared in [tearDown]. */
    private fun editeur(noteId: String): NoteEditorViewModel {
        val fabrique = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = NoteEditorViewModel(
                notes = notes,
                links = links,
                exporter = exporter,
                folders = folders,
                vaults = vaults,
                settings = settings,
                clipboard = clipboard,
                applicationScope = portee,
                savedState = SavedStateHandle(mapOf(Destination.ARG_NOTE_ID to noteId)),
            ) as T
        }
        return ViewModelProvider.create(magasin, fabrique)[NoteEditorViewModel::class]
    }

    private companion object {
        const val PHRASE = "correct horse battery staple"
        const val ATTENTE_MS = 20_000L
    }
}
