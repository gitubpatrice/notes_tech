package com.filestech.notes_tech.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** What a card says of a note's lifetime (D-043, 3.4.0). */
@DisplayName("Reste de vie d'une note")
class ResteDeVieTest {

    private val maintenant = Instant.parse("2026-11-09T20:00:00Z")

    private fun dans(duree: Duration) = resteDeVie(maintenant, maintenant.plus(duree), lectureUnique = false)

    @Test
    fun `no lifetime, nothing to say`() {
        assertThat(resteDeVie(maintenant, null, lectureUnique = false)).isNull()
    }

    @Test
    fun `after reading wins over a date`() {
        assertThat(resteDeVie(maintenant, maintenant.plusSeconds(60), lectureUnique = true))
            .isEqualTo(ResteDeVie.ApresLecture)
    }

    @Test
    fun `rounded up, never less than what is left`() {
        assertThat(dans(Duration.ofSeconds(1))).isEqualTo(ResteDeVie.Minutes(1))
        assertThat(dans(Duration.ofMinutes(59).plusSeconds(30))).isEqualTo(ResteDeVie.Heures(1))
        assertThat(dans(Duration.ofMinutes(59))).isEqualTo(ResteDeVie.Minutes(59))
        assertThat(dans(Duration.ofHours(1))).isEqualTo(ResteDeVie.Heures(1))
        assertThat(dans(Duration.ofHours(23).plusMinutes(1))).isEqualTo(ResteDeVie.Heures(24))
    }

    @Test
    fun `hours below two days, days beyond`() {
        assertThat(dans(Duration.ofHours(47))).isEqualTo(ResteDeVie.Heures(47))
        assertThat(dans(Duration.ofHours(48))).isEqualTo(ResteDeVie.Jours(2))
        assertThat(dans(Duration.ofDays(7))).isEqualTo(ResteDeVie.Jours(7))
        assertThat(dans(Duration.ofDays(30))).isEqualTo(ResteDeVie.Jours(30))
    }

    @Test
    fun `the time is up, the purge has not run yet`() {
        assertThat(dans(Duration.ZERO)).isEqualTo(ResteDeVie.Bientot)
        assertThat(dans(Duration.ofMinutes(-5))).isEqualTo(ResteDeVie.Bientot)
    }
}
