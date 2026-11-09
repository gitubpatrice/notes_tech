package com.filestech.notes_tech.ui.scellee

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.data.scellee.FichierDeNoteScellee
import com.filestech.notes_tech.data.scellee.FichierDeNoteScellee.Raison
import com.filestech.notes_tech.data.scellee.NoteScellee
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.NoteColor
import com.filestech.notes_tech.security.sauvegarde.SecretDeSauvegarde
import com.filestech.notes_tech.ui.scellee.NotesScelleesViewModel.Etat
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
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
 * D-044 (3.4.0) — **a sealed note received becomes ONE note of the Inbox, with what its sender sealed**,
 * on the real ViewModel and the real database: a wrong code first is refused and lets the right one
 * through, the colour, the tags and "after reading" are kept, opening again adds nothing; and a second
 * file deposited while the first is being opened waits for it instead of spoiling it (3-axes audit).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NotesScelleesRecuesTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var enAttente: NotesScelleesEnAttente

    @Inject
    lateinit var notes: NotesRepository

    private val suffixe = UUID.randomUUID().toString().take(8)
    private val magasin = ViewModelStore()
    private val creees = mutableListOf<String>()

    @Before
    fun setUp() {
        hilt.inject()
        enAttente.abandonner()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        withContext(Dispatchers.Main) { magasin.clear() }
        enAttente.abandonner()
        creees.forEach { notes.deletePermanently(it) }
    }

    @Test
    fun the_right_code_after_a_wrong_one_gives_one_note_with_what_was_sealed(): Unit = runBlocking {
        val code = SecretDeSauvegarde.nouveauCode()
        val scellee = scellee("Code de l'immeuble", lectureUnique = true)
        val recues = viewModel()

        deposerPuisPrendre(recues, FichierDeNoteScellee.sceller(scellee, code))
        withContext(Dispatchers.Main) { recues.ouvrir(SecretDeSauvegarde.nouveauCode()) }
        assertThat(attendreUneFin(recues)).isEqualTo(Etat.Code(erreur = Raison.CODE_OU_FICHIER))

        withContext(Dispatchers.Main) { recues.ouvrir(code.lowercase()) }
        val id = withTimeout(ATTENTE_MS) { recues.aOuvrir.first() }.also(creees::add)

        val note = requireNotNull(notes.find(id))
        assertThat(note.folderId).isEqualTo(Folder.INBOX_ID)
        assertThat(note.title).isEqualTo(scellee.titre)
        assertThat(note.content).isEqualTo(scellee.contenu)
        assertThat(note.tags).containsExactly("maison", "accès").inOrder()
        assertThat(note.color).isEqualTo(NoteColor.fromId(scellee.couleur))
        assertThat(note.readOnce).isTrue()
        assertThat(note.encrypted).isNull()
        assertThat(recues.etat.value).isEqualTo(Etat.Aucune)
    }

    /** Control: the bytes are let go once the note is in — opening again adds nothing. */
    @Test
    fun control_opening_again_adds_no_second_note(): Unit = runBlocking {
        val code = SecretDeSauvegarde.nouveauCode()
        val scellee = scellee("Une seule fois", lectureUnique = false)
        val recues = viewModel()

        deposerPuisPrendre(recues, FichierDeNoteScellee.sceller(scellee, code))
        withContext(Dispatchers.Main) { recues.ouvrir(code) }
        creees += withTimeout(ATTENTE_MS) { recues.aOuvrir.first() }
        withContext(Dispatchers.Main) { recues.ouvrir(code) }

        assertThat(recues.etat.value).isEqualTo(Etat.Aucune)
        assertThat(notes.listAllAlive().count { it.title == scellee.titre }).isEqualTo(1)
    }

    /** 🔴 3-axes audit of 3.4.0, M4: the second file is not taken under the first one's decryption. */
    @Test
    fun a_second_file_deposited_while_the_first_opens_waits_for_it(): Unit = runBlocking {
        val premierCode = SecretDeSauvegarde.nouveauCode()
        val secondCode = SecretDeSauvegarde.nouveauCode()
        val premiere = scellee("Premiere", lectureUnique = false)
        val seconde = scellee("Seconde", lectureUnique = false)
        val secondFichier = FichierDeNoteScellee.sceller(seconde, secondCode)
        val recues = viewModel()

        deposerPuisPrendre(recues, FichierDeNoteScellee.sceller(premiere, premierCode))
        // One block on the main thread: the first opening cannot end in between, whatever the phone's speed.
        withContext(Dispatchers.Main) {
            recues.ouvrir(premierCode)
            assertThat(recues.etat.value).isEqualTo(Etat.Code(enCours = true))
            enAttente.deposer(secondFichier)
            recues.prendre()
            assertThat(recues.etat.value).isEqualTo(Etat.Code(enCours = true))
        }
        creees += withTimeout(ATTENTE_MS) { recues.aOuvrir.first() }

        // The first is in, the second taken after it: its own code opens it.
        assertThat(withTimeout(ATTENTE_MS) { recues.etat.first { it == Etat.Code() } }).isEqualTo(Etat.Code())
        withContext(Dispatchers.Main) { recues.ouvrir(secondCode) }
        creees += withTimeout(ATTENTE_MS) { recues.aOuvrir.first() }

        val titres = creees.mapNotNull { notes.find(it)?.title }
        assertThat(titres).containsExactly(premiere.titre, seconde.titre).inOrder()
    }

    private fun scellee(titre: String, lectureUnique: Boolean) = NoteScellee(
        titre = "$titre $suffixe",
        contenu = "B 4521 — « ne pas oublier » 🔑\nseconde ligne",
        couleur = NoteColor.entries.last().id,
        etiquettes = listOf("maison", "accès"),
        lectureUnique = lectureUnique,
    )

    private suspend fun deposerPuisPrendre(recues: NotesScelleesViewModel, fichier: ByteArray) =
        withContext(Dispatchers.Main) {
            enAttente.deposer(fichier)
            recues.prendre()
            assertThat(recues.etat.value).isEqualTo(Etat.Code())
        }

    private suspend fun attendreUneFin(recues: NotesScelleesViewModel): Etat =
        withTimeout(ATTENTE_MS) { recues.etat.first { it is Etat.Code && !it.enCours } }

    @Suppress("UNCHECKED_CAST")
    private suspend fun viewModel(): NotesScelleesViewModel = withContext(Dispatchers.Main) {
        val fabrique = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                NotesScelleesViewModel(enAttente, notes) as T
        }
        ViewModelProvider.create(magasin, fabrique)[NotesScelleesViewModel::class]
    }

    private companion object {
        /** Two Argon2id derivations (64 MiB, t=3) and an INSERT, on the S9, with margin. */
        const val ATTENTE_MS = 20_000L
    }
}
