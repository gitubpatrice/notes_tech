package com.filestech.notes_tech.ui.startup

import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.NotesDatabase
import com.filestech.notes_tech.security.panic.PanicJournal
import com.filestech.notes_tech.security.panic.PanicService
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
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
 * 🔴 **A panic cut before its end is finished before the database is opened** — security audit of
 * 2026-09-26, P2 (second half). Opened first, a database whose key the panic had already destroyed
 * led to the failure screen saying the notes were intact.
 */
class StartupViewModelTest {

    private val base = mockk<DatabaseProvider>()
    private val journal = mockk<PanicJournal>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { base.get() } returns mockk<NotesDatabase>()
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    @DisplayName("a panic left pending: nothing is opened, the panic is resumed")
    fun pending_panic_is_resumed() {
        every { journal.isPending() } returns true

        val demarrage = StartupViewModel(base, journal)

        assertThat(demarrage.state.value).isEqualTo(StartupState.ResumingPanic)
        coVerify(exactly = 0) { base.get() }
    }

    @Test
    @DisplayName("the control: no panic left, the database opens as before")
    fun no_pending_panic_opens_the_database() {
        every { journal.isPending() } returns false

        val demarrage = StartupViewModel(base, journal)

        assertThat(demarrage.state.value).isEqualTo(StartupState.Ready)
        coVerify(exactly = 1) { base.get() }
    }

    @Test
    @DisplayName("the flag survives the preferences step of the panic, which keeps a whitelist")
    fun the_flag_is_kept_by_the_preferences_step() {
        assertThat(PanicService.PREFERENCES_CONSERVEES).contains(PanicJournal.KEY)
    }
}
