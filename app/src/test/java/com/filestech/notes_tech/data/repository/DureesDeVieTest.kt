package com.filestech.notes_tech.data.repository

import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.NotesDatabase
import com.filestech.notes_tech.data.local.dao.NoteWriteDao
import com.filestech.notes_tech.domain.model.DureeDeVie
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** What a lifetime writes (D-043, 3.4.0) — the purge itself runs on a real base in `MigrationVersDixTest`. */
@DisplayName("Durées de vie")
class DureesDeVieTest {

    private val maintenant = Instant.parse("2026-11-09T20:00:00Z")
    private val ecriture = mockk<NoteWriteDao>()
    private lateinit var durees: DureesDeVie

    @BeforeEach
    fun setUp() {
        val base = mockk<NotesDatabase> { every { noteWriteDao() } returns ecriture }
        val bases = mockk<DatabaseProvider> { coEvery { get() } returns base }
        coEvery { ecriture.updateLifetime(any(), any(), any()) } returns 1
        coEvery { ecriture.deleteExpired(any()) } returns 2
        durees = DureesDeVie(bases, Clock.fixed(maintenant, ZoneOffset.UTC))
    }

    @Test
    fun `a duration counts from now, without the reading flag`() = runTest {
        assertThat(durees.poser("n", DureeDeVie.UNE_HEURE)).isTrue()
        durees.poser("n", DureeDeVie.TRENTE_JOURS)

        coVerify { ecriture.updateLifetime("n", maintenant.plus(Duration.ofHours(1)).toEpochMilli(), false) }
        coVerify { ecriture.updateLifetime("n", maintenant.plus(Duration.ofDays(30)).toEpochMilli(), false) }
    }

    @Test
    fun `after reading has no date, only its flag`() = runTest {
        durees.poser("n", DureeDeVie.APRES_LECTURE)

        coVerify { ecriture.updateLifetime("n", null, true) }
    }

    @Test
    fun `none clears both`() = runTest {
        durees.poser("n", null)

        coVerify { ecriture.updateLifetime("n", null, false) }
    }

    @Test
    fun `the purge erases what is over at now, and says how many`() = runTest {
        assertThat(durees.purger()).isEqualTo(2)

        coVerify { ecriture.deleteExpired(maintenant.toEpochMilli()) }
    }

    @Test
    fun `an unknown note is not written`() = runTest {
        coEvery { ecriture.updateLifetime("absente", any(), any()) } returns 0

        assertThat(durees.poser("absente", DureeDeVie.UN_JOUR)).isFalse()
    }
}
