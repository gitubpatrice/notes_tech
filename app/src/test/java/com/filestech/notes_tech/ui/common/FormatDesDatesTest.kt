package com.filestech.notes_tech.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * The date of the note lists, per language (`formateurDeDate`).
 *
 * The JVM's month abbreviations are not Android's, so the SHAPE is checked, not the month: the day
 * with or without its zero, the German dot, the 24-hour time — and English for a language the app
 * does not speak, as its screens fall back to English. Before, anything but English got French.
 */
class FormatDesDatesTest {

    @Test
    @DisplayName("chaque langue de l'application a la forme de date de sa langue")
    fun chaque_langue_a_sa_forme() {
        assertThat(date(Locale.FRENCH)).startsWith("05 ")
        assertThat(date(Locale.ENGLISH)).endsWith(" 5, 2026 · 19:30")
        assertThat(date(Locale.GERMAN)).startsWith("5. ")
        assertThat(date(Locale.forLanguageTag("es"))).startsWith("5 ")
        assertThat(date(Locale.ITALIAN)).startsWith("5 ")
        for (langue in listOf(Locale.FRENCH, Locale.GERMAN, Locale.forLanguageTag("es"), Locale.ITALIAN)) {
            assertWithMessage(langue.language).that(date(langue)).endsWith(" 2026 · 19:30")
        }
    }

    /** A region does not change the language: Swiss German is German. */
    @Test
    @DisplayName("une region garde la forme de sa langue")
    fun une_region_garde_sa_langue() {
        assertThat(date(Locale.forLanguageTag("de-CH"))).startsWith("5. ")
    }

    /** A Portuguese phone: English screens, so English dates — not French-shaped Portuguese ones. */
    @Test
    @DisplayName("une langue que l'application ne parle pas retombe sur l'anglais, comme les ecrans")
    fun une_langue_inconnue_retombe_sur_l_anglais() {
        assertThat(date(Locale.forLanguageTag("pt-BR"))).isEqualTo(date(Locale.ENGLISH))
        assertThat(date(Locale.forLanguageTag("ja"))).isEqualTo(date(Locale.ENGLISH))
    }

    private fun date(locale: Locale): String = formateurDeDate(locale).withZone(ZoneOffset.UTC).format(INSTANT)

    private companion object {
        /** The 5th: a day of one digit tells "d" from "dd". 19:30: a 12-hour clock would say 07:30. */
        val INSTANT: Instant = Instant.parse("2026-09-05T19:30:00Z")
    }
}
