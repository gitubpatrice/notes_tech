package com.filestech.notes_tech.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.filestech.notes_tech.core.text.DartTextSemantics
import com.filestech.notes_tech.domain.markdown.MarkdownPreviewReader
import com.filestech.notes_tech.domain.markdown.PreviewBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Where the Markdown preview of a note stands (D-024): nothing to show, being read, or read. */
sealed interface LectureDeLApercu {
    /** Nothing but spaces: 2.0.9 tests `data.trim().isEmpty`, with Dart's set of spaces. */
    data object Vide : LectureDeLApercu

    data object EnCours : LectureDeLApercu

    /** @param elements the preview's lazy items, prepared with the reading — see [aplatir]. */
    data class Lue(val elements: List<ElementDApercu>) : LectureDeLApercu
}

/**
 * One item of the preview's lazy list: a whole block — or one item of a top-level list, or one row of
 * a top-level table, so that a long list or table is laid out as it scrolls, not all at once.
 *
 * @param partie the item's index, in a list; in a table, 0 for the header and n for the n-th row;
 *   0 for any other block. An element whose [partie] is 0 starts its block.
 */
data class ElementDApercu(val bloc: PreviewBlock, val partie: Int)

/**
 * The preview's lazy items, in order.
 *
 * ⚠️ Prepared **with the reading, off the main thread**, so that the lazy list walks it with ONE
 * `items(count)`. Declaring an item per block while composing ran on the main thread, once per block
 * — a note of 500 KB of short paragraphs is over a hundred thousand of them (GPT-5.6 review,
 * 2026-09-25).
 */
fun aplatir(blocs: List<PreviewBlock>): List<ElementDApercu> {
    val elements = ArrayList<ElementDApercu>(blocs.size)
    blocs.forEach { bloc ->
        val parties = when (bloc) {
            is PreviewBlock.ListBlock -> bloc.items.size
            is PreviewBlock.Table -> 1 + bloc.rows.size
            else -> 1
        }
        for (partie in 0 until parties) elements += ElementDApercu(bloc, partie)
    }
    return elements
}

/**
 * Reads [source] for the preview, **off the main thread**: a pasted note can run to megabytes, and
 * the parse must not freeze the screen that shows it. The reader never throws — see
 * [MarkdownPreviewReader.read] — since a failure here would crash the editor holding the note, from a
 * coroutine nothing else watches.
 *
 * Keyed on [source]: a new text starts a new reading, never shows the blocks of the previous one.
 *
 * @param actif `true` while the preview is shown. Called in both modes, so that a reading survives a
 *   trip to Edit and back when nothing was typed: the preview then comes back at once, **at the
 *   position it was left at** — re-reading would show one "reading" item first, and the list would
 *   lose its position on it. Nothing is read while inactive: typing in Edit does not parse the note.
 */
@Composable
fun rememberLectureDeLApercu(source: String, actif: Boolean): LectureDeLApercu {
    // Asked on every keystroke in Edit, where this is called too: no copy of the note.
    val vide = DartTextSemantics.isBlank(source)
    val lecture = remember(source) { mutableStateOf(if (vide) LectureDeLApercu.Vide else LectureDeLApercu.EnCours) }
    LaunchedEffect(source, actif) {
        if (vide || !actif || lecture.value is LectureDeLApercu.Lue) return@LaunchedEffect
        // Leaving the preview cancels this effect; `ensureActive` hands that to the parser, which
        // then stops between two of its passes instead of finishing a reading nobody will see.
        val elements = withContext(Dispatchers.Default) {
            aplatir(MarkdownPreviewReader.read(source) { ensureActive() })
        }
        lecture.value = LectureDeLApercu.Lue(elements)
    }
    return lecture.value
}
