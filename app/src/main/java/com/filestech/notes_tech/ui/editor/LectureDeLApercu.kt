package com.filestech.notes_tech.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.filestech.notes_tech.core.text.DartTextSemantics
import com.filestech.notes_tech.domain.markdown.MarkdownPreviewReader
import com.filestech.notes_tech.domain.markdown.PreviewBlock
import com.filestech.notes_tech.domain.markdown.TaskBoxes
import com.filestech.notes_tech.domain.markdown.TaskMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

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
 * @param partie the item's index, in a list; in a table, 0 for the header and n for the n-th row; in
 *   a note shown as written, the piece's index ([morceauxTelQuel]); 0 for any other block. An element
 *   whose [partie] is 0 starts its block.
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
        if (bloc is PreviewBlock.AsWritten) {
            morceauxTelQuel(bloc.text).forEachIndexed { partie, morceau ->
                elements += ElementDApercu(PreviewBlock.AsWritten(morceau), partie)
            }
            return@forEach
        }
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
 * 🔴 **A note shown as written ([PreviewBlock.AsWritten]), in pieces of at most [max] characters —
 * one lazy item each.** Whole, it was ONE text, which Compose lays out and draws entirely, every line:
 * a 5 MiB note — the import's bound until 2026-10-30 — froze the app on opening, the heap full (measured on
 * the emulator, 2026-10-29). Past [MarkdownPreviewReader.MAX_LENGTH] every note is shown this way.
 *
 * A piece ends at a line break where there is one in its second half — the break itself, which the
 * next item's start already shows, is not kept —, else at [max], never inside a surrogate pair. A
 * search finds what the pieces draw: a word cut across two pieces is not found, as across two blocks.
 */
internal fun morceauxTelQuel(texte: String, max: Int = MORCEAU_TEL_QUEL_MAX): List<String> {
    if (texte.length <= max) return listOf(texte)
    val morceaux = ArrayList<String>(texte.length / max + 1)
    var debut = 0
    while (texte.length - debut > max) {
        val saut = texte.lastIndexOf('\n', debut + max - 1)
        if (saut >= debut + max / 2) {
            morceaux += texte.substring(debut, saut)
            debut = saut + 1
        } else {
            val fin = if (Character.isHighSurrogate(texte[debut + max - 1])) debut + max - 1 else debut + max
            morceaux += texte.substring(debut, fin)
            debut = fin
        }
    }
    morceaux += texte.substring(debut)
    return morceaux
}

/** Characters per piece of a note shown as written: a screen or two of monospace text. */
internal const val MORCEAU_TEL_QUEL_MAX = 2_000

/**
 * The reading of [nouvelle], made from [lue] — the reading of [ancienne] — when the only change between
 * the two is ONE box turned over: one character between `[` and `]`, from one mark to another, at a box
 * [lue] knows (D-047, 3.5.0). `null` for any other change: the note is then read again.
 *
 * 🔴 **Why patch instead of reading again.** A new text starts a new reading, which shows a "reading"
 * item first: the list would flash and lose its position at every tick — scrolled down a shopping list,
 * each box ticked would send the reader back to the top. A box turned over changes no structure: only the
 * item's mark differs, which is what this returns (checked against a full reading, on the JVM).
 *
 * Unchanged blocks are kept as they are, the very objects: the search's highlights are keyed by them.
 */
internal fun lectureApresUneCase(lue: LectureDeLApercu.Lue, ancienne: String, nouvelle: String): LectureDeLApercu.Lue? {
    val (position, marque) = caseRetournee(ancienne, nouvelle) ?: return null
    val faits = IdentityHashMap<PreviewBlock, PreviewBlock>()
    var trouvee = false
    val elements = lue.elements.map { element ->
        val bloc = faits.getOrPut(element.bloc) {
            avecLaMarque(element.bloc, position, marque).also { if (it !== element.bloc) trouvee = true }
        }
        if (bloc === element.bloc) element else element.copy(bloc = bloc)
    }
    return if (trouvee) LectureDeLApercu.Lue(elements) else null
}

/**
 * The box [nouvelle] turned over from [ancienne] — its `[`'s offset and its new mark —, or `null` when the
 * change is anything else: another length, more than one character, a character outside `[` and `]`, or
 * one that is no mark before or after (`[y]` is no box: the structure changes with it).
 */
private fun caseRetournee(ancienne: String, nouvelle: String): Pair<Int, TaskMark>? {
    if (ancienne.length != nouvelle.length) return null
    val ici = ancienne.indices.firstOrNull { ancienne[it] != nouvelle[it] } ?: return null
    val entreCrochets = ici > 0 && ici + 1 < nouvelle.length && nouvelle[ici - 1] == '[' && nouvelle[ici + 1] == ']'
    val seuleDifference = ancienne.regionMatches(ici + 1, nouvelle, ici + 1, ancienne.length - ici - 1)
    val etaitUneMarque = TaskBoxes.markOf(ancienne[ici]) != null
    val marque = TaskBoxes.markOf(nouvelle[ici])
    val retournee = entreCrochets && seuleDifference && etaitUneMarque
    return if (retournee && marque != null) (ici - 1) to marque else null
}

/**
 * [bloc] with the item whose box is at [position] marked [marque]; [bloc] itself when it holds none. An item
 * too heavy to draw block by block has no offset (`MarkdownPreviewReader`): its box is never found here, and
 * a change in its text reads the note again.
 */
private fun avecLaMarque(bloc: PreviewBlock, position: Int, marque: TaskMark): PreviewBlock = when (bloc) {
    is PreviewBlock.ListBlock -> {
        val items = bloc.items.map { item ->
            if (item.taskOffset == position) {
                item.copy(task = marque)
            } else {
                val blocs = item.blocks.map { avecLaMarque(it, position, marque) }
                if (blocs.indices.all { blocs[it] === item.blocks[it] }) item else item.copy(blocks = blocs)
            }
        }
        if (items.indices.all { items[it] === bloc.items[it] }) bloc else bloc.copy(items = items)
    }
    is PreviewBlock.Quote -> {
        val blocs = bloc.blocks.map { avecLaMarque(it, position, marque) }
        if (blocs.indices.all { blocs[it] === bloc.blocks[it] }) bloc else bloc.copy(blocks = blocs)
    }
    else -> bloc
}

/** The last reading made, and the text it was made of — not snapshot state: nothing draws from it. */
private class DerniereLecture {
    var source: String? = null
    var lue: LectureDeLApercu.Lue? = null
}

/**
 * Reads [source] for the preview, **off the main thread**: a pasted note can run to megabytes, and
 * the parse must not freeze the screen that shows it. The reader never throws — see
 * [MarkdownPreviewReader.read] — since a failure here would crash the editor holding the note, from a
 * coroutine nothing else watches.
 *
 * Keyed on [source]: a new text starts a new reading, never shows the blocks of the previous one —
 * with ONE exception since 3.5.0, a box ticked or unticked, patched in from the last reading: see
 * [lectureApresUneCase].
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
    val derniere = remember { DerniereLecture() }
    val lecture = remember(source) {
        val apresUneCase = derniere.lue?.let { lue -> derniere.source?.let { lectureApresUneCase(lue, it, source) } }
        mutableStateOf(
            when {
                vide -> LectureDeLApercu.Vide
                apresUneCase != null -> apresUneCase
                else -> LectureDeLApercu.EnCours
            },
        )
    }
    // Remembered once it is drawn, with its text: the next box ticked is patched in from it.
    SideEffect {
        (lecture.value as? LectureDeLApercu.Lue)?.let { lue ->
            derniere.source = source
            derniere.lue = lue
        }
    }
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
