package com.filestech.notes_tech.ui.about

import com.filestech.notes_tech.data.prefs.LocalePreference
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The "Support the project" link of About (3.1.1): the donation page in the language shown when
 * files-tech.com publishes it — French and German — the English one for every other language.
 */
class PageDeDonsTest {

    @Test
    fun `French and German open their own page`() {
        assertThat(urlPageDeDons("fr")).isEqualTo("https://www.files-tech.com/soutenir.php")
        assertThat(urlPageDeDons("de")).isEqualTo("https://www.files-tech.com/de/soutenir.php")
    }

    @Test
    fun `every other language of the app, and an unknown one, opens the English page`() {
        val autres = LocalePreference.entries.mapNotNull { it.code } - setOf("fr", "de")
        // The app's languages first: without them, this would prove nothing.
        assertThat(autres).containsAtLeast("en", "it", "es")
        (autres + listOf("", "pt")).forEach {
            assertThat(urlPageDeDons(it)).isEqualTo("https://www.files-tech.com/en/soutenir.php")
        }
    }
}
