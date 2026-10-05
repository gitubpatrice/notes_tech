package com.filestech.notes_tech.ui.editor

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.CHAMP_DE_SAISIE
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.util.Locale

/**
 * The info panel (`NoteInfoDialog`), which had no test: one node per line, numbers grouped in the
 * reader's language, its last line reachable at a large font, and this repository's accessibility
 * sweeps. What it counts is tested on the JVM (`InfosDeLaNoteTest`).
 */
@RunWith(AndroidJUnit4::class)
class PanneauInfosTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val fermetures = mutableListOf<Unit>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    private fun poser(langue: Locale? = null, echelle: Float? = null) {
        regle.setContent {
            val configuration = Configuration(LocalConfiguration.current)
            if (langue != null) configuration.setLocale(langue)
            val densite = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(densite.density, echelle ?: densite.fontScale),
            ) {
                NotesTechTheme {
                    NoteInfoDialog(info = INFOS, onDismiss = { fermetures += Unit })
                }
            }
        }
        regle.waitForIdle()
    }

    /** "Words, 1,234" in one breath: label and value are ONE node, not two unrelated fragments. */
    @Test
    fun each_line_is_one_node_label_and_value() {
        poser(langue = Locale.US)

        val ligne = regle.onNode(hasText(texte(R.string.note_info_words)) and hasText("1,234")).fetchSemanticsNode()

        assertThat(ligne.config[SemanticsProperties.Text].map { it.text })
            .containsExactly(texte(R.string.note_info_words), "1,234").inOrder()
    }

    /** Grouped in the reader's language, never "1234": the separator comes with the locale. */
    @Test
    fun numbers_are_grouped_in_the_readers_language() {
        poser(langue = Locale.FRENCH)
        val separateur = DecimalFormatSymbols.getInstance(Locale.FRENCH).groupingSeparator

        regle.onNodeWithText("1${separateur}234").assertExists()
        regle.onNodeWithText("56${separateur}789").assertExists()
        regle.onNodeWithText("1,234").assertDoesNotExist()
    }

    /** At twice the font size, the last line scrolls into view, and the close button stays on screen. */
    @Test
    fun at_a_large_font_the_last_line_scrolls_into_view() {
        poser(echelle = 2f)

        regle.onNodeWithText(texte(R.string.note_info_characters_hint)).performScrollTo().assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.common_close)).assertIsDisplayed()
    }

    @Test
    fun close_closes_once() {
        poser()

        regle.onNodeWithText(texte(R.string.common_close)).performClick()
        regle.waitForIdle()

        assertThat(fermetures).hasSize(1)
    }

    /**
     * The sweeps find nothing — and have something to sweep: the close button is the one actionable,
     * and there is no field. A sweep over nothing would pass whatever the screen does.
     */
    @Test
    fun the_accessibility_sweeps_find_nothing() {
        poser()

        assertThat(regle.onAllNodes(hasClickAction()).fetchSemanticsNodes()).hasSize(1)
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).isEmpty()
        assertThat(regle.actionnablesSansNom()).isEmpty()
        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    private companion object {
        val INFOS = NoteInfo(
            folderName = "Inbox",
            createdAt = Instant.ofEpochMilli(1_700_000_000_000L),
            updatedAt = Instant.ofEpochMilli(1_700_000_600_000L),
            words = 1234,
            characters = 56_789,
        )
    }
}
