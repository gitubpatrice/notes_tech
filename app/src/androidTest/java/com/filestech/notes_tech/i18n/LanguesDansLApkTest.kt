package com.filestech.notes_tech.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.LocalePreference
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Every language the app offers is really IN THE INSTALLED APK.
 *
 * `localeFilters` (app/build.gradle.kts) drops resources at BUILD time. A language missing there keeps
 * its `values-*` and `raw-*` files in the sources — the JVM tests read those and pass — while the APK
 * silently falls back to English: the settings would offer Deutsch and speak English. Pass Tech 2.7.0
 * learned it from `aapt2` on its APK (docs/04-PIEGES.md §161). Only the installed APK can show it, so
 * this test asks the installed APK's resources, language by language.
 *
 * ⚠️ It demands a DIFFERENCE from English, not mere presence: a filtered-out language still resolves,
 * to the English default, so "a string is there" would pass either way.
 */
@RunWith(AndroidJUnit4::class)
class LanguesDansLApkTest {

    private val contexte: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun ressources(langue: String): Resources = contexte
        .createConfigurationContext(
            Configuration(contexte.resources.configuration).apply { setLocale(Locale.forLanguageTag(langue)) },
        )
        .resources

    private fun lire(ressources: Resources, page: Int): String =
        ressources.openRawResource(page).bufferedReader().use { it.readText() }

    @Test
    fun chaque_langue_proposee_a_ses_chaines_et_ses_pages_dans_l_apk() {
        val anglais = ressources("en")
        val traduites = LocalePreference.LANGUES - "en"
        // The witness first: were the list empty, the loop below would prove nothing.
        assertThat(traduites).containsAtLeast("fr", "de", "es", "it")

        for (langue in traduites) {
            val traduction = ressources(langue)
            assertWithMessage("$langue : common_cancel")
                .that(traduction.getString(R.string.common_cancel))
                .isNotEqualTo(anglais.getString(R.string.common_cancel))
            for (page in listOf(R.raw.privacy, R.raw.terms)) {
                assertWithMessage("$langue : ${contexte.resources.getResourceEntryName(page)}")
                    .that(lire(traduction, page))
                    .isNotEqualTo(lire(anglais, page))
            }
        }
    }
}
