package com.filestech.notes_tech.ui.editor

import android.app.UiAutomation
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.markdown.LinkTarget
import com.filestech.notes_tech.domain.markdown.MarkdownPreviewReader
import com.filestech.notes_tech.ui.CHAMP_DE_SAISIE
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **What the Markdown preview draws, and what a screen reader receives of it** (D-024).
 *
 * The reader's rules are tested on the JVM (`MarkdownPreviewReaderTest`); this class measures the
 * drawing — and the one thing no JVM test can see: the accessibility tree Android hands to TalkBack.
 *
 * ## 🔴 Two trees, and they disagree about links
 *
 * Measured on the S9 on 2026-09-25. In **Compose's** semantics tree, each link of a text is a child
 * node carrying `OnClick` and **no name** — exactly what [actionnablesSansNom] exists to catch. In
 * **Android's** accessibility tree those nodes do not exist: the text is one `TextView` whose text
 * carries an `AccessibilityClickableSpan` per link, spanning the link's words. That span is how
 * TalkBack lists and opens links, and its words are the link's name.
 *
 * So the sweep's finding is not a defect here — but it is only allowed **exactly** over the links,
 * and only because the other test proves, on the real tree, that their names reach the reader.
 */
@RunWith(AndroidJUnit4::class)
class ApercuMarkdownTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val liens = mutableListOf<LinkTarget>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    /** @param onCase the box handler (D-047); `null`, as on the legal pages, leaves the boxes inert. */
    private fun poser(source: String, onCase: ((Int) -> Unit)? = null) {
        val blocs = MarkdownPreviewReader.read(source)
        regle.setContent {
            NotesTechTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    apercuMarkdown(LectureDeLApercu.Lue(aplatir(blocs)), onCase = onCase) { liens += it }
                }
            }
        }
        regle.waitForIdle()
    }

    /**
     * D-047 (3.5.0): with a box handler, each box is ONE node — toggleable, the checkbox role, named by its
     * task and saying its state, as `04-PIEGES.md` asks of a control with a state — and a tap reports the
     * `[`'s offset in the note. The sweeps stay clean with them on screen.
     */
    @Test
    fun a_box_that_can_be_ticked_is_one_named_checkbox_and_reports_its_offset() {
        val cases = mutableListOf<Int>()
        poser("- [x] bought\n- [ ] to buy", onCase = { cases += it })

        val achetee = regle.onNode(isToggleable() and hasContentDescription("bought"))
        achetee.assertIsOn()
        val dite = texte(R.string.note_preview_task_done)
        achetee.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, dite))
        val aAcheter = regle.onNode(isToggleable() and hasContentDescription("to buy"))
        aAcheter.assertIsOff()
        aAcheter.performClick()

        // "- [x] bought\n" is 13 characters; the second box's `[` is 2 further.
        assertThat(cases).containsExactly(15)
        assertThat(regle.actionnablesSansNom()).isEmpty()
        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    /**
     * 🔴 Seen on the S9 (3.5.0): the menu's "Checklist" keeps a list's numbers in the text (`1. [ ] a`), and
     * the reading dropped them. A numbered task shows its number AND its box; a bulleted one, its box alone.
     */
    @Test
    fun a_numbered_task_keeps_its_number_before_its_box() {
        poser("3. [ ] first\n4. [x] second\n\n- [ ] bulleted", onCase = {})

        regle.onNodeWithText("3.").assertIsDisplayed()
        regle.onNodeWithText("4.").assertIsDisplayed()
        regle.onAllNodes(isToggleable()).assertCountEquals(3)
        // The control: the bulleted task draws no bullet next to its box.
        regle.onNodeWithText("•").assertDoesNotExist()
    }

    /** The link nodes Compose lays over [texteAffiche] — clickable children of its text node. */
    private fun noeudsDeLien(texteAffiche: String) =
        regle.onNode(hasText(texteAffiche), useUnmergedTree = true).onChildren().filter(hasClickAction())

    @Test
    fun a_tapped_link_reports_its_target_and_a_refused_address_is_no_link() {
        poser(PARAGRAPHE)

        // Two links, not three: `[local](page.md)` has no scheme the preview opens, and is text.
        noeudsDeLien(TEXTE_DU_PARAGRAPHE).assertCountEquals(2)
        noeudsDeLien(TEXTE_DU_PARAGRAPHE)[0].performClick()
        noeudsDeLien(TEXTE_DU_PARAGRAPHE)[1].performClick()

        assertThat(liens).containsExactly(LinkTarget.Note("Plan"), LinkTarget.Web("https://exemple.fr")).inOrder()
    }

    /**
     * 🔴 **What TalkBack gets**: the text, with one clickable span per link, over the link's words.
     *
     * "le site" is **one** span although "site" is italic: the renderer merges runs of one target
     * into one link, or a screen reader would announce two links where the user wrote one. And
     * "local" has none — the control that the instrument reads spans at all, and reads the right ones.
     */
    @Test
    fun a_screen_reader_gets_each_link_as_a_clickable_span_over_its_words() {
        poser(PARAGRAPHE)

        val texteAccessible = texteAccessibleContenant("Voir Plan")
        val nommes = texteAccessible.getSpans(0, texteAccessible.length, ClickableSpan::class.java).map { span ->
            texteAccessible.subSequence(texteAccessible.getSpanStart(span), texteAccessible.getSpanEnd(span)).toString()
        }
        assertThat(nommes).containsExactly("Plan", "le site").inOrder()
    }

    /**
     * The three sweeps on a preview holding every interactive thing it can draw: links in a
     * paragraph and in a table cell, task boxes.
     *
     * ⚠️ The unnamed actionables must be **exactly** the link nodes — whose names the test above
     * proves reach the reader — no more, no fewer. A task box is not actionable here: no box handler, as
     * on the legal pages (D-047 — with one, see the test above).
     */
    @Test
    fun the_only_unnamed_actionables_are_the_links_whose_text_names_them() {
        poser("$PARAGRAPHE\n\n- [x] done\n- [ ] open\n\n| A | B |\n|---|---|\n| [[T]] | 2 |")

        val sansNom = regle.actionnablesSansNom()
        val dessousDesLiens: List<Rect> =
            (noeudsDeLien(TEXTE_DU_PARAGRAPHE).fetchSemanticsNodes() + noeudsDeLien("T").fetchSemanticsNodes())
                .map { it.boundsInRoot }
        assertThat(dessousDesLiens).hasSize(3)
        assertThat(sansNom).containsExactlyElementsIn(dessousDesLiens)

        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
        // No field in a preview: calling the field sweep here would be the empty assertion itself.
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun headings_are_headings_and_a_task_box_says_its_state() {
        poser("# Title one\n\nText.\n\n- [x] bought\n- [ ] to buy")

        regle.onNode(hasText("Title one") and isHeading()).assertExists()
        // The control: an ordinary paragraph is no heading.
        regle.onNode(hasText("Text.") and isHeading()).assertDoesNotExist()
        regle.onNodeWithContentDescription(texte(R.string.note_preview_task_done)).assertExists()
        regle.onNodeWithContentDescription(texte(R.string.note_preview_task_open)).assertExists()
    }

    @Test
    fun a_note_not_parsed_says_why_above_its_text() {
        val tropImbriquee = ">".repeat(MarkdownPreviewReader.MAX_LINE_NESTING + 1) + " x"
        poser(tropImbriquee)

        regle.onNodeWithText(texte(R.string.note_preview_as_written)).assertIsDisplayed()
        regle.onNodeWithText(tropImbriquee).assertIsDisplayed()
    }

    /**
     * The text of the first node of Android's accessibility tree whose text contains [debut] — what a
     * screen reader reads, spans included. Waits for the tree: `rootInActiveWindow` lags behind the
     * composition by a few frames.
     */
    private fun texteAccessibleContenant(debut: String): Spanned {
        val automate = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val echeance = SystemClock.uptimeMillis() + ATTENTE_DE_L_ARBRE_MS
        while (SystemClock.uptimeMillis() < echeance) {
            val trouve = chercher(automate.rootInActiveWindow, debut)
            if (trouve != null) return trouve
            SystemClock.sleep(PAS_D_ATTENTE_MS)
        }
        error("no accessible text contains \"$debut\"")
    }

    private fun chercher(noeud: AccessibilityNodeInfo?, debut: String): Spanned? {
        if (noeud == null) return null
        val texteDuNoeud = noeud.text
        if (texteDuNoeud is Spanned && texteDuNoeud.contains(debut)) return texteDuNoeud
        return (0 until noeud.childCount).firstNotNullOfOrNull { chercher(noeud.getChild(it), debut) }
    }

    private companion object {
        const val PARAGRAPHE = "Voir [[Plan]] et [le *site*](https://exemple.fr) et [local](page.md)."
        const val TEXTE_DU_PARAGRAPHE = "Voir Plan et le site et local."
        const val ATTENTE_DE_L_ARBRE_MS = 5_000L
        const val PAS_D_ATTENTE_MS = 100L
    }
}
