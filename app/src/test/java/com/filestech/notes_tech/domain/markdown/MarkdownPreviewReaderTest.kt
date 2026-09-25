package com.filestech.notes_tech.domain.markdown

import com.filestech.notes_tech.domain.links.WikiLinkParser
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Markdown preview's reader (D-024): what each construct of a note becomes.
 *
 * The expected values are what notes_tech 2.0.9 shows through `flutter_markdown_plus` with its
 * GitHub-flavoured extension set — except where a case says it is a deliberate gap, and why.
 */
@DisplayName("Markdown preview reader")
class MarkdownPreviewReaderTest {

    private fun read(source: String) = MarkdownPreviewReader.read(source)

    private fun run(text: String, vararg styles: RunStyle, link: LinkTarget? = null) =
        TextRun(text, styles.toSet(), link)

    private fun paragraph(vararg runs: TextRun) = PreviewBlock.Paragraph(runs.toList())

    private fun web(url: String) = LinkTarget.Web(url)

    private fun note(title: String) = LinkTarget.Note(title)

    /** The runs of a single-paragraph note. */
    private fun runsOf(source: String): List<TextRun> = (read(source).single() as PreviewBlock.Paragraph).runs

    @Nested
    @DisplayName("blocks")
    inner class Blocks {

        @Test
        fun `an empty or blank note has nothing to draw`() {
            assertThat(read("")).isEmpty()
            assertThat(read(" \n\n\t\n")).isEmpty()
        }

        /**
         * 🔴 Windows and old Mac line endings are line endings, as CommonMark says. JetBrains' parser
         * knows only `\n`: a quote's `>` lines did not split its paragraphs, blank lines did not split
         * blocks, and the `\r` stayed in the text — the MIT licence of the terms of use came out as
         * one paragraph (2026-09-25).
         */
        @Test
        fun `CRLF and lone CR endings read as line feeds`() {
            val lf = "> MIT License\n>\n> Copyright\n\nA paragraph\n\n- item"
            val attendu = read(lf)
            // The control: with line feeds, the quote holds two paragraphs, and three blocks follow.
            assertThat((attendu.first() as PreviewBlock.Quote).blocks).hasSize(2)
            assertThat(attendu).hasSize(3)

            assertThat(read(lf.replace("\n", "\r\n"))).isEqualTo(attendu)
            assertThat(read(lf.replace("\n", "\r"))).isEqualTo(attendu)
        }

        @Test
        fun `ATX and Setext headings keep their level, not their markers`() {
            assertThat(read("# One\n\n### Three ###\n\n###### Six\n\nTitle\n=====\n\nSub\n---")).containsExactly(
                PreviewBlock.Heading(1, listOf(run("One"))),
                PreviewBlock.Heading(3, listOf(run("Three"))),
                PreviewBlock.Heading(6, listOf(run("Six"))),
                PreviewBlock.Heading(1, listOf(run("Title"))),
                PreviewBlock.Heading(2, listOf(run("Sub"))),
            ).inOrder()
        }

        @Test
        fun `bullet, ordered and task lists, nested`() {
            val blocks = read("- a\n- [ ] open\n- [x] done\n  - nested\n\n3. three\n4. four")
            assertThat(blocks).containsExactly(
                PreviewBlock.ListBlock(
                    ordered = false,
                    start = 1,
                    items = listOf(
                        ListItem(null, listOf(paragraph(run("a")))),
                        ListItem(TaskMark.OPEN, listOf(paragraph(run("open")))),
                        ListItem(
                            TaskMark.DONE,
                            listOf(
                                paragraph(run("done")),
                                PreviewBlock.ListBlock(
                                    false,
                                    1,
                                    listOf(ListItem(null, listOf(paragraph(run("nested"))))),
                                ),
                            ),
                        ),
                    ),
                ),
                // `3.` starts the list at 3, as written.
                PreviewBlock.ListBlock(
                    ordered = true,
                    start = 3,
                    items = listOf(
                        ListItem(null, listOf(paragraph(run("three")))),
                        ListItem(null, listOf(paragraph(run("four")))),
                    ),
                ),
            ).inOrder()
        }

        @Test
        fun `a capital X ticks a task box too`() {
            val list = read("- [X] done").single() as PreviewBlock.ListBlock
            assertThat(list.items.single().task).isEqualTo(TaskMark.DONE)
        }

        @Test
        fun `quotes nest, and a lazily continued line loses its marker`() {
            assertThat(read("> outer\n> > inner\n\n> first\nlazy")).containsExactly(
                PreviewBlock.Quote(
                    listOf(paragraph(run("outer")), PreviewBlock.Quote(listOf(paragraph(run("inner"))))),
                ),
                PreviewBlock.Quote(listOf(paragraph(run("first lazy")))),
            ).inOrder()
            // The `>` token the library leaves inside a continued paragraph is not text.
            assertThat(read("> a\n> b")).containsExactly(PreviewBlock.Quote(listOf(paragraph(run("a b")))))
        }

        @Test
        fun `a GitHub alert is a quote whose marker stays text, as 2_0_9 shows it`() {
            assertThat(read("> [!NOTE]\n> Careful")).containsExactly(
                PreviewBlock.Quote(listOf(paragraph(run("[!NOTE]")), paragraph(run("Careful")))),
            )
        }

        @Test
        fun `code keeps its text, without fences, language, indent or quote markers`() {
            assertThat(
                read("```kotlin\nval x = 1\n  indented\n```\n\n    four spaces\n    more\n\n> ```\n> quoted\n> ```"),
            )
                .containsExactly(
                    PreviewBlock.Code("val x = 1\n  indented"),
                    PreviewBlock.Code("four spaces\nmore"),
                    PreviewBlock.Quote(listOf(PreviewBlock.Code("quoted"))),
                ).inOrder()
        }

        @Test
        fun `a fence inside a list item loses the item's indent`() {
            val list = read("- item\n\n  ```\n  code\n  ```").single() as PreviewBlock.ListBlock
            assertThat(
                list.items.single().blocks,
            ).containsExactly(paragraph(run("item")), PreviewBlock.Code("code")).inOrder()
        }

        @Test
        fun `a table pads short rows and cuts long ones to the separator's columns`() {
            val table = read("| A | B |\n|:--|--:|\n| 1 |\n| 2 | 3 | 4 |").single()
            assertThat(table).isEqualTo(
                PreviewBlock.Table(
                    alignments = listOf(CellAlignment.START, CellAlignment.END),
                    header = listOf(listOf(run("A")), listOf(run("B"))),
                    rows = listOf(
                        listOf(listOf(run("1")), emptyList()),
                        listOf(listOf(run("2")), listOf(run("3"))),
                    ),
                ),
            )
        }

        @Test
        fun `a centred column, and an escaped pipe inside a cell, code included`() {
            val table = read("| x |\n|:-:|\n| a \\| b `c \\| d` |").single() as PreviewBlock.Table
            assertThat(table.alignments).containsExactly(CellAlignment.CENTER)
            assertThat(
                table.rows.single().single(),
            ).containsExactly(run("a | b "), run("c | d", RunStyle.CODE)).inOrder()
        }

        @Test
        fun `rules, and raw HTML shown as written — a deliberate gap with 2_0_9, which drops it`() {
            assertThat(read("a\n\n---\n\n<div>\n<b>x</b> &amp;\n</div>")).containsExactly(
                paragraph(run("a")),
                PreviewBlock.Rule,
                PreviewBlock.RawHtml("<div>\n<b>x</b> &amp;\n</div>"),
            ).inOrder()
        }

        @Test
        fun `a link definition is not drawn`() {
            assertThat(read("[a]: https://x.y")).isEmpty()
        }

        @Test
        fun `block math is an ordinary paragraph, as in 2_0_9 which has no math`() {
            assertThat(read("$$\nx^2\n$$")).containsExactly(paragraph(run("$$ x^2 $$")))
        }
    }

    @Nested
    @DisplayName("inline text")
    inner class Inline {

        @Test
        fun `emphasis, strong, strikethrough and code become styles, delimiters gone`() {
            assertThat(runsOf("Un *it* et **gras** et ~~barré~~ et `code`.")).containsExactly(
                run("Un "),
                run("it", RunStyle.ITALIC),
                run(" et "),
                run("gras", RunStyle.BOLD),
                run(" et "),
                run("barré", RunStyle.STRIKETHROUGH),
                run(" et "),
                run("code", RunStyle.CODE),
                run("."),
            ).inOrder()
        }

        @Test
        fun `nested styles add up`() {
            assertThat(runsOf("***both*** _**in**_")).containsExactly(
                run("both", RunStyle.BOLD, RunStyle.ITALIC),
                run(" "),
                run("in", RunStyle.BOLD, RunStyle.ITALIC),
            ).inOrder()
        }

        @Test
        fun `a soft line break is a space, spaces around it folded — 2_0_9's softLineBreak false`() {
            assertThat(runsOf("one \n   two\nthree")).containsExactly(run("one two three"))
        }

        @Test
        fun `a hard line break — two spaces or a backslash — is a line break`() {
            assertThat(runsOf("one  \ntwo\\\nthree")).containsExactly(run("one\ntwo\nthree"))
        }

        @Test
        fun `inline code keeps its inner spaces and strips one padding space each side`() {
            assertThat(runsOf("`` a`b ``")).containsExactly(run("a`b", RunStyle.CODE))
            assertThat(runsOf("x ` y` z")).containsExactly(run("x "), run(" y", RunStyle.CODE), run(" z")).inOrder()
        }

        @Test
        fun `a paragraph's own trimming stops at inline code, first or last`() {
            // Where the trimming applies — the paragraph's first and last characters — and nowhere
            // else could the guard be seen: in the middle of a line there is nothing to trim.
            assertThat(runsOf("` a` b")).containsExactly(run(" a", RunStyle.CODE), run(" b")).inOrder()
            assertThat(runsOf("a `b `")).containsExactly(run("a "), run("b ", RunStyle.CODE)).inOrder()
        }

        @Test
        fun `escapes and entities decode to plain text, emoji included`() {
            assertThat(runsOf("\\* \\[ &amp; &copy; &#233; &#x1F600; &unknown;"))
                .containsExactly(run("* [ & © é 😀 &unknown;"))
        }

        @Test
        fun `a numeric entity naming NUL, a surrogate or no code point becomes the replacement character`() {
            // `&#1114112;` is U+110000, one past the last code point: still an entity for
            // CommonMark (up to seven digits), hence replaced, not left as written.
            assertThat(runsOf("&#0; &#xD800; &#1114112;")).containsExactly(run("\uFFFD \uFFFD \uFFFD"))
            // Eight digits is no longer an entity: it stays as written.
            assertThat(runsOf("&#11141120;")).containsExactly(run("&#11141120;"))
        }

        @Test
        fun `inline HTML and inline math are shown as written`() {
            assertThat(runsOf("<b>x</b> \$a+b\$")).containsExactly(run("<b>x</b> \$a+b\$"))
        }
    }

    @Nested
    @DisplayName("links")
    inner class Links {

        @Test
        fun `an http link carries its target across its styled pieces`() {
            assertThat(runsOf("[see *this*](https://a.b \"title\")")).containsExactly(
                run("see ", link = web("https://a.b")),
                run("this", RunStyle.ITALIC, link = web("https://a.b")),
            ).inOrder()
        }

        @Test
        fun `only http, https and mailto become links — anything else is plain text, not a dead link`() {
            assertThat(runsOf("[a](page.md) [b](javascript:alert(1)) [c](file:///x) [d](intent://x) [e](#top)"))
                .containsExactly(run("a b c d e"))
            assertThat(runsOf("[m](mailto:x@y.z)")).containsExactly(run("m", link = web("mailto:x@y.z")))
            assertThat(runsOf("[u](HTTPS://A.B)")).containsExactly(run("u", link = web("HTTPS://A.B")))
        }

        @Test
        fun `autolinks, bare addresses and emails`() {
            assertThat(runsOf("<https://c.d> www.e.f https://g.h/i?j=k <m@n.o>")).containsExactly(
                run("https://c.d", link = web("https://c.d")),
                run(" "),
                // Deliberate gap: `https://`, where 2.0.9 opens `www.` addresses as `http://`.
                run("www.e.f", link = web("https://www.e.f")),
                run(" "),
                run("https://g.h/i?j=k", link = web("https://g.h/i?j=k")),
                run(" "),
                run("m@n.o", link = web("mailto:m@n.o")),
            ).inOrder()
        }

        /**
         * 🔴 Only `www.` is written without a scheme: a URI autolink keeps its own, `//` or not.
         * `<mailto:…>` became `https://mailto:…` — a browser for a mail address — and a refused
         * `<javascript:…>` was drawn as an https link (GPT-5.6 review, 2026-09-25).
         */
        @Test
        fun `a URI autolink keeps its scheme — mailto opens mail, a refused scheme is text`() {
            assertThat(
                runsOf("<mailto:a@b.example>"),
            ).containsExactly(run("mailto:a@b.example", link = web("mailto:a@b.example")))
            assertThat(runsOf("<javascript:alert(1)>")).containsExactly(run("javascript:alert(1)"))
            assertThat(runsOf("<intent://x#Intent;end>")).containsExactly(run("intent://x#Intent;end"))
            // The control: `www.` still gets its scheme.
            assertThat(
                runsOf("www.example.org"),
            ).containsExactly(run("www.example.org", link = web("https://www.example.org")))
        }

        @Test
        fun `a reference link needs its definition, first one wins, label matched loosely`() {
            val source = "[full][Ref] [Ref] [collapsed][] [missing]\n\n" +
                "[ref]: https://one\n[REF]: https://two\n[collapsed]: https://c"
            assertThat((read(source).first() as PreviewBlock.Paragraph).runs).containsExactly(
                run("full", link = web("https://one")),
                run(" "),
                run("Ref", link = web("https://one")),
                run(" "),
                run("collapsed", link = web("https://c")),
                run(" [missing]"),
            ).inOrder()
        }

        @Test
        fun `a destination's escapes and entities are decoded`() {
            assertThat(
                runsOf("[b](https://x.y/?c=1&amp;d=\\*)"),
            ).containsExactly(run("b", link = web("https://x.y/?c=1&d=*")))
        }

        @Test
        fun `a pointy-bracket destination may hold a space, encoded — the library parses it as an autolink`() {
            assertThat(runsOf("[a](<https://x.y/a b?c=1&amp;d=\\*>)"))
                .containsExactly(run("a", link = web("https://x.y/a%20b?c=1&d=*")))
        }

        @Test
        fun `nothing inside a link's text becomes a second link`() {
            assertThat(runsOf("[see www.x.y and [[Note]]](https://a.b)"))
                .containsExactly(run("see www.x.y and [[Note]]", link = web("https://a.b")))
        }
    }

    @Nested
    @DisplayName("images are never loaded")
    inner class Images {

        @Test
        fun `the alternative text stands in, plain, in the image style`() {
            assertThat(runsOf("![a *rich* alt](https://img.png)")).containsExactly(run("a rich alt", RunStyle.IMAGE))
        }

        @Test
        fun `without alternative text, the address stands in — as 2_0_9 does`() {
            assertThat(runsOf("![](pic.png)")).containsExactly(run("pic.png", RunStyle.IMAGE))
        }

        @Test
        fun `an image inside a link keeps the link`() {
            assertThat(
                runsOf("[![logo](i.png)](https://x.y)"),
            ).containsExactly(run("logo", RunStyle.IMAGE, link = web("https://x.y")))
        }

        /** Full and collapsed reference images — the case a review doubted, measured here. */
        @Test
        fun `a reference image, full or collapsed, shows its alternative text`() {
            val paragraphe = read(
                "![Logo][img] ![img][] ![missing][]\n\n[img]: https://x.y/i.png",
            ).first() as PreviewBlock.Paragraph
            assertThat(paragraphe.runs).containsExactly(
                run("Logo", RunStyle.IMAGE),
                run(" "),
                run("img", RunStyle.IMAGE),
                // Undefined: its source, as written — not an image.
                run(" ![missing][]"),
            ).inOrder()
        }
    }

    @Nested
    @DisplayName("[[Title]] links")
    inner class NoteLinks {

        @Test
        fun `a wiki link shows its trimmed title, and links to it`() {
            assertThat(runsOf("See [[ Plan 2026 ]] now")).containsExactly(
                run("See "),
                run("Plan 2026", link = note("Plan 2026")),
                run(" now"),
            ).inOrder()
        }

        @Test
        fun `a title holding Markdown characters is taken whole, as 2_0_9's syntax tried first takes it`() {
            assertThat(runsOf("[[a *b* `c` d|e]]")).containsExactly(run("a *b* `c` d|e", link = note("a *b* `c` d|e")))
        }

        @Test
        fun `in a heading, a list, a quote and a table cell`() {
            assertThat(read("# [[H]]\n\n- [[L]]\n\n> [[Q]]\n\n| [[T]] |\n|---|")).containsExactly(
                PreviewBlock.Heading(1, listOf(run("H", link = note("H")))),
                PreviewBlock.ListBlock(false, 1, listOf(ListItem(null, listOf(paragraph(run("L", link = note("L"))))))),
                PreviewBlock.Quote(listOf(paragraph(run("Q", link = note("Q"))))),
                PreviewBlock.Table(
                    listOf(CellAlignment.START),
                    listOf(listOf(run("T", link = note("T")))),
                    emptyList(),
                ),
            ).inOrder()
        }

        @Test
        fun `what the indexer refuses stays text — the same pattern`() {
            val tooLong = "x".repeat(201)
            assertThat(runsOf("[[   ]] [[a]b]] [[$tooLong]]")).containsExactly(run("[[   ]] [[a]b]] [[$tooLong]]"))
            // The control: the indexer finds no link in the same text either.
            assertThat(WikiLinkParser.extract("[[   ]] [[a]b]] [[$tooLong]]")).isEmpty()
        }

        @Test
        fun `in code, it is text`() {
            assertThat(read("`[[a]]`\n\n```\n[[b]]\n```\n\n    [[c]]")).containsExactly(
                paragraph(run("[[a]]", RunStyle.CODE)),
                PreviewBlock.Code("[[b]]"),
                PreviewBlock.Code("[[c]]"),
            ).inOrder()
        }

        @Test
        fun `in an address or an HTML block, it is restored as written`() {
            assertThat(read("[x](https://a.b/[[p]])\n\n<div>\n[[h]]\n</div>")).containsExactly(
                paragraph(run("x", link = web("https://a.b/[[p]]"))),
                PreviewBlock.RawHtml("<div>\n[[h]]\n</div>"),
            ).inOrder()
        }

        @Test
        fun `between two inline HTML tags it is a link — the tags alone are raw, as in 2_0_9`() {
            assertThat(runsOf("<i>[[h]]</i>")).containsExactly(
                run("<i>"),
                run("h", link = note("h")),
                run("</i>"),
            ).inOrder()
        }

        @Test
        fun `private-use characters of the note survive, and never become links`() {
            val pua = "\uE000\uE001 \uE0000\uE001"
            assertThat(runsOf("$pua [[x]]")).containsExactly(run("$pua "), run("x", link = note("x"))).inOrder()
        }

        @Test
        fun `entities cannot forge a link — placeholders are cut before decoding`() {
            assertThat(runsOf("[[x]] &#xE000;0&#xE001;")).containsExactly(
                run("x", link = note("x")),
                run(" \uE0000\uE001"),
            ).inOrder()
        }
    }

    @Nested
    @DisplayName("bounded, whatever the note holds")
    inner class Bounds {

        private fun quoteDepth(blocks: List<PreviewBlock>): Int =
            blocks.maxOfOrNull { if (it is PreviewBlock.Quote) 1 + quoteDepth(it.blocks) else 0 } ?: 0

        /** How deep the first item of each list nests, and the block found at the bottom. */
        private fun deepestList(block: PreviewBlock, depth: Int = 0): Pair<Int, PreviewBlock> {
            if (block !is PreviewBlock.ListBlock) return depth to block
            val inner = block.items.first().blocks.lastOrNull() ?: return depth + 1 to block
            return deepestList(inner, depth + 1)
        }

        // ── The walk: past MAX_DEPTH, a container is shown as its source ────────────────────────

        @Test
        fun `forty lists nested by indentation stop at the cap, the rest shown as its source`() {
            // One marker per line: the scan lets it through, the walk must bound it.
            val source = (0 until 40).joinToString("\n") { "  ".repeat(it) + "- level $it" }
            assertThat(MarkdownPreviewReader.lineNesting(source)).isEqualTo(1)
            val (depth, bottom) = deepestList(read(source).single())
            assertThat(depth).isEqualTo(MarkdownPreviewReader.MAX_DEPTH)
            val rest = (bottom as PreviewBlock.Paragraph).runs.single().text
            assertThat(rest).startsWith("- level ${MarkdownPreviewReader.MAX_DEPTH}")
            assertThat(rest).endsWith("- level 39")
        }

        // ── The scan: past a limit, the note is not parsed at all ───────────────────────────────

        @Test
        fun `nesting on one line — eight markers are parsed, nine are not`() {
            val limit = MarkdownPreviewReader.MAX_LINE_NESTING
            assertThat(quoteDepth(read(">".repeat(limit) + " x"))).isEqualTo(limit)
            val over = ">".repeat(limit + 1) + " x"
            assertThat(read(over)).containsExactly(PreviewBlock.AsWritten(over))
            // 50 000 was an OutOfMemoryError in the parser (2026-09-25): it must not reach it.
            val measured = ">".repeat(50_000) + " x"
            assertThat(read(measured)).containsExactly(PreviewBlock.AsWritten(measured))
            val lists = "- ".repeat(limit + 1) + "x"
            assertThat(read(lists)).containsExactly(PreviewBlock.AsWritten(lists))
        }

        @Test
        fun `what counts as a nesting marker`() {
            assertThat(MarkdownPreviewReader.lineNesting("> > - 1. 2) + * x")).isEqualTo(7)
            assertThat(MarkdownPreviewReader.lineNesting("-x *y +z 1.x")).isEqualTo(0)
            assertThat(MarkdownPreviewReader.lineNesting("text > - not at the start")).isEqualTo(0)
            assertThat(MarkdownPreviewReader.lineNesting("123456789. x")).isEqualTo(1)
            // Ten digits is not a list marker for CommonMark.
            assertThat(MarkdownPreviewReader.lineNesting("1234567890. x")).isEqualTo(0)
            assertThat(MarkdownPreviewReader.lineNesting("a\n>>>\n> >")).isEqualTo(3)
        }

        @Test
        fun `brackets — the cap per block, and blocks counted apart`() {
            val limit = MarkdownPreviewReader.MAX_BRACKETS_PER_BLOCK
            val atLimit = "[a](".repeat(limit)
            assertThat(read(atLimit).single()).isInstanceOf(PreviewBlock.Paragraph::class.java)
            val over = "[a](".repeat(limit + 1)
            assertThat(read(over)).containsExactly(PreviewBlock.AsWritten(over))
            // The same brackets split by a blank line are two blocks, each under the cap.
            val split = "[a](".repeat(limit) + "\n\n" + "[a]("
            assertThat(read(split)).hasSize(2)
            // A line of spaces and tabs is blank too.
            assertThat(read("[a](".repeat(limit) + "\n  \t\n" + "[a](")).hasSize(2)
            // A line of no-break spaces is NOT: for the parser it continues the block.
            val nbsp = "[a](".repeat(limit) + "\n\u00A0\n" + "[a]("
            assertThat(read(nbsp)).containsExactly(PreviewBlock.AsWritten(nbsp))
        }

        @Test
        fun `a task box does not count as a bracket — a long checklist is previewed`() {
            val count = MarkdownPreviewReader.MAX_BRACKETS_PER_BLOCK * 3
            val list = read("- [ ] item\n".repeat(count)).single() as PreviewBlock.ListBlock
            assertThat(list.items).hasSize(count)
            assertThat(list.items.map { it.task }.toSet()).containsExactly(TaskMark.OPEN)
            // A bracket after the box still counts.
            val linked = "- [ ] [a](\n".repeat(MarkdownPreviewReader.MAX_BRACKETS_PER_BLOCK + 1)
            assertThat(read(linked)).containsExactly(PreviewBlock.AsWritten(linked))
        }

        @Test
        fun `brackets — the cap on the whole note`() {
            val perBlock = "[a](".repeat(MarkdownPreviewReader.MAX_BRACKETS_PER_BLOCK) + "\n\n"
            val blocks = MarkdownPreviewReader.MAX_BRACKETS / MarkdownPreviewReader.MAX_BRACKETS_PER_BLOCK
            assertThat(read(perBlock.repeat(blocks)).first()).isInstanceOf(PreviewBlock.Paragraph::class.java)
            val over = perBlock.repeat(blocks) + "["
            assertThat(read(over)).containsExactly(PreviewBlock.AsWritten(over))
        }

        @Test
        fun `wiki links do not count as brackets`() {
            val links = "[[x]] ".repeat(MarkdownPreviewReader.MAX_BRACKETS_PER_BLOCK)
            assertThat((read(links).single() as PreviewBlock.Paragraph).runs.count { it.link == note("x") })
                .isEqualTo(MarkdownPreviewReader.MAX_BRACKETS_PER_BLOCK)
        }

        @Test
        fun `length — up to the cap it is parsed, past it shown as written`() {
            val atLimit = "a".repeat(MarkdownPreviewReader.MAX_LENGTH)
            assertThat(read(atLimit).single()).isInstanceOf(PreviewBlock.Paragraph::class.java)
            val over = atLimit + "a"
            assertThat(read(over)).containsExactly(PreviewBlock.AsWritten(over))
        }

        @Test
        fun `a cancellation goes through — it is not a note too complex to preview`() {
            // The parser calls the token between its passes; the preview's token throws when the
            // user has left it. Caught with the other runtime exceptions, it would come back as a
            // note "shown as written" — for a note nobody asked to see any more.
            val thrown = assertThrows<CancellationException> {
                MarkdownPreviewReader.read("# a\n\nb [c](https://d.e)") { throw CancellationException("left") }
            }
            assertThat(thrown).hasMessageThat().isEqualTo("left")
            // The control: the same note, not cancelled, is read.
            assertThat(MarkdownPreviewReader.read("# a\n\nb [c](https://d.e)") {}).hasSize(2)
        }

        @Test
        fun `a table wider than the cap is shown as written`() {
            fun table(columns: Int) = "|a".repeat(columns) + "|\n" + "|-".repeat(columns) + "|"
            val atLimit = table(MarkdownPreviewReader.MAX_TABLE_COLUMNS)
            assertThat(
                (read(atLimit).single() as PreviewBlock.Table).alignments,
            ).hasSize(MarkdownPreviewReader.MAX_TABLE_COLUMNS)
            val over = table(MarkdownPreviewReader.MAX_TABLE_COLUMNS + 1)
            assertThat(read(over)).containsExactly(PreviewBlock.Code(over))
        }

        // ── What one lazy item may lay out at once (GPT-5.6 review, 2026-09-25) ────────────────

        @Test
        fun `a quote too heavy for one item is shown as written — at the cap it is drawn`() {
            fun quote(paragraphs: Int) = (1..paragraphs).joinToString("\n>\n") { "> p$it" }
            // A quote weighs itself plus its paragraphs.
            val atLimit = quote(MarkdownPreviewReader.MAX_BLOCKS_PER_ITEM - 1)
            assertThat(read(atLimit).single()).isInstanceOf(PreviewBlock.Quote::class.java)
            val over = quote(MarkdownPreviewReader.MAX_BLOCKS_PER_ITEM)
            assertThat(read(over)).containsExactly(PreviewBlock.Code(over))
        }

        @Test
        fun `a long top-level list stays a list — each of its items is its own lazy item`() {
            val count = MarkdownPreviewReader.MAX_BLOCKS_PER_ITEM * 4
            val list = read("- item\n".repeat(count)).single() as PreviewBlock.ListBlock
            assertThat(list.items).hasSize(count)
        }

        @Test
        fun `a top-level list item holding a huge sub-list is that item alone shown as written`() {
            val heavy = "- heavy\n" + "  - sub\n".repeat(MarkdownPreviewReader.MAX_BLOCKS_PER_ITEM)
            val list = read("- light\n$heavy- light again").single() as PreviewBlock.ListBlock

            assertThat(list.items).hasSize(3)
            assertThat(list.items[0].blocks).containsExactly(paragraph(run("light")))
            assertThat(list.items[1].blocks.single()).isInstanceOf(PreviewBlock.Code::class.java)
            assertThat((list.items[1].blocks.single() as PreviewBlock.Code).text).startsWith("- heavy")
            assertThat(list.items[2].blocks).containsExactly(paragraph(run("light again")))
        }
    }

    @Nested
    @DisplayName("addresses")
    inner class Addresses {

        @Test
        fun `a control character refuses an address whatever its scheme`() {
            assertThat(MarkdownPreviewReader.webTarget("https://a.b/\u0000x")).isNull()
            assertThat(MarkdownPreviewReader.webTarget("https://a.b/\u007Fx")).isNull()
            assertThat(MarkdownPreviewReader.webTarget("https://a.b/x")).isEqualTo(web("https://a.b/x"))
        }

        @Test
        fun `a scheme is required`() {
            assertThat(MarkdownPreviewReader.webTarget("a.b")).isNull()
            assertThat(MarkdownPreviewReader.webTarget("//a.b")).isNull()
            assertThat(MarkdownPreviewReader.webTarget("")).isNull()
        }

        /** A huge address overflowed the Binder transaction of `startActivity` (GPT-5.6 review). */
        @Test
        fun `an address longer than the cap is text, at the cap it is a link`() {
            val prefixe = "https://a.b/"
            val atLimit = prefixe + "x".repeat(MarkdownPreviewReader.MAX_LINK_LENGTH - prefixe.length)
            assertThat(MarkdownPreviewReader.webTarget(atLimit)).isEqualTo(web(atLimit))
            assertThat(MarkdownPreviewReader.webTarget(atLimit + "x")).isNull()
            // Through a note, the link is drawn as its text.
            assertThat(runsOf("[go](${atLimit}x)")).containsExactly(run("go"))
        }
    }
}
