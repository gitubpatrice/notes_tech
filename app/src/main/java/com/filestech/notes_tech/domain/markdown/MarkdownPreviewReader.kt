package com.filestech.notes_tech.domain.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.entities.Entities
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns a note's Markdown into what the preview draws (D-024).
 *
 * The GFM parser is JetBrains' (`org.jetbrains:markdown`); everything after the parse is here, and
 * mirrors what notes_tech 2.0.9 shows through `flutter_markdown_plus` with its GitHub-flavoured
 * extension set: headings, paragraphs, lists and task boxes, quotes, code, tables, rules; bold,
 * italic, strikethrough, inline code, links. The deliberate gaps are named where they are made.
 *
 * ## Only the library's parser is used, never its HTML side
 *
 * Its `EntityConverter` produces **HTML-escaped** text (`&` comes out as `&amp;`) and truncates
 * code points above U+FFFF, so an emoji written `&#x1F600;` would come out wrong; its link
 * normalisation goes through the same converter. Text, entities and escapes are therefore decoded
 * by [decode], written for plain text. The named-entity table is the library's own.
 *
 * ## Bounded, whatever the note holds
 *
 * A note is untrusted input — an import, a paste — and has no size limit. Measured on the JVM on
 * 2026-09-25, the parser is linear on ordinary text (260 KB of paragraphs: 133 ms; 5 000 links:
 * 78 ms; a 5 000-item task list: 146 ms) but **not** on some shapes:
 *
 * | Shape | n = 5 000 | n = 10 000 |
 * |---|---|---|
 * | `[a](` never closed, in one block | 1.4 s | **8.0 s** |
 * | `![` | 1.1 s | 3.4 s |
 * | `[` nested `]` | 0.5 s | 1.8 s |
 * | `>` or `- ` repeated on one line | 0.1 s | 0.4 s — 50 000 `>`: **OutOfMemoryError** |
 * | 3 000 lines of 64 `- ` each | 2.0 s | — |
 *
 * The cost of the brackets depends on how many a block holds, not on the text around them: 400 KB of
 * words after a `[a](` cost 1 ms.
 *
 * The parser can be cancelled, but only **between** its passes — never inside the one pass over a
 * block where the cost above is spent. So the defence is not to start such a pass: [read] scans the
 * note first, in one linear pass ([tooCostly]), and shows it [PreviewBlock.AsWritten] past any of
 * the limits below. The tree walk is bounded too: past [MAX_DEPTH] nested containers the rest is
 * shown as source text, and link definitions are gathered without recursion. A preview must not be
 * the way a note freezes or crashes the editor holding it.
 */
object MarkdownPreviewReader {

    /**
     * Nested lists and quotes followed this deep; deeper, a container is shown as source text. Only
     * lists nested by indentation get here: markers on one line stop at [MAX_LINE_NESTING] first.
     */
    const val MAX_DEPTH = 12

    /** Characters. 500 KB of ordinary text parses in about 250 ms on the JVM. */
    const val MAX_LENGTH = 500_000

    /**
     * `>` and list markers opening one line. A real note stacks two or three (`> - [ ] …`); nesting
     * deeper is written with indentation, which costs one marker per line. Measured at 8, the worst
     * the size cap allows — every line eight markers deep — stays near 300 ms on the JVM.
     */
    const val MAX_LINE_NESTING = 8

    /**
     * `[` in one block of lines between blank lines, and in the whole note: 10 blocks of 300 unclosed
     * `[a](` cost 168 ms. Counted after [WikiLinkMask] — `[[Title]]` links, the note's own way of
     * linking, do not count — and without task boxes: a checklist is a block where every line opens
     * with `[ ]`, and it costs nothing.
     */
    const val MAX_BRACKETS_PER_BLOCK = 300
    const val MAX_BRACKETS = 3_000

    /** Wider, a table is shown as written: its cells would be a few pixels wide anyway. */
    const val MAX_TABLE_COLUMNS = 32

    /** Characters of a link's address; longer, it is text — see [webTarget]. */
    const val MAX_LINK_LENGTH = 8_192

    /**
     * Blocks drawn at once in one item of the preview's lazy list — see [PreviewBlock.weight].
     *
     * The list is lazy per top-level block, per item of a top-level list, per row of a top-level
     * table; what one of them holds is laid out whole. A quote of 50 000 paragraphs, or a list item
     * holding a sub-list of 50 000 items, would lay out 50 000 texts in one go (GPT-5.6 review,
     * 2026-09-25). Heavier than this, the item is shown as written.
     */
    const val MAX_BLOCKS_PER_ITEM = 500

    /**
     * ⚠️ **Never throws — except to cancel.** The preview reads in a coroutine nothing else watches:
     * an exception there crashes the editor that holds the note. Should the parse fail anyway — a
     * defect of the library the limits above do not foresee — the note is shown as written, like a
     * note past the limits.
     *
     * @param checkCancelled called by the parser between its passes; it cancels the reading by
     *   throwing a [CancellationException] — the preview's coroutine passes its own `ensureActive`, so
     *   leaving the preview stops a long parse instead of letting it run to the end for nothing.
     *   🔴 That exception is a `RuntimeException`, and it is **rethrown**: caught with the others, a
     *   cancellation would come back as a note "too complex to preview".
     */
    fun read(source: String, checkCancelled: () -> Unit = {}): List<PreviewBlock> = try {
        parse(source, checkCancelled)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: StackOverflowError) {
        listOf(PreviewBlock.AsWritten(source))
    } catch (_: RuntimeException) {
        listOf(PreviewBlock.AsWritten(source))
    }

    private fun parse(source: String, checkCancelled: () -> Unit): List<PreviewBlock> {
        if (source.length > MAX_LENGTH) return listOf(PreviewBlock.AsWritten(source))
        val mask = WikiLinkMask.mask(lineFeedsOnly(source))
        if (tooCostly(mask.text)) return listOf(PreviewBlock.AsWritten(source))
        val parser = MarkdownParser(GFMFlavourDescriptor(), assertionsEnabled = true, CancellationToken(checkCancelled))
        val tree = parser.buildMarkdownTreeFromString(mask.text as CharSequence)
        return Reading(mask, definitionsOf(tree, mask)).blocks(tree.children, depth = 0)
    }

    /**
     * [source] with its line endings as the parser knows them: `\r\n` and a lone `\r` become `\n`.
     *
     * CommonMark counts all three as line endings; JetBrains' parser only `\n`. A `\r` stayed in the
     * text, and a line holding nothing else was not blank to it: a quote's `>` lines no longer split
     * its paragraphs, blank lines no longer split blocks — the whole MIT licence of the terms of use
     * came out as one paragraph, git giving the file CRLF endings on Windows (2026-09-25). A note
     * imported from a Windows file has them too, and 2.0.9's parser handles them. No copy when there
     * is no `\r`, which is the usual note.
     */
    internal fun lineFeedsOnly(source: String): String =
        if (source.indexOf('\r') < 0) source else source.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * Whether parsing [masked] would cost too much — see the table above. One pass, line by line:
     * the container markers opening each line, then the `[` it holds, a task box aside.
     */
    internal fun tooCostly(masked: String): Boolean {
        var total = 0
        var inBlock = 0
        var start = 0
        while (true) {
            val end = masked.indexOf('\n', start).let { if (it < 0) masked.length else it }
            val (markers, afterMarkers) = containerMarkers(masked, start, end)
            if (markers > MAX_LINE_NESTING) return true
            // ⚠️ Blank as the parser means it — spaces and tabs only — not as `isBlank` does: a line
            // of no-break spaces does NOT end the parser's block, and counting it as a boundary would
            // let one block of thousands of brackets pass as many small ones. Nor does a `\r`, which
            // this counted as blank until 2026-09-25: the same gap, closed by [lineFeedsOnly] upstream.
            if ((start until end).all { masked[it] == ' ' || masked[it] == '\t' }) {
                inBlock = 0
            } else {
                val from = if (markers > 0) afterTaskBox(masked, afterMarkers, end) else afterMarkers
                val brackets = (from until end).count { masked[it] == '[' }
                inBlock += brackets
                total += brackets
                if (inBlock > MAX_BRACKETS_PER_BLOCK || total > MAX_BRACKETS) return true
            }
            if (end == masked.length) return false
            start = end + 1
        }
    }

    /** The most container markers opening a single line of [source]. */
    internal fun lineNesting(source: String): Int {
        var deepest = 0
        var start = 0
        while (true) {
            val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
            deepest = maxOf(deepest, containerMarkers(source, start, end).first)
            if (end == source.length) return deepest
            start = end + 1
        }
    }

    /**
     * The container markers opening the line `[start, end)`: `>`, and `-` `*` `+` or `1.` `1)` followed
     * by a space. Spaces between markers are skipped, as the parser skips them.
     *
     * @return how many, and where the text after them starts.
     */
    private fun containerMarkers(source: String, start: Int, end: Int): Pair<Int, Int> {
        var markers = 0
        var at = start
        while (true) {
            while (at < end && (source[at] == ' ' || source[at] == '\t')) at++
            val next = containerMarkerEnd(source, at, end) ?: return markers to at
            markers++
            at = next
        }
    }

    /** After `[ ]`, `[x]` or `[X]` — a GFM task box — if the text at [at] opens with one; else [at]. */
    private fun afterTaskBox(source: String, at: Int, end: Int): Int {
        var box = at
        while (box < end && (source[box] == ' ' || source[box] == '\t')) box++
        val candidate = source.substring(box, minOf(box + TASK_BOX_LENGTH, end))
        return if (candidate in TASK_BOXES) box + TASK_BOX_LENGTH else at
    }

    /** Where a container marker starting at [at] ends, or `null` if none starts there. */
    private fun containerMarkerEnd(source: String, at: Int, end: Int): Int? {
        if (at >= end) return null
        val first = source[at]
        if (first == '>') return at + 1
        if (first in BULLETS && spaceOrEnd(source, at + 1, end)) return at + 1
        var digits = at
        while (digits < end && digits - at < MAX_LIST_NUMBER_DIGITS && source[digits].isDigit()) digits++
        val numbered = digits > at && digits < end && (source[digits] == '.' || source[digits] == ')')
        return if (numbered && spaceOrEnd(source, digits + 1, end)) digits + 1 else null
    }

    private fun spaceOrEnd(source: String, at: Int, end: Int): Boolean =
        at >= end || source[at] == ' ' || source[at] == '\t'

    private const val MAX_LIST_NUMBER_DIGITS = 9
    private const val BULLETS = "-*+"
    private const val TASK_BOX_LENGTH = 3
    private val TASK_BOXES = setOf("[ ]", "[x]", "[X]")

    /**
     * `[label]: destination` definitions, first one wins (CommonMark), keyed like the library keys
     * them — whitespace collapsed, lower-cased.
     *
     * ⚠️ Walked with an explicit stack: the library's `RecursiveVisitor` would follow the tree to its
     * full depth, which a crafted note makes thousands of levels deep.
     */
    private fun definitionsOf(root: ASTNode, mask: WikiLinkMask): Map<String, String> {
        val definitions = HashMap<String, String>()
        val pending = ArrayDeque<ASTNode>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
                val label = node.child(MarkdownElementTypes.LINK_LABEL)
                val destination = node.child(MarkdownElementTypes.LINK_DESTINATION)
                if (label != null && destination != null) {
                    val key = labelKey(mask.restore(label.getTextInNode(mask.text)))
                    definitions.getOrPut(key) { destinationOf(mask.restore(destination.getTextInNode(mask.text))) }
                }
            } else {
                // Reversed, so that definitions are met in document order and the first one wins.
                node.children.asReversed().forEach(pending::addLast)
            }
        }
        return definitions
    }

    internal fun labelKey(label: String): String = LinkMap.normalizeLabel(label).toString()

    /** `<…>` removed, escapes and entities decoded, spaces encoded. */
    internal fun destinationOf(raw: String): String {
        val trimmed = raw.trim()
        val bare = if (trimmed.length >= 2 && trimmed.first() == '<' && trimmed.last() == '>') {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return decode(bare).replace(" ", "%20")
    }

    /**
     * What a link destination becomes: a [LinkTarget.Web] for `http`, `https` and `mailto` only,
     * `null` for anything else — see [LinkTarget.Web] for why a refused link is plain text.
     *
     * ⚠️ A destination holding a control character is refused whatever its scheme: nothing a person
     * types as an address contains one, and an intent should not carry it. Nor is one longer than
     * [MAX_LINK_LENGTH]: the address travels to the other app through Binder, whose transaction has a
     * size limit — a 400 KB link, which a note can hold, made `startActivity` throw (GPT-5.6 review,
     * 2026-09-25). A real address is a few hundred characters.
     */
    internal fun webTarget(destination: String): LinkTarget.Web? {
        if (destination.length > MAX_LINK_LENGTH || destination.any { Character.isISOControl(it) }) return null
        val scheme = SCHEME.find(destination)?.groupValues?.get(1)?.lowercase() ?: return null
        return if (scheme in ALLOWED_SCHEMES) LinkTarget.Web(destination) else null
    }

    /**
     * Backslash escapes and HTML entities, decoded to plain text as CommonMark specifies: an escape
     * only before ASCII punctuation; a numeric entity outside Unicode, or naming a surrogate or NUL,
     * becomes U+FFFD; an unknown name stays as written.
     */
    internal fun decode(raw: String): String {
        if (raw.indexOf('\\') < 0 && raw.indexOf('&') < 0) return raw
        return DECODABLE.replace(raw) { match ->
            val escaped = match.groups[1]
            val decimal = match.groups[2]
            val hexadecimal = match.groups[3]
            when {
                escaped != null -> escaped.value
                decimal != null -> codePointText(decimal.value.toInt())
                hexadecimal != null -> codePointText(hexadecimal.value.toInt(HEX))
                else -> Entities.map[match.value]?.let(::codePointText) ?: match.value
            }
        }
    }

    private fun codePointText(codePoint: Int): String {
        val usable = codePoint in 1..Character.MAX_CODE_POINT &&
            codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
        return if (usable) String(Character.toChars(codePoint)) else REPLACEMENT
    }

    private const val HEX = 16
    private const val REPLACEMENT = "\uFFFD"
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")
    private val DECODABLE = Regex(
        """\\([!-/:-@\[-`{-~])|&#([0-9]{1,7});|&#[xX]([0-9a-fA-F]{1,6});|&[A-Za-z][A-Za-z0-9]{1,31};""",
    )
}

/**
 * One reading of one note.
 *
 * Every offset of the tree points into the **masked** text, the one the parser saw — [raw] reads
 * from it, and [WikiLinkMask.restore] turns a literal context back into what the user wrote.
 */
private class Reading(private val mask: WikiLinkMask, private val definitions: Map<String, String>) {

    fun blocks(nodes: List<ASTNode>, depth: Int): List<PreviewBlock> = nodes.mapNotNull { node ->
        val built = block(node, depth)
        // A top-level list and table are laid out item by item, row by row: they are bounded where
        // their items are built. Anything else at the top level is ONE lazy item, drawn whole.
        val drawnWhole = depth == 0 && built != null && built !is PreviewBlock.ListBlock && built !is PreviewBlock.Table
        if (drawnWhole && built.weight() > MarkdownPreviewReader.MAX_BLOCKS_PER_ITEM) asWritten(node) else built
    }

    private fun block(node: ASTNode, depth: Int): PreviewBlock? = when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> runs(node.children).takeIf { it.isNotEmpty() }?.let(PreviewBlock::Paragraph)
        in HEADINGS -> heading(node)
        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST ->
            if (depth >= MarkdownPreviewReader.MAX_DEPTH) sourceOf(node) else list(node, depth)
        MarkdownElementTypes.BLOCK_QUOTE -> quote(node, depth)
        // A GitHub alert (`> [!NOTE]`) is a quote whose marker stays text: notes_tech 2.0.9's
        // renderer has no alerts, and shows the marker as written.
        GFMElementTypes.ALERT -> if (depth >= MarkdownPreviewReader.MAX_DEPTH) sourceOf(node) else alert(node, depth)
        MarkdownElementTypes.CODE_FENCE -> PreviewBlock.Code(fenceText(node))
        MarkdownElementTypes.CODE_BLOCK -> PreviewBlock.Code(indentedCodeText(node))
        MarkdownElementTypes.HTML_BLOCK -> PreviewBlock.RawHtml(htmlText(node))
        GFMElementTypes.TABLE -> table(node)
        MarkdownTokenTypes.HORIZONTAL_RULE -> PreviewBlock.Rule
        // Definitions are not drawn: they only give their address to the links that name them.
        MarkdownElementTypes.LINK_DEFINITION -> null
        in LAYOUT_TOKENS -> null
        // Anything else — `$$` math, which 2.0.9 does not render either — is shown as written, so
        // that no character of the note disappears from its preview.
        else -> sourceOf(node)
    }

    private fun heading(node: ASTNode): PreviewBlock {
        val content = node.child(MarkdownTokenTypes.ATX_CONTENT) ?: node.child(MarkdownTokenTypes.SETEXT_CONTENT)
        return PreviewBlock.Heading(HEADINGS.getValue(node.type), content?.let { runs(it.children) }.orEmpty())
    }

    private fun list(node: ASTNode, depth: Int): PreviewBlock {
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        // `3.` or `3)`: the digits give the first number, as written. CommonMark caps them at nine,
        // so `toIntOrNull` cannot overflow on a valid list — and falls back to 1 on anything else.
        val start = items.firstOrNull()?.child(MarkdownTokenTypes.LIST_NUMBER)
            ?.let { raw(it).takeWhile(Char::isDigit).toIntOrNull() } ?: 1
        return PreviewBlock.ListBlock(
            ordered = node.type == MarkdownElementTypes.ORDERED_LIST,
            start = start,
            items = items.map { item ->
                val task = item.child(GFMTokenTypes.CHECK_BOX)?.let { box ->
                    if (raw(box).any { it == 'x' || it == 'X' }) TaskMark.DONE else TaskMark.OPEN
                }
                val built = ListItem(task, blocks(item.children.filter { it.type !in ITEM_MARKERS }, depth + 1))
                // Each item of a top-level list is one lazy item of the preview: bounded here.
                val heavy = depth == 0 && built.blocks.sumOf { it.weight() } > MarkdownPreviewReader.MAX_BLOCKS_PER_ITEM
                if (heavy) ListItem(task, listOf(asWritten(item))) else built
            },
        )
    }

    private fun quote(node: ASTNode, depth: Int): PreviewBlock? = if (depth >=
        MarkdownPreviewReader.MAX_DEPTH
    ) {
        sourceOf(node)
    } else {
        PreviewBlock.Quote(blocks(node.children, depth + 1))
    }

    /** A block too heavy to draw at once — see [MarkdownPreviewReader.MAX_BLOCKS_PER_ITEM] — as written. */
    private fun asWritten(node: ASTNode): PreviewBlock = PreviewBlock.Code(mask.restore(raw(node)))

    private fun alert(node: ASTNode, depth: Int): PreviewBlock {
        val marker = node.child(GFMTokenTypes.ALERT_TITLE)?.let { PreviewBlock.Paragraph(listOf(TextRun(raw(it)))) }
        val rest = blocks(node.children.filter { it.type != GFMTokenTypes.ALERT_TITLE }, depth + 1)
        return PreviewBlock.Quote(listOfNotNull(marker) + rest)
    }

    /**
     * The fence's lines, without the fences, and with the fence's own indent removed from each —
     * the library's `CodeFenceGeneratingProvider`, reproduced. The `> ` of a quote and the indent of
     * a list item are separate tokens: taking only content and line ends leaves them out.
     */
    private fun fenceText(node: ASTNode): String {
        val indent = raw(node).takeWhile { it == ' ' }.length
        var inBody = false
        val body = StringBuilder()
        node.children.forEach { child ->
            when {
                !inBody -> inBody = child.type == MarkdownTokenTypes.EOL
                child.type in CODE_FENCE_LINES -> body.append(HtmlGenerator.trimIndents(raw(child), indent))
            }
        }
        return mask.restore(body).trimEnd('\n')
    }

    /** An indented code block: four columns of indent removed from each line, as the library does. */
    private fun indentedCodeText(node: ASTNode): String {
        val body = StringBuilder()
        node.children.forEach { child ->
            when (child.type) {
                MarkdownTokenTypes.CODE_LINE -> body.append(HtmlGenerator.trimIndents(raw(child), CODE_INDENT))
                MarkdownTokenTypes.EOL -> body.append('\n')
            }
        }
        return mask.restore(body).trimEnd('\n')
    }

    private fun htmlText(node: ASTNode): String =
        mask.restore(node.children.filter { it.type in HTML_LINES }.joinToString("") { raw(it) }).trimEnd()

    private fun table(node: ASTNode): PreviewBlock {
        val alignments = node.child(GFMTokenTypes.TABLE_SEPARATOR)?.let { alignmentsOf(raw(it)) }.orEmpty()
        // Every row draws one cell per column: a separator of ten thousand `|-` would lay out ten
        // thousand cells per row. Past the cap the table is shown as written, like code.
        if (alignments.size > MarkdownPreviewReader.MAX_TABLE_COLUMNS) return PreviewBlock.Code(mask.restore(raw(node)))

        fun cells(row: ASTNode): List<List<TextRun>> {
            val written = row.children.filter {
                it.type == GFMTokenTypes.CELL
            }.map { runs(it.children, inTable = true) }
            return List(alignments.size) { index -> written.getOrElse(index) { emptyList() } }
        }
        return PreviewBlock.Table(
            alignments = alignments,
            header = node.child(GFMElementTypes.HEADER)?.let(::cells) ?: List(alignments.size) { emptyList() },
            rows = node.children.filter { it.type == GFMElementTypes.ROW }.map(::cells),
        )
    }

    /** `|:--|--:|:-:|`, split the way the library splits it: outer empty cells do not count. */
    private fun alignmentsOf(separator: String): List<CellAlignment> {
        val parts = separator.split('|')
        return parts.withIndex()
            .filter { (index, part) -> part.isNotBlank() || index in 1 until parts.lastIndex }
            .map { (_, part) ->
                val trimmed = part.trim()
                val left = trimmed.startsWith(':')
                val right = trimmed.endsWith(':') && trimmed.length > 1
                when {
                    left && right -> CellAlignment.CENTER
                    right -> CellAlignment.END
                    else -> CellAlignment.START
                }
            }
    }

    /** A container shown as its source text, placeholders restored — see [MarkdownPreviewReader.MAX_DEPTH]. */
    private fun sourceOf(node: ASTNode): PreviewBlock? =
        mask.restore(raw(node)).trim().takeIf { it.isNotEmpty() }?.let { PreviewBlock.Paragraph(listOf(TextRun(it))) }

    // ── Inline content ───────────────────────────────────────────────────────────────────────────

    private fun runs(nodes: List<ASTNode>, inTable: Boolean = false): List<TextRun> {
        val out = RunsBuilder()
        inlines(nodes, Context(inTable = inTable), out)
        return out.build()
    }

    /**
     * @param link the link the text belongs to, if any.
     * @param insideLinkText inside a link's text — or an image's — nothing else becomes a link: an
     *   autolink is text and a `[[Title]]` stays as written. A link in a link has no single target.
     * @param inTable GFM writes a pipe inside a cell as `\|`, code spans included.
     */
    private data class Context(
        val styles: Set<RunStyle> = emptySet(),
        val link: LinkTarget? = null,
        val insideLinkText: Boolean = false,
        val inTable: Boolean = false,
    ) {
        fun with(style: RunStyle) = copy(styles = styles + style)
    }

    private fun RunsBuilder.text(value: String, context: Context) = text(value, context.styles, context.link)

    private fun inlines(nodes: List<ASTNode>, context: Context, out: RunsBuilder) {
        var index = 0
        while (index < nodes.size) {
            // `<m@n.o>`: the library leaves the chevrons as the email token's siblings.
            if (isChevronedEmail(nodes, index)) {
                email(nodes[index + 1], context, out)
                index += EMAIL_AUTOLINK_TOKENS
            } else {
                inline(nodes[index], context, out)
                index++
            }
        }
    }

    private fun isChevronedEmail(nodes: List<ASTNode>, index: Int): Boolean =
        nodes[index].type == MarkdownTokenTypes.LT &&
            nodes.getOrNull(index + 1)?.type == MarkdownTokenTypes.EMAIL_AUTOLINK &&
            nodes.getOrNull(index + 2)?.type == MarkdownTokenTypes.GT

    // One branch per node type: the one table that says what each becomes.
    private fun inline(node: ASTNode, context: Context, out: RunsBuilder) {
        when (node.type) {
            MarkdownElementTypes.EMPH -> inlines(
                inner(node, MarkdownTokenTypes.EMPH),
                context.with(RunStyle.ITALIC),
                out,
            )
            MarkdownElementTypes.STRONG -> inlines(
                inner(node, MarkdownTokenTypes.EMPH),
                context.with(RunStyle.BOLD),
                out,
            )
            GFMElementTypes.STRIKETHROUGH ->
                inlines(inner(node, GFMTokenTypes.TILDE), context.with(RunStyle.STRIKETHROUGH), out)
            MarkdownElementTypes.CODE_SPAN -> out.text(codeSpanText(node, context), context.with(RunStyle.CODE))
            MarkdownElementTypes.INLINE_LINK -> inlineLink(node, context, out)
            MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK ->
                referenceLink(node, context, out)
            MarkdownElementTypes.IMAGE -> image(node, context, out)
            MarkdownElementTypes.AUTOLINK -> node.child(MarkdownTokenTypes.AUTOLINK)?.let {
                autolink(raw(it), context, out)
            }
            GFMTokenTypes.GFM_AUTOLINK -> autolink(raw(node), context, out)
            MarkdownTokenTypes.EMAIL_AUTOLINK -> email(node, context, out)
            MarkdownTokenTypes.HARD_LINE_BREAK -> out.hardBreak(context.styles, context.link)
            MarkdownTokenTypes.EOL -> out.softBreak(context.styles, context.link)
            // The `>` of a lazily continued quote line sits inside the paragraph; the library's own
            // `leafText` renders it as nothing, and so does this.
            MarkdownTokenTypes.BLOCK_QUOTE -> Unit
            // Raw inline HTML and `$math$` are shown as written, entities and escapes included.
            MarkdownTokenTypes.HTML_TAG, GFMElementTypes.INLINE_MATH -> out.text(mask.restore(raw(node)), context)
            else -> if (node.children.isEmpty()) leaf(node, context, out) else inlines(node.children, context, out)
        }
    }

    /** Plain text: placeholders first, on the raw text, then escapes and entities — see [WikiLinkMask.split]. */
    private fun leaf(node: ASTNode, context: Context, out: RunsBuilder) {
        mask.split(raw(node)).forEach { piece ->
            when (piece) {
                is WikiLinkMask.Piece.Plain -> out.text(MarkdownPreviewReader.decode(piece.text), context)
                is WikiLinkMask.Piece.NoteLink ->
                    if (context.insideLinkText) {
                        out.text(piece.original, context)
                    } else {
                        out.text(piece.title, context.copy(link = LinkTarget.Note(piece.title)))
                    }
            }
        }
    }

    /** CommonMark: line ends become spaces, then one space is stripped from each end if both have one. */
    private fun codeSpanText(node: ASTNode, context: Context): String {
        val content = node.children.drop(1).dropLast(1).joinToString("") { raw(it) }.replace('\n', ' ')
        val padded = content.length >= 2 && content.first() == ' ' && content.last() == ' ' && content.isNotBlank()
        val stripped = if (padded) content.substring(1, content.length - 1) else content
        return mask.restore(if (context.inTable) stripped.replace("\\|", "|") else stripped)
    }

    private fun inlineLink(node: ASTNode, context: Context, out: RunsBuilder) {
        val label = node.child(MarkdownElementTypes.LINK_TEXT) ?: return inlines(node.children, context, out)
        linkText(label, inlineDestination(node).orEmpty(), context, out)
    }

    /**
     * ⚠️ A `<…>` destination holding a space — valid CommonMark, and followed by 2.0.9 — is parsed
     * by the library as an `AUTOLINK` node inside the link, not as a `LINK_DESTINATION`: its own HTML
     * gives such a link an empty `href`. Its text, chevrons included, is the destination.
     */
    private fun inlineDestination(link: ASTNode): String? =
        (link.child(MarkdownElementTypes.LINK_DESTINATION) ?: link.child(MarkdownElementTypes.AUTOLINK))
            ?.let { MarkdownPreviewReader.destinationOf(mask.restore(raw(it))) }

    /** A reference link whose label is defined; otherwise its brackets and text, as written. */
    private fun referenceLink(node: ASTNode, context: Context, out: RunsBuilder) {
        val label = node.child(MarkdownElementTypes.LINK_LABEL)
        val destination = label?.let(::definitionOf)
        if (label == null || destination == null) return inlines(node.children, context, out)
        linkText(node.child(MarkdownElementTypes.LINK_TEXT) ?: label, destination, context, out)
    }

    private fun definitionOf(label: ASTNode): String? =
        definitions[MarkdownPreviewReader.labelKey(mask.restore(raw(label)))]

    /**
     * The text of a link, bracket to bracket. A refused address (see [LinkTarget.Web]) leaves the
     * text in place, as plain text; so does a link written inside another one.
     */
    private fun linkText(label: ASTNode, destination: String, context: Context, out: RunsBuilder) {
        val target = if (context.insideLinkText) null else MarkdownPreviewReader.webTarget(destination)
        inlines(bracketed(label), context.copy(link = target ?: context.link, insideLinkText = true), out)
    }

    /**
     * An image is never loaded: its alternative text stands in, in the image style — or its address
     * when it has none, as notes_tech 2.0.9 does.
     */
    private fun image(node: ASTNode, context: Context, out: RunsBuilder) {
        val link = node.child(MarkdownElementTypes.INLINE_LINK)
            ?: node.child(MarkdownElementTypes.FULL_REFERENCE_LINK)
            ?: node.child(MarkdownElementTypes.SHORT_REFERENCE_LINK)
        val label = link?.child(MarkdownElementTypes.LINK_TEXT) ?: link?.child(MarkdownElementTypes.LINK_LABEL)
        val destination = when (link?.type) {
            null -> null
            MarkdownElementTypes.INLINE_LINK -> inlineDestination(link).orEmpty()
            else -> link.child(MarkdownElementTypes.LINK_LABEL)?.let(::definitionOf)
        }
        if (label == null || destination == null) return inlines(node.children, context, out)
        val alt = RunsBuilder().also { inlines(bracketed(label), context.copy(insideLinkText = true), it) }
            .build().joinToString("") { it.text }
        out.text(alt.ifBlank { destination }, context.with(RunStyle.IMAGE))
    }

    /**
     * `www.…`, `https://…` or `<scheme:…>`. Inside a link's text it is plain text — the library does
     * the same ("do not render GFM autolinks under link titles").
     *
     * ⚠️ **Only `www.` is written without a scheme.** Every other autolink carries its own, `//` or
     * not: `<mailto:a@b.c>` became `https://mailto:a@b.c` — a browser for a mail address — and a
     * refused `<javascript:…>` was drawn as an https link, while this tested for `://` (GPT-5.6
     * review, 2026-09-25). `www.` gets `https://`, where notes_tech 2.0.9 and the library's default
     * use `http://`: an address the user wrote no scheme for should not be opened unencrypted.
     */
    private fun autolink(written: String, context: Context, out: RunsBuilder) {
        val shown = mask.restore(written)
        if (context.insideLinkText) return out.text(shown, context)
        val destination = if (shown.startsWith("www.", ignoreCase = true)) "https://$shown" else shown
        val target = MarkdownPreviewReader.webTarget(destination)
        out.text(shown, if (target != null) context.copy(link = target) else context)
    }

    private fun email(node: ASTNode, context: Context, out: RunsBuilder) {
        val address = mask.restore(raw(node))
        if (context.insideLinkText) return out.text(address, context)
        out.text(address, context.copy(link = MarkdownPreviewReader.webTarget("mailto:$address")))
    }

    // ── Tree helpers ─────────────────────────────────────────────────────────────────────────────

    private fun raw(node: ASTNode): String = node.getTextInNode(mask.text).toString()

    /** A node's children without its opening and closing delimiter tokens of [delimiter]. */
    private fun inner(node: ASTNode, delimiter: IElementType): List<ASTNode> {
        val children = node.children
        var from = 0
        var to = children.size
        while (from < to && children[from].type == delimiter) from++
        while (to > from && children[to - 1].type == delimiter) to--
        return children.subList(from, to)
    }

    /** A link text or label without its outer `[` and `]`. */
    private fun bracketed(label: ASTNode): List<ASTNode> {
        val children = label.children
        val from = if (children.firstOrNull()?.type == MarkdownTokenTypes.LBRACKET) 1 else 0
        val closed = children.size > from && children.last().type == MarkdownTokenTypes.RBRACKET
        return children.subList(from, if (closed) children.size - 1 else children.size)
    }

    private companion object {
        const val CODE_INDENT = 4
        const val EMAIL_AUTOLINK_TOKENS = 3

        val HEADINGS: Map<IElementType, Int> = mapOf(
            MarkdownElementTypes.ATX_1 to 1,
            MarkdownElementTypes.ATX_2 to 2,
            MarkdownElementTypes.ATX_3 to 3,
            MarkdownElementTypes.ATX_4 to 4,
            MarkdownElementTypes.ATX_5 to 5,
            MarkdownElementTypes.ATX_6 to 6,
            MarkdownElementTypes.SETEXT_1 to 1,
            MarkdownElementTypes.SETEXT_2 to 2,
        )

        /** Tokens that only lay blocks out: line ends, indents, the `>` of a quote. */
        val LAYOUT_TOKENS: Set<IElementType> = setOf(
            MarkdownTokenTypes.EOL,
            MarkdownTokenTypes.WHITE_SPACE,
            MarkdownTokenTypes.BLOCK_QUOTE,
        )

        /** What a list item holds besides its blocks. */
        val ITEM_MARKERS: Set<IElementType> = setOf(
            MarkdownTokenTypes.LIST_BULLET,
            MarkdownTokenTypes.LIST_NUMBER,
            GFMTokenTypes.CHECK_BOX,
        )

        val CODE_FENCE_LINES: Set<IElementType> = setOf(MarkdownTokenTypes.CODE_FENCE_CONTENT, MarkdownTokenTypes.EOL)
        val HTML_LINES: Set<IElementType> = setOf(MarkdownTokenTypes.HTML_BLOCK_CONTENT, MarkdownTokenTypes.EOL)
    }
}

private fun ASTNode.child(type: IElementType): ASTNode? = children.firstOrNull { it.type == type }

/**
 * Accumulates the runs of one paragraph, heading or cell.
 *
 * Line breaks follow notes_tech 2.0.9 (`flutter_markdown_plus`, `softLineBreak: false`): a soft one
 * — a single line end — is a space, with the spaces around it folded (` ?\n *` → one space); a hard
 * one — two trailing spaces or a backslash — is a line break. Leading and trailing whitespace of the
 * whole is dropped, as its `TrimmingInlineHolderProvider` does for headings and cells.
 */
private class RunsBuilder {
    private val runs = mutableListOf<TextRun>()
    private var skipLeadingSpaces = true

    fun text(value: String, styles: Set<RunStyle>, link: LinkTarget?) {
        // Spaces inside inline code are content, not layout: `` ` a` `` keeps its space.
        val kept = if (skipLeadingSpaces && RunStyle.CODE !in styles) value.trimStart(' ', '\t') else value
        if (kept.isEmpty()) return
        skipLeadingSpaces = false
        val last = runs.lastOrNull()
        if (last != null && last.styles == styles && last.link == link) {
            runs[runs.lastIndex] = last.copy(text = last.text + kept)
        } else {
            runs += TextRun(kept, styles, link)
        }
    }

    /**
     * ⚠️ A line break drops trailing spaces and tabs, **never a `\n`**. A hard break is always
     * followed by the line end it sits on, which arrives here as a soft break: dropping the `\n` as
     * trailing whitespace erased the hard break, and the space that replaced it was then eaten as a
     * leading space — `one··⏎two` came out `onetwo` (measured by `MarkdownPreviewReaderTest`,
     * 2026-09-25). Kept, the `\n` leaves the soft break's space leading, hence skipped: nothing to add.
     */
    fun softBreak(styles: Set<RunStyle>, link: LinkTarget?) = lineBreak(" ", styles, link)

    fun hardBreak(styles: Set<RunStyle>, link: LinkTarget?) = lineBreak("\n", styles, link)

    private fun lineBreak(separator: String, styles: Set<RunStyle>, link: LinkTarget?) {
        if (runs.isEmpty()) return
        dropTrailing(' ', '\t')
        text(separator, styles, link)
        skipLeadingSpaces = true
    }

    /** The runs, without the whitespace — and a line break — that nothing follows. */
    fun build(): List<TextRun> {
        dropTrailing(' ', '\t', '\n')
        return runs.toList()
    }

    /** Never inside inline code: its spaces are content. */
    private fun dropTrailing(vararg characters: Char) {
        while (runs.isNotEmpty()) {
            val last = runs.last()
            if (RunStyle.CODE in last.styles) return
            val kept = last.text.trimEnd(*characters)
            if (kept.isNotEmpty()) {
                runs[runs.lastIndex] = last.copy(text = kept)
                return
            }
            runs.removeAt(runs.lastIndex)
        }
    }
}
