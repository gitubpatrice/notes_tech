package com.filestech.notes_tech.ui.depot

import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.importation.LongueurDUneNote
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.security.panic.PanicJournal
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The blind drop (D-042, 3.4.0): it writes one note to the Inbox and reads nothing — its only call on
 * the notes is [NotesRepository.create], checked with `confirmVerified`.
 */
@DisplayName("Dépôt à l'aveugle")
class DepotViewModelTest {

    private val notes = mockk<NotesRepository>()
    private val journal = mockk<PanicJournal>()
    private lateinit var depot: DepotViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { journal.isPending() } returns false
        coEvery { notes.create(any(), any(), any(), any()) } returns mockk<Note>()
        depot = DepotViewModel(notes, journal)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a drop writes one note to the Inbox, then nothing typed stays on the screen`() {
        depot.titre("  Idée  ")
        depot.texte("Le texte\nsur deux lignes")

        depot.deposer()

        coVerify(exactly = 1) { notes.create(Folder.INBOX_ID, "Idée", "Le texte\nsur deux lignes", any()) }
        confirmVerified(notes)
        assertThat(depot.etat.value).isEqualTo(EtatDuDepot(depose = true))
    }

    @Test
    fun `nothing typed, nothing dropped`() {
        depot.titre("   ")

        depot.deposer()

        assertThat(depot.etat.value.peutDeposer).isFalse()
        coVerify(exactly = 0) { notes.create(any(), any(), any(), any()) }
    }

    /** A panic cut before its end is the app's to finish: the drop must not write into that database. */
    @Test
    fun `while a panic is pending, nothing is written and the text stays`() {
        every { journal.isPending() } returns true
        depot.texte("à garder")

        depot.deposer()

        coVerify(exactly = 0) { notes.create(any(), any(), any(), any()) }
        assertThat(depot.etat.value.echec).isTrue()
        assertThat(depot.etat.value.texte).isEqualTo("à garder")
    }

    @Test
    fun `a refused write keeps the text, to try again`() {
        coEvery { notes.create(any(), any(), any(), any()) } throws IllegalStateException("base fermee")
        depot.texte("à garder")

        depot.deposer()

        assertThat(depot.etat.value).isEqualTo(EtatDuDepot(texte = "à garder", echec = true))
        assertThat(depot.etat.value.peutDeposer).isTrue()
    }

    @Test
    fun `a title or a text beyond a note's limits is not taken`() {
        depot.titre("t".repeat(NotesRepository.TITLE_MAX_LENGTH + 1))
        depot.texte("x".repeat(LongueurDUneNote.CARACTERES_MAX + 1))

        assertThat(depot.etat.value.titre).isEmpty()
        assertThat(depot.etat.value.texte).isEmpty()
    }
}
