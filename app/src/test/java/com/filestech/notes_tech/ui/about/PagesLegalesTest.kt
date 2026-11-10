package com.filestech.notes_tech.ui.about

import com.filestech.notes_tech.data.prefs.LocalePreference
import com.filestech.notes_tech.domain.markdown.LinkTarget
import com.filestech.notes_tech.domain.markdown.MarkdownPreviewReader
import com.filestech.notes_tech.domain.markdown.PreviewBlock
import com.filestech.notes_tech.domain.markdown.TextRun
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The legal pages of every language of the app, read by the note preview's reader, as `LegalScreen`
 * draws them.
 *
 * The screen does nothing with a note link — there is no note to open from a legal page — so none may
 * be there: a `[[…]]` written outside code would be a link that does nothing when tapped. The privacy
 * pages name the feature, in every language, inside inline code, where it is text.
 */
class PagesLegalesTest {

    @Test
    fun `each legal page is read as Markdown, and holds no note link`() {
        // The files first: without them, this would prove nothing.
        assertThat(PAGES.filterNot(File::isFile)).isEmpty()

        for (page in PAGES) {
            val blocs = MarkdownPreviewReader.read(page.readText())

            assertThat(blocs.filterIsInstance<PreviewBlock.AsWritten>()).isEmpty()
            assertThat(liens(blocs).filterIsInstance<LinkTarget.Note>()).isEmpty()
        }
    }

    /**
     * 🔴 **No paragraph is wrapped at a width** (issue #57, D-045). The reader keeps every line end as
     * a line break, as a note needs; a paragraph cut at 100 columns — the MIT notice of whisper.cpp
     * was, until 3.4.0 — would come out cut short on every line of a phone's screen. The only lines a
     * paragraph may hold are the notice's three copyright lines, which are lines in the licence too.
     */
    @Test
    fun `no paragraph of a legal page is wrapped at a width — only the copyright lines are lines`() {
        for (page in PAGES) {
            val multilignes = paragraphes(MarkdownPreviewReader.read(page.readText()))
                .map { runs -> runs.joinToString("") { it.text } }
                .filter { '\n' in it }

            val attendus = if (page.name == "terms.md") listOf(COPYRIGHTS) else emptyList()
            assertWithMessage(page.path).that(multilignes).isEqualTo(attendus)
        }
    }

    /** Every paragraph's runs, quotes and lists included — where a wrapped line would show. */
    private fun paragraphes(blocs: List<PreviewBlock>): List<List<TextRun>> = blocs.flatMap { bloc ->
        when (bloc) {
            is PreviewBlock.Paragraph -> listOf(bloc.runs)
            is PreviewBlock.ListBlock -> bloc.items.flatMap { paragraphes(it.blocks) }
            is PreviewBlock.Quote -> paragraphes(bloc.blocks)
            is PreviewBlock.Heading, is PreviewBlock.Table, is PreviewBlock.Code, is PreviewBlock.AsWritten,
            is PreviewBlock.RawHtml, PreviewBlock.Rule,
            -> emptyList()
        }
    }

    /** The control of the case above: the privacy pages do write a `[[…]]`, and it stays text. */
    @Test
    fun `the privacy pages write a note link in code, which stays text`() {
        val confidentialite = PAGES.filter { it.name == "privacy.md" }
        assertThat(confidentialite).hasSize(LocalePreference.LANGUES.size)

        for (page in confidentialite) {
            assertThat(page.readText()).contains("[[")
        }
    }

    /**
     * The French text is the original, and the one that prevails (2026-09-25: the first legal texts,
     * v0.7.0, were written in French; the English came two days later, and German, Spanish and Italian
     * are translations nobody has reviewed yet). Every language keeps the outline of the French text:
     * a section a translation loses goes unseen otherwise — its own "Language" section first of all,
     * the one that says which version prevails.
     */
    @Test
    fun `every language has the outline of the French text, which says it prevails`() {
        for (nom in listOf("privacy.md", "terms.md")) {
            val original = File("src/main/res/raw-fr/$nom")
            assertThat(original.readText()).contains("**la version française fait foi**")

            for (page in PAGES.filter { it.name == nom }) {
                assertWithMessage(page.path).that(niveauxDesTitres(page)).isEqualTo(niveauxDesTitres(original))
            }
        }
    }

    /** The level of each heading, in order: the outline of a page, whatever its language. */
    private fun niveauxDesTitres(page: File): List<Int> =
        page.readLines().filter { it.startsWith("#") }.map { ligne -> ligne.takeWhile { it == '#' }.length }

    private fun liens(blocs: List<PreviewBlock>): List<LinkTarget> = blocs.flatMap { bloc ->
        when (bloc) {
            is PreviewBlock.Heading -> bloc.runs.mapNotNull(TextRun::link)
            is PreviewBlock.Paragraph -> bloc.runs.mapNotNull(TextRun::link)
            is PreviewBlock.ListBlock -> bloc.items.flatMap { liens(it.blocks) }
            is PreviewBlock.Quote -> liens(bloc.blocks)
            is PreviewBlock.Table -> (listOf(bloc.header) + bloc.rows).flatten().flatten().mapNotNull(TextRun::link)
            is PreviewBlock.Code, is PreviewBlock.AsWritten, is PreviewBlock.RawHtml, PreviewBlock.Rule -> emptyList()
        }
    }

    private companion object {
        /** Gradle runs the JVM tests from the module's directory. */
        val PAGES = LocalePreference.LANGUES.map { if (it == "en") "raw" else "raw-$it" }.flatMap { dossier ->
            listOf("privacy.md", "terms.md").map { File("src/main/res/$dossier/$it") }
        }

        /** The three copyright lines of the MIT notice, one under the other as in the licence. */
        const val COPYRIGHTS = "Copyright (c) 2023-2024 The ggml authors\n" +
            "Copyright (C) 2024 Intel Corporation\n" +
            "Copyright (c) 2023 Jeffrey Quesnelle and Bowen Peng"
    }
}
