package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.EncryptedBody
import com.filestech.notes_tech.domain.model.EncryptedFormat
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the info panel shows, taken from the editor's state (`noteInfoOf`).
 *
 * The case that matters is the vault note: its row carries an empty title and an empty content, the
 * text living in the sealed blob. Counted from the row, a note full of text would report 0 words —
 * and only for the notes the user protects most.
 */
class InfosDeLaNoteTest {

    @Test
    @DisplayName("les comptes viennent du texte a l'ecran, pas de la ligne scellee")
    fun les_comptes_viennent_du_texte_a_l_ecran() {
        val etat = EditorUiState(loading = false, note = scellee(), content = TextFieldValue("Trois mots ici"))

        val infos = noteInfoOf(etat, folderName = "Coffre")

        assertThat(infos).isEqualTo(
            NoteInfo(folderName = "Coffre", createdAt = CREEE, updatedAt = MODIFIEE, words = 3, characters = 14),
        )
    }

    @Test
    @DisplayName("les retours a la ligne ne comptent pas, les espaces si")
    fun les_retours_a_la_ligne_ne_comptent_pas() {
        val etat = EditorUiState(loading = false, note = scellee(), content = TextFieldValue("a b\nc\r\nd"))

        val infos = noteInfoOf(etat, folderName = "Coffre")!!

        assertThat(infos.words).isEqualTo(4)
        // a, the space, b, c, d: the space counts, the three line-break characters do not.
        assertThat(infos.characters).isEqualTo(5)
    }

    /** Nothing loaded, or a note that could not be opened: there is nothing true to show. */
    @Test
    @DisplayName("rien a montrer tant que la note n'est pas lisible")
    fun rien_a_montrer_tant_que_la_note_n_est_pas_lisible() {
        val lisible = EditorUiState(loading = false, note = scellee(), content = TextFieldValue("x"))

        assertThat(noteInfoOf(lisible, "Coffre")).isNotNull()
        assertThat(noteInfoOf(lisible.copy(note = null), "Coffre")).isNull()
        assertThat(noteInfoOf(lisible.copy(loading = true), "Coffre")).isNull()
        val illisible = lisible.copy(loadError = R.string.note_editor_error_vault_folder_missing)
        assertThat(noteInfoOf(illisible, "Coffre")).isNull()
        assertThat(noteInfoOf(lisible.copy(lockedVault = coffre()), "Coffre")).isNull()
    }

    private fun scellee(): Note = Note(
        id = "n",
        title = "",
        content = "",
        folderId = "coffre",
        tags = emptyList(),
        pinned = false,
        favorite = false,
        archived = false,
        trashedAt = null,
        createdAt = CREEE,
        updatedAt = MODIFIEE,
        encrypted = EncryptedBody(byteArrayOf(1, 2, 3)),
        encVersion = EncryptedFormat.TITLE_AND_CONTENT,
    )

    private fun coffre(): Folder = Folder(
        id = "coffre",
        name = "Coffre",
        parentId = null,
        color = null,
        icon = null,
        createdAt = CREEE,
        updatedAt = CREEE,
        vault = null,
    )

    private companion object {
        val CREEE: Instant = Instant.ofEpochMilli(1_700_000_000_000L)
        val MODIFIEE: Instant = Instant.ofEpochMilli(1_700_000_600_000L)
    }
}
