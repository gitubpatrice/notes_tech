package com.filestech.notes_tech.ui.editor

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
 * **A `[[Title]]` tapped in a vault note opens the note of that vault** — solution B, chosen by
 * Patrice on 2026-09-25 — through the real editor ViewModel, the real repositories and the real vault
 * service: a passphrase vault created and opened for the test, its notes sealed for real.
 *
 * notes_tech 2.0.9 resolves no vault note at all: a link tapped in a vault created a new, empty note
 * each time, even when the vault held one of that title. The rule itself is tested in
 * `NotesRepositoryTest`; this proves the editor goes through it.
 *
 * ⚠️ It writes to the app's real database: one folder and its notes, with a random suffix, deleted in
 * [tearDown].
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LiensDansUnCoffreTest {

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
    private var coffre: String? = null

    @Before
    fun setUp() {
        hilt.inject()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        withContext(Dispatchers.Main) { magasin.clear() }
        portee.cancel()
        val dossier = coffre ?: return@runBlocking
        notes.listAllAlive().filter { it.folderId == dossier }.forEach { notes.deletePermanently(it.id) }
        vaults.lock(dossier)
        folders.delete(dossier)
    }

    @Test
    fun a_link_tapped_in_a_vault_note_opens_the_note_of_that_vault(): Unit = runBlocking {
        val dossier = folders.create("Vault $suffixe").id.also { coffre = it }
        vaults.createPassphraseVault(dossier, PHRASE)
        val titreCible = "Codes $suffixe"
        val cible = notes.create(folderId = dossier, title = titreCible, content = "0000")
        val source = notes.create(folderId = dossier, title = "Bank $suffixe", content = "See [[$titreCible]].")
        // Sealed for real: the title is in the blob, where only the open session reads it.
        assertThat(notes.find(cible.id)!!.isLocked).isTrue()
        assertThat(notes.find(cible.id)!!.title).isEmpty()

        val editeur = withContext(Dispatchers.Main) { editeur(source.id) }
        val chargee = withTimeout(ATTENTE_MS) { editeur.state.first { !it.loading } }
        assertThat(chargee.note?.folderId).isEqualTo(dossier)

        editeur.ouvrirOuCreerLaNote(titreCible)
        val issue = withTimeout(ATTENTE_MS) { editeur.action.first { it.aOuvrir != null || it.erreur != null } }

        assertThat(issue.erreur).isNull()
        assertThat(issue.aOuvrir).isEqualTo(cible.id)
        // And nothing was created beside it.
        assertThat(notes.listAllAlive().count { it.folderId == dossier }).isEqualTo(2)
    }

    /**
     * 🔴 **In a vault note, the link sheet knows the vault's note whose title is typed in full**, and
     * links it instead of offering to create a second one. Before, it saw no vault note at all: it
     * said "Create a new note", Enter created a duplicate, and the inserted link then opened the older
     * note, never the new one.
     *
     * The control is the rule of issue #10 — no list of vault titles on screen: a title typed only in
     * part names nothing from the vault.
     */
    @Test
    fun the_link_sheet_of_a_vault_note_links_the_note_typed_in_full_and_lists_none(): Unit = runBlocking {
        val dossier = folders.create("Vault $suffixe").id.also { coffre = it }
        vaults.createPassphraseVault(dossier, PHRASE)
        val titreCible = "Codes $suffixe"
        val cible = notes.create(folderId = dossier, title = titreCible, content = "0000")
        val source = notes.create(folderId = dossier, title = "Bank $suffixe", content = "")
        val editeur = withContext(Dispatchers.Main) { editeur(source.id) }
        withTimeout(ATTENTE_MS) { editeur.state.first { !it.loading } }

        val entier = reponseA(editeur, titreCible.uppercase())
        val etat = etatDAutocompletion(titreCible.uppercase(), entier)
        assertThat(entier.titres.map { it.id }).containsExactly(cible.id)
        assertThat(etat.proposerLaCreation).isFalse()
        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Lier(titreCible))

        val enPartie = reponseA(editeur, "Codes")
        assertThat(enPartie.titres.map { it.id }).doesNotContain(cible.id)
    }

    /**
     * The vault locked while the sheet is open: its notes can no longer be read. The search answers,
     * marked as failed — escaping, its failure brought the app down (measured on the S9) — and the
     * sheet offers nothing, creation included: it cannot tell whether the note exists.
     */
    @Test
    fun the_link_sheet_of_a_vault_locked_meanwhile_answers_without_crashing(): Unit = runBlocking {
        val dossier = folders.create("Vault $suffixe").id.also { coffre = it }
        vaults.createPassphraseVault(dossier, PHRASE)
        val titreCible = "Codes $suffixe"
        notes.create(folderId = dossier, title = titreCible, content = "0000")
        val source = notes.create(folderId = dossier, title = "Bank $suffixe", content = "")
        val editeur = withContext(Dispatchers.Main) { editeur(source.id) }
        withTimeout(ATTENTE_MS) { editeur.state.first { !it.loading } }
        vaults.lock(dossier)

        val reponse = reponseA(editeur, titreCible)
        val etat = etatDAutocompletion(titreCible, reponse)

        assertThat(reponse.echec).isTrue()
        assertThat(etat.proposerLaCreation).isFalse()
        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Rien)
    }

    /** The sheet's answer to [saisie], as the sheet waits for it: the one that carries its question. */
    private suspend fun reponseA(editeur: NoteEditorViewModel, saisie: String): SuggestionsDeLien {
        editeur.chercherUnTitre(saisie)
        return withTimeout(ATTENTE_MS) { editeur.suggestionsDeLien.first { it.pour == saisie } }
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
