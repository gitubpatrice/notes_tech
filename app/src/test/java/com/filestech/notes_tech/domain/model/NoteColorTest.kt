package com.filestech.notes_tech.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NoteColor")
class NoteColorTest {

    /**
     * ⚠️ The ids are written in users' databases (`notes.color_id`): renumbering them would recolour
     * every note. Pinned here one by one, so that reordering the enum fails this test, not a user.
     */
    @Test
    fun `the stored ids never change`() {
        assertThat(NoteColor.entries.associate { it.name to it.id }).containsExactly(
            "YELLOW", 1, "ORANGE", 2, "GREEN", 3, "TEAL", 4, "BLUE", 5, "PURPLE", 6, "PINK", 7, "GRAY", 8,
        )
    }

    @Test
    fun `an id is read back as its colour, none as none, and an unknown one as none`() {
        NoteColor.entries.forEach { assertThat(NoteColor.fromId(it.id)).isEqualTo(it) }
        assertThat(NoteColor.fromId(null)).isNull()
        // Written by a later version this one does not know: a neutral card rather than a failure.
        assertThat(NoteColor.fromId(99)).isNull()
        assertThat(NoteColor.fromId(0)).isNull()
    }
}
