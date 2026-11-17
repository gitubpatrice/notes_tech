package com.filestech.notes_tech.domain.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** D-047 (3.5.0): a task box turned over from the reading — and nothing written where there is none. */
@DisplayName("Cases de tâche")
class TaskBoxesTest {

    @Test
    fun `an open box is ticked, a ticked one opened, nothing else moves`() {
        assertThat(TaskBoxes.toggle("- [ ] Lait\n- [x] Pain", 2)).isEqualTo("- [x] Lait\n- [x] Pain")
        assertThat(TaskBoxes.toggle("- [ ] Lait\n- [x] Pain", 13)).isEqualTo("- [ ] Lait\n- [ ] Pain")
        assertThat(TaskBoxes.toggle("- [X] Pain", 2)).isEqualTo("- [ ] Pain")
    }

    @Test
    fun `a box at the very end of the note is turned over`() {
        assertThat(TaskBoxes.toggle("- [ ]", 2)).isEqualTo("- [x]")
    }

    /** The guard: the offset came from a reading that may be one change behind. */
    @Test
    fun `nothing is written where the characters are not a box`() {
        assertThat(TaskBoxes.toggle("- [ ] Lait", 0)).isNull()
        assertThat(TaskBoxes.toggle("- [ ] Lait", 3)).isNull()
        assertThat(TaskBoxes.toggle("- [y] Lait", 2)).isNull()
        assertThat(TaskBoxes.toggle("- [ ", 2)).isNull()
        assertThat(TaskBoxes.toggle("- [ ] Lait", -1)).isNull()
        assertThat(TaskBoxes.toggle("- [ ] Lait", 40)).isNull()
    }

    @Test
    fun `the marks between the brackets`() {
        assertThat(TaskBoxes.markOf(' ')).isEqualTo(TaskMark.OPEN)
        assertThat(TaskBoxes.markOf('x')).isEqualTo(TaskMark.DONE)
        assertThat(TaskBoxes.markOf('X')).isEqualTo(TaskMark.DONE)
        assertThat(TaskBoxes.markOf('y')).isNull()
    }
}
