package com.filestech.notes_tech.ui.home

import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.ui.common.MOTIFS_DE_DATE
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** The archive's month headings (3.3.0, Patrice 2026-11-09): which month, by which date, said how. */
@DisplayName("Intertitres de mois de l'archive")
class LignesDeLArchiveTest {

    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `a heading comes before each month, by the date the sort orders by`() {
        val notes = listOf(
            note("a", modifiee = "2026-11-08T10:00:00Z", creee = "2024-03-01T10:00:00Z"),
            note("b", modifiee = "2026-11-01T10:00:00Z", creee = "2026-11-01T10:00:00Z"),
            note("c", modifiee = "2026-10-31T23:00:00Z", creee = "2025-12-24T10:00:00Z"),
        )

        assertThat(lignesDeLArchive(notes, NoteSortMode.UPDATED_DESC, utc).map { it.cle })
            .containsExactly("mois-2026-11", "a", "b", "mois-2026-10", "c").inOrder()
        // The "created" orders group by creation — here the list given in that order.
        val parCreation = notes.sortedByDescending { it.createdAt }
        assertThat(lignesDeLArchive(parCreation, NoteSortMode.CREATED_DESC, utc).map { it.cle })
            .containsExactly("mois-2026-11", "b", "mois-2025-12", "c", "mois-2024-03", "a").inOrder()
    }

    /** The month is the phone's: 23:30 UTC on 31 October is already November in Paris. */
    @Test
    fun `the month is read in the phone's time zone`() {
        val note = note("a", modifiee = "2026-10-31T23:30:00Z")

        assertThat(lignesDeLArchive(listOf(note), NoteSortMode.UPDATED_DESC, utc).first())
            .isEqualTo(LigneDeLArchive.Mois(YearMonth.of(2026, 10)))
        assertThat(lignesDeLArchive(listOf(note), NoteSortMode.UPDATED_DESC, ZoneId.of("Europe/Paris")).first())
            .isEqualTo(LigneDeLArchive.Mois(YearMonth.of(2026, 11)))
    }

    @Test
    fun `a sort by title has no dates to group by, so no heading`() {
        val notes = listOf(note("a", modifiee = "2026-11-08T10:00:00Z"), note("b", modifiee = "2025-01-08T10:00:00Z"))

        for (tri in listOf(NoteSortMode.TITLE_ASC, NoteSortMode.TITLE_DESC)) {
            assertThat(lignesDeLArchive(notes, tri, utc).map { it.cle }).containsExactly("a", "b").inOrder()
        }
    }

    @Test
    fun `an empty archive has no line`() {
        assertThat(lignesDeLArchive(emptyList(), NoteSortMode.UPDATED_DESC, utc)).isEmpty()
    }

    @Test
    fun `the heading is the month and the year, capitalised, in each language of the app`() {
        val novembre = YearMonth.of(2026, 11)

        assertThat(formateurDeMois(Locale.ENGLISH)(novembre)).isEqualTo("November 2026")
        assertThat(formateurDeMois(Locale.FRENCH)(novembre)).isEqualTo("Novembre 2026")
        assertThat(formateurDeMois(Locale.GERMAN)(novembre)).isEqualTo("November 2026")
        assertThat(formateurDeMois(Locale.ITALIAN)(novembre)).isEqualTo("Novembre 2026")
        assertThat(formateurDeMois(Locale.forLanguageTag("es"))(novembre)).isEqualTo("Noviembre de 2026")
        // A language the app does not speak gets English, as the cards' dates do.
        assertThat(formateurDeMois(Locale.forLanguageTag("pt"))(novembre)).isEqualTo("November 2026")
    }

    @Test
    fun `one month pattern per language of the app, the same languages as the dates`() {
        assertThat(MOTIFS_DE_MOIS.keys).isEqualTo(MOTIFS_DE_DATE.keys)
    }

    /**
     * The archive leaves the pin out of its order (`NoteDao.observeOutOfTrash`): that only works if each
     * order begins with the pin — checked, or `removePrefix` would silently keep it.
     */
    @Test
    fun `every order begins with the pin, so the archive's order is the same without it`() {
        for (tri in NoteSortMode.entries) {
            assertThat(tri.orderBy).startsWith("pinned DESC, ")
            assertThat(tri.orderByIgnoringPin).doesNotContain("pinned")
            assertThat(tri.orderBy).endsWith(tri.orderByIgnoringPin)
        }
    }

    private fun note(id: String, modifiee: String, creee: String = modifiee) = Note(
        id = id,
        title = "Titre $id",
        content = "corps",
        folderId = "inbox",
        tags = emptyList(),
        pinned = false,
        favorite = false,
        archived = true,
        trashedAt = null,
        createdAt = Instant.parse(creee),
        updatedAt = Instant.parse(modifiee),
        encrypted = null,
        encVersion = 1,
    )
}
