package com.filestech.notes_tech.domain.markdown

/**
 * What the Markdown preview draws, as plain data (D-024).
 *
 * The note is parsed once into this model by [MarkdownPreviewReader], then drawn by
 * `ui/editor/ApercuMarkdown.kt`. Keeping the model free of Compose is what lets every rule that can
 * go wrong — which link is clickable, which text is a `[[Title]]`, what an entity decodes to — be
 * tested on the JVM, one case per line, instead of on a screen.
 */
sealed interface PreviewBlock {

    /** `#` to `######`, or a Setext heading (`===` gives 1, `---` gives 2). */
    data class Heading(val level: Int, val runs: List<TextRun>) : PreviewBlock

    data class Paragraph(val runs: List<TextRun>) : PreviewBlock

    /**
     * @param start the number of the first item of an ordered list — `3.` starts at 3, as written.
     *   Meaningless for a bullet list.
     */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : PreviewBlock

    data class Quote(val blocks: List<PreviewBlock>) : PreviewBlock

    /** A fenced or indented code block, without its fences and its four-space indent. */
    data class Code(val text: String) : PreviewBlock

    /**
     * A GFM table. Every row has exactly `alignments.size` cells: a short row is padded with empty
     * cells, a long one is cut — the column count is the separator row's, as GFM specifies.
     */
    data class Table(
        val alignments: List<CellAlignment>,
        val header: List<List<TextRun>>,
        val rows: List<List<List<TextRun>>>,
    ) : PreviewBlock

    /** `---`, `***`, `___`. */
    data object Rule : PreviewBlock

    /**
     * The whole note, **not parsed**: too long, or shaped so that parsing it would cost too much —
     * see [MarkdownPreviewReader.read] for the limits and what they were measured against. Drawn as
     * written, under a notice that says why, so that the preview still shows every character.
     */
    data class AsWritten(val text: String) : PreviewBlock

    /**
     * A block of raw HTML, shown **as written**.
     *
     * ⚠️ Deliberate gap with notes_tech 2.0.9, whose renderer drops HTML blocks altogether: text the
     * user typed must not vanish from the preview without a trace. And nothing here interprets it —
     * a note is untrusted input.
     */
    data class RawHtml(val text: String) : PreviewBlock
}

/**
 * How many blocks drawing this one lays out at once: itself, and everything it holds. The reader
 * bounds it per lazy item (`MarkdownPreviewReader.MAX_BLOCKS_PER_ITEM`); the recursion is bounded by
 * the reader's depth cap.
 */
internal fun PreviewBlock.weight(): Int = 1 + when (this) {
    is PreviewBlock.Quote -> blocks.sumOf { it.weight() }
    is PreviewBlock.ListBlock -> items.sumOf { item -> 1 + item.blocks.sumOf { it.weight() } }
    is PreviewBlock.Table -> (rows.size + 1) * alignments.size
    is PreviewBlock.Heading, is PreviewBlock.Paragraph, is PreviewBlock.Code, is PreviewBlock.RawHtml,
    is PreviewBlock.AsWritten, PreviewBlock.Rule,
    -> 0
}

/** @param task `null` for an ordinary item; the GFM `[ ]` / `[x]` box otherwise. */
data class ListItem(val task: TaskMark?, val blocks: List<PreviewBlock>)

/** A GFM task box. Shown, never toggled: the preview does not write into the note. */
enum class TaskMark { DONE, OPEN }

enum class CellAlignment { START, CENTER, END }

/**
 * A piece of inline text with one set of styles and at most one link.
 *
 * Adjacent pieces with the same styles and the same link are merged by the reader: a paragraph is
 * as few runs as its formatting allows, which is also what keeps the tests readable.
 */
data class TextRun(val text: String, val styles: Set<RunStyle> = emptySet(), val link: LinkTarget? = null)

enum class RunStyle {
    BOLD,
    ITALIC,
    STRIKETHROUGH,
    CODE,

    /**
     * The text stands for an image: its alternative text, or its address when it has none.
     *
     * ⚠️ Images are **never loaded** — a note is untrusted input and the app has no network. Same
     * rule as notes_tech 2.0.9, whose image builder shows the alt text only.
     */
    IMAGE,
}

sealed interface LinkTarget {

    /**
     * An address another app may open.
     *
     * ⚠️ Only `http`, `https` and `mailto` ever become a [Web] target. Any other scheme — `file`,
     * `content`, `intent`, `javascript`, a relative path — is drawn as plain text, **not** as a link
     * that does nothing when tapped: notes_tech 2.0.9 styles those as links and ignores the tap,
     * which is a control that accepts a gesture without effect.
     */
    data class Web(val url: String) : LinkTarget

    /** A `[[Title]]` link to another note, resolved only when tapped. */
    data class Note(val title: String) : LinkTarget
}
