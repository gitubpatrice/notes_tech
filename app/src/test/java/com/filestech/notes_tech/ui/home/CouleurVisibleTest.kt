package com.filestech.notes_tech.ui.home

import com.filestech.notes_tech.domain.model.EncryptedBody
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.NoteColor
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Option A (Patrice, 2026-10-10): a sealed note shows its colour only while its vault is open. Its card
 * hides its title, excerpt and tags; a colour shown with the vault closed would say what they hide.
 */
@DisplayName("Couleur visible dans une liste")
class CouleurVisibleTest {

    @Test
    fun `an ordinary note shows its colour, vault or not`() {
        assertThat(note(scellee = false).couleurVisible(emptySet())).isEqualTo(NoteColor.GREEN)
    }

    @Test
    fun `a sealed note hides its colour while its vault is closed`() {
        assertThat(note(scellee = true).couleurVisible(emptySet())).isNull()
        assertThat(note(scellee = true).couleurVisible(setOf("un-autre-coffre"))).isNull()
    }

    @Test
    fun `a sealed note shows its colour while its vault is open`() {
        assertThat(note(scellee = true).couleurVisible(setOf(COFFRE))).isEqualTo(NoteColor.GREEN)
    }

    @Test
    fun `no colour stays no colour`() {
        assertThat(note(scellee = false, couleur = null).couleurVisible(setOf(COFFRE))).isNull()
    }

    private fun note(scellee: Boolean, couleur: NoteColor? = NoteColor.GREEN) = Note(
        id = "n1",
        title = if (scellee) "" else "Titre",
        content = if (scellee) "" else "corps",
        folderId = COFFRE,
        tags = emptyList(),
        pinned = false,
        favorite = false,
        archived = false,
        trashedAt = null,
        createdAt = INSTANT,
        updatedAt = INSTANT,
        encrypted = if (scellee) EncryptedBody(ByteArray(32)) else null,
        encVersion = if (scellee) 2 else 1,
        color = couleur,
    )

    private companion object {
        const val COFFRE = "coffre"
        val INSTANT: Instant = Instant.ofEpochMilli(1_760_000_000_000L)
    }
}
