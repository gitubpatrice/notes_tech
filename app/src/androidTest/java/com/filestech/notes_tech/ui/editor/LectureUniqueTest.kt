package com.filestech.notes_tech.ui.editor

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
import com.filestech.notes_tech.domain.model.DureeDeVie
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
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
 * D-043 (3.4.0) — **"after reading": the note goes when its editor closes**, on the real editor and the
 * real database. The editor's ViewModel is cleared as the back stack clears it when the screen is left;
 * a rotation, which keeps the ViewModel, erases nothing — that is why the erasing sits in `onCleared`.
 * Each case has its control.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LectureUniqueTest {

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
    private val creees = mutableListOf<String>()

    @Before
    fun setUp() {
        hilt.inject()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        withContext(Dispatchers.Main) { magasin.clear() }
        portee.cancel()
        creees.forEach { notes.deletePermanently(it) }
    }

    @Test
    fun a_note_read_once_is_erased_when_its_editor_closes(): Unit = runBlocking {
        val note = nouvelleNote("à lire une fois")
        durees.poser(note.id, DureeDeVie.APRES_LECTURE)

        ouvrirPuisFermer(note) { editeur ->
            assertThat(editeur.effaceeALaFermeture).isTrue()
        }

        withTimeout(ATTENTE_MS) {
            while (notes.find(note.id) != null) delay(PAS_MS)
        }
    }

    /** The choice made in this session counts from the NEXT opening: this closing erases nothing. */
    @Test
    fun control_a_lifetime_chosen_while_the_note_is_open_does_not_erase_it_at_this_closing(): Unit = runBlocking {
        val note = nouvelleNote("choisie maintenant")

        ouvrirPuisFermer(note) { editeur ->
            editeur.setLifetime(DureeDeVie.APRES_LECTURE)
            withTimeout(ATTENTE_MS) { editeur.state.first { it.note?.readOnce == true } }
            assertThat(editeur.effaceeALaFermeture).isFalse()
        }

        delay(ATTENTE_DE_L_EFFACEMENT_MS)
        assertThat(notes.find(note.id)?.readOnce).isTrue()
    }

    @Test
    fun control_a_note_without_a_lifetime_stays(): Unit = runBlocking {
        val note = nouvelleNote("ordinaire")

        ouvrirPuisFermer(note) { editeur -> assertThat(editeur.effaceeALaFermeture).isFalse() }

        delay(ATTENTE_DE_L_EFFACEMENT_MS)
        assertThat(notes.find(note.id)).isNotNull()
    }

    private suspend fun nouvelleNote(texte: String): Note =
        notes.create(folderId = Folder.INBOX_ID, title = "Lecture unique $suffixe", content = "$texte $suffixe")
            .also { creees += it.id }

    private suspend fun ouvrirPuisFermer(note: Note, pendant: suspend (NoteEditorViewModel) -> Unit) {
        val editeur = withContext(Dispatchers.Main) { editeur(note) }
        withTimeout(ATTENTE_MS) { editeur.state.first { !it.loading } }
        pendant(editeur)
        withContext(Dispatchers.Main) { magasin.clear() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun editeur(note: Note): NoteEditorViewModel {
        val fabrique = object : ViewModelProvider.Factory {
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
                savedState = SavedStateHandle(mapOf(Destination.ARG_NOTE_ID to note.id)),
            ) as T
        }
        return ViewModelProvider.create(magasin, fabrique)[NoteEditorViewModel::class]
    }

    private companion object {
        const val ATTENTE_MS = 10_000L
        const val PAS_MS = 100L

        /** Longer than an erasing takes on the S9 (a DELETE), to see that none came. */
        const val ATTENTE_DE_L_EFFACEMENT_MS = 1_500L
    }
}
