package com.filestech.notes_tech.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The backup code as TalkBack reads it: spelled (audit 3 axes of 3.2.0, U2 — it was read as words). */
class CodeEpeleTest {

    @Test
    fun `the code is spelled sign by sign, a pause between groups`() {
        assertThat(codeEpele("K7QM-T2XB")).isEqualTo("K, 7, Q, M. T, 2, X, B")
    }
}
