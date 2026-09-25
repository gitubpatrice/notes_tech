package com.filestech.notes_tech.ui.about

import com.filestech.notes_tech.domain.markdown.LinkTarget
import com.filestech.notes_tech.domain.markdown.MarkdownPreviewReader
import com.filestech.notes_tech.domain.markdown.PreviewBlock
import com.filestech.notes_tech.domain.markdown.TextRun
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The four legal pages, read by the note preview's reader, as `LegalScreen` draws them.
 *
 * The screen does nothing with a note link — there is no note to open from a legal page — so none may
 * be there: a `[[…]]` written outside code would be a link that does nothing when tapped. The privacy
 * pages name the feature, in both languages, inside inline code, where it is text.
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

    /** The control of the case above: the privacy pages do write a `[[…]]`, and it stays text. */
    @Test
    fun `the privacy pages write a note link in code, which stays text`() {
        val confidentialite = PAGES.filter { it.name == "privacy.md" }
        assertThat(confidentialite).hasSize(2)

        for (page in confidentialite) {
            assertThat(page.readText()).contains("[[")
        }
    }

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
        val PAGES = listOf("raw", "raw-fr").flatMap { dossier ->
            listOf("privacy.md", "terms.md").map { File("src/main/res/$dossier/$it") }
        }
    }
}
