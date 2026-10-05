package com.filestech.notes_tech.domain.markdown

import com.filestech.notes_tech.core.text.DartTextSemantics
import com.filestech.notes_tech.domain.links.WikiLinkParser

/**
 * The note's text with every `[[Title]]` replaced by an opaque placeholder, **before** Markdown
 * parsing.
 *
 * ## Why before, and not by walking the parsed tree
 *
 * The GFM parser has no idea what `[[Title]]` is: it reads `[Title]` as a reference link — even
 * with no definition — wrapped in two stray brackets, and a title holding `*`, `` ` `` or `|` is cut
 * into emphasis, code or table cells. notes_tech 2.0.9 avoids this because its `WikiLinkSyntax` is
 * tried **before** the built-in inline syntaxes. Masking first reproduces that precedence exactly:
 * the parser sees an ordinary word where the link was.
 *
 * Where a placeholder lands in code, a URL, raw HTML or any other literal context, [restore] puts
 * the original characters back. Only inline text turns it into a link — see [split].
 *
 * ## The placeholder
 *
 * `U+E000`, the entry's index in decimal, `U+E001` — two private-use characters around digits, which
 * no Markdown rule treats as punctuation or whitespace, so a placeholder stays one text token.
 * ⚠️ Private-use characters can appear in a note (icon fonts, pasted text): **every** `U+E000` and
 * `U+E001` of the source is masked too, as a literal entry, so that no character of the note can be
 * mistaken for a placeholder.
 */
internal class WikiLinkMask private constructor(
    /** The text given to the parser. */
    val text: String,
    private val entries: List<Entry>,
) {

    /** What a placeholder stands for. */
    private sealed interface Entry {
        /** The characters the placeholder replaced, exactly. */
        val original: String
    }

    private data class Link(val title: String, override val original: String) : Entry

    private data class Literal(override val original: String) : Entry

    /** A piece of inline text: either plain characters or a `[[Title]]` link. */
    sealed interface Piece {
        data class Plain(val text: String) : Piece

        /** @param original the `[[…]]` as written, for the contexts where it must stay text. */
        data class NoteLink(val title: String, val original: String) : Piece
    }

    /** [text] with every placeholder turned back into the characters it replaced. */
    fun restore(masked: CharSequence): String = PLACEHOLDER.replace(masked) { match -> entry(match).original }

    /**
     * Cuts inline text at its placeholders: a masked `[[Title]]` becomes [Piece.NoteLink], anything
     * else is [Piece.Plain] — restored, so a masked literal character comes back as itself.
     *
     * ⚠️ Called on the **raw** text, before entities and escapes are decoded: `&#xE000;` decodes to
     * a placeholder character, and decoding first would let a note forge a link out of entities.
     */
    fun split(masked: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var from = 0
        PLACEHOLDER.findAll(masked).forEach { match ->
            if (match.range.first > from) pieces += Piece.Plain(masked.substring(from, match.range.first))
            pieces += when (val entry = entry(match)) {
                is Link -> Piece.NoteLink(entry.title, entry.original)
                is Literal -> Piece.Plain(entry.original)
            }
            from = match.range.last + 1
        }
        if (from < masked.length) pieces += Piece.Plain(masked.substring(from))
        return pieces
    }

    // A placeholder only ever comes from [mask], so its index is always in range; a miss would be a
    // defect of this class, and failing loudly beats drawing the wrong link.
    private fun entry(match: MatchResult): Entry = entries[match.groupValues[1].toInt()]

    companion object {
        private const val OPEN = '\uE000'
        private const val CLOSE = '\uE001'
        private val PLACEHOLDER = Regex("$OPEN(\\d+)$CLOSE")

        /** The indexer's own pattern, or one of the two placeholder characters. */
        private val MASKED = Regex("${WikiLinkParser.LINK.pattern}|[$OPEN$CLOSE]")

        fun mask(source: String): WikiLinkMask {
            val entries = mutableListOf<Entry>()
            val text = MASKED.replace(source) { match ->
                val raw = match.groupValues[1]
                val entry = when {
                    // One of the placeholder characters, found in the note itself.
                    match.value.length == 1 && raw.isEmpty() -> Literal(match.value)
                    else -> {
                        // `[[   ]]` is not a link for the indexer either (`WikiLinkParser.extract`):
                        // it stays text, untouched. Same rule as 2.0.9's `WikiLinkSyntax.onMatch`.
                        val title = DartTextSemantics.trim(raw)
                        if (title.isEmpty()) return@replace match.value
                        Link(title, match.value)
                    }
                }
                entries += entry
                "$OPEN${entries.lastIndex}$CLOSE"
            }
            return WikiLinkMask(text, entries)
        }
    }
}
