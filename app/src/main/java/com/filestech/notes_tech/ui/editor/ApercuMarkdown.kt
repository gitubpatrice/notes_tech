package com.filestech.notes_tech.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.markdown.CellAlignment
import com.filestech.notes_tech.domain.markdown.LinkTarget
import com.filestech.notes_tech.domain.markdown.MarkdownPreviewReader
import com.filestech.notes_tech.domain.markdown.PreviewBlock
import com.filestech.notes_tech.domain.markdown.RunStyle
import com.filestech.notes_tech.domain.markdown.TaskMark
import com.filestech.notes_tech.domain.markdown.TextRun
import kotlinx.coroutines.delay

/**
 * The note's Markdown, drawn (D-024): the preview's items, in the editor's lazy list — the "Preview"
 * side of the Edit / Preview switch.
 *
 * What each construct becomes is decided by [MarkdownPreviewReader], on plain data tested on the
 * JVM; this file only draws it. Nothing here is selectable or editable: the preview is for reading,
 * as in notes_tech 2.0.9, and copying the Markdown stays in the editor's menu.
 *
 * ## 🔴 Lazy, because a note has no size limit
 *
 * One item per block — and one per item of a top-level list, one per row of a top-level table — so
 * that only what is on screen is laid out, as `flutter_markdown_plus`'s `ListView` does. A column of
 * every block would compose three thousand paragraphs to show twenty. What one item holds (a nested
 * list, a quote) stays a plain column, bounded by the reader (`MAX_BLOCKS_PER_ITEM`): a lazy list
 * inside a lazy item is measured with an infinite height, and Compose crashes on that (see
 * `LiensDeLaNote`).
 *
 * @param onLien a tapped link: a `[[Title]]` to open or create, or an address for another app.
 */
fun LazyListScope.apercuMarkdown(lecture: LectureDeLApercu, onLien: (LinkTarget) -> Unit) {
    val marges = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    when (lecture) {
        LectureDeLApercu.Vide -> item(contentType = "vide") {
            Text(
                text = stringResource(R.string.note_editor_preview_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = marges.padding(vertical = 12.dp),
            )
        }
        LectureDeLApercu.EnCours -> item(contentType = "lecture") { Lecture(marges) }
        is LectureDeLApercu.Lue -> {
            val elements = lecture.elements
            items(count = elements.size, contentType = { typeDeContenu(elements[it]) }) { index ->
                val element = elements[index]
                val ecart = when {
                    index == 0 -> ESPACE_EN_TETE
                    element.partie == 0 -> ESPACE_ENTRE_BLOCS
                    element.bloc is PreviewBlock.ListBlock -> ESPACE_DANS_UNE_LISTE
                    // The rows of a table touch: their cells' borders make the grid.
                    else -> 0.dp
                }
                Box(marges.padding(top = ecart)) { Element(element, onLien) }
            }
        }
    }
}

private fun typeDeContenu(element: ElementDApercu): String = when (element.bloc) {
    is PreviewBlock.ListBlock -> "element-de-liste"
    is PreviewBlock.Table -> "ligne-de-tableau"
    else -> "bloc"
}

@Composable
private fun Element(element: ElementDApercu, onLien: (LinkTarget) -> Unit) {
    when (val bloc = element.bloc) {
        is PreviewBlock.ListBlock -> ElementDeListe(bloc, element.partie, onLien)
        is PreviewBlock.Table -> {
            val cellules = if (element.partie == 0) bloc.header else bloc.rows[element.partie - 1]
            LigneDeTableau(
                cellules = cellules,
                alignements = bloc.alignments,
                entete = element.partie == 0,
                bord = MaterialTheme.colorScheme.outlineVariant,
                onLien = onLien,
            )
        }
        else -> Bloc(bloc, onLien)
    }
}

/**
 * While the note is read. Shown only past a short delay: an ordinary note is read within a frame,
 * and an indicator flashing on every switch to the preview would be noise.
 */
@Composable
private fun Lecture(modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(DELAI_AVANT_INDICATEUR_MS)
        visible = true
    }
    Box(modifier = modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        if (visible) {
            val chargement = stringResource(R.string.common_loading)
            CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = chargement })
        }
    }
}

@Composable
private fun Bloc(bloc: PreviewBlock, onLien: (LinkTarget) -> Unit) {
    // ⚠️ Exhaustive, no `else`: a new kind of block must not compile until it is drawn.
    when (bloc) {
        is PreviewBlock.Heading -> Text(
            text = texteDe(bloc.runs, onLien),
            style = styleDeTitre(bloc.level),
            modifier = Modifier.semantics { heading() },
        )
        is PreviewBlock.Paragraph -> Text(text = texteDe(bloc.runs, onLien), style = MaterialTheme.typography.bodyLarge)
        is PreviewBlock.ListBlock -> Liste(bloc, onLien)
        is PreviewBlock.Quote -> Citation(bloc, onLien)
        is PreviewBlock.Code -> BlocDeCode(bloc.text)
        // Shown as written, like code: it is markup the preview does not interpret.
        is PreviewBlock.RawHtml -> BlocDeCode(bloc.text)
        is PreviewBlock.Table -> Tableau(bloc, onLien)
        PreviewBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        is PreviewBlock.AsWritten -> TelQuel(bloc.text)
    }
}

/** A note the preview did not parse — see [MarkdownPreviewReader.read] — with the reason, then its text. */
@Composable
private fun TelQuel(texte: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.note_preview_as_written),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BlocDeCode(texte)
    }
}

/** The heading scale of `flutter_markdown_plus`'s `MarkdownStyleSheet.fromTheme`, on Material 3. */
@Composable
private fun styleDeTitre(niveau: Int): TextStyle = when (niveau) {
    1 -> MaterialTheme.typography.headlineSmall
    2 -> MaterialTheme.typography.titleLarge
    3 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

@Composable
private fun Liste(liste: PreviewBlock.ListBlock, onLien: (LinkTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ESPACE_DANS_UNE_LISTE)) {
        liste.items.indices.forEach { index -> ElementDeListe(liste, index, onLien) }
    }
}

/** One item of [liste] — a lazy item at the top level, a row of its column when nested. */
@Composable
private fun ElementDeListe(
    liste: PreviewBlock.ListBlock,
    index: Int,
    onLien: (LinkTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val element = liste.items[index]
    Row(modifier) {
        Box(Modifier.widthIn(min = 24.dp).padding(end = 6.dp)) {
            val tache = element.task
            when {
                tache != null -> CaseDeTache(tache)
                // `Long`: a list may start at 999 999 999, and its tenth item must not wrap.
                liste.ordered -> Text("${liste.start.toLong() + index}.", style = MaterialTheme.typography.bodyLarge)
                else -> Text("•", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ESPACE_DANS_UNE_LISTE)) {
            element.blocks.forEach { Bloc(it, onLien) }
        }
    }
}

/**
 * A GFM task box, drawn and **named**: notes_tech 2.0.9 draws the icon with no label, so a screen
 * reader passes over whether the task is done. Never toggled — the preview does not write.
 */
@Composable
private fun CaseDeTache(tache: TaskMark) {
    val (icone, etat) = when (tache) {
        TaskMark.DONE -> Icons.Outlined.CheckBox to R.string.note_preview_task_done
        TaskMark.OPEN -> Icons.Outlined.CheckBoxOutlineBlank to R.string.note_preview_task_open
    }
    Icon(
        imageVector = icone,
        contentDescription = stringResource(etat),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp).size(20.dp),
    )
}

@Composable
private fun Citation(citation: PreviewBlock.Quote, onLien: (LinkTarget) -> Unit) {
    val barre = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(color = barre, size = Size(LARGEUR_DE_BARRE.toPx(), size.height)) }
            .padding(start = 14.dp),
        verticalArrangement = Arrangement.spacedBy(ESPACE_ENTRE_BLOCS),
    ) {
        citation.blocks.forEach { Bloc(it, onLien) }
    }
}

/**
 * Code, and raw HTML: monospaced, **wrapped**. `flutter_markdown_plus` scrolls a long line
 * sideways; on a phone that hides the end of every line behind a gesture nothing announces.
 */
@Composable
private fun BlocDeCode(texte: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = texte,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun Tableau(tableau: PreviewBlock.Table, onLien: (LinkTarget) -> Unit) {
    val bord = MaterialTheme.colorScheme.outlineVariant
    // No border of its own: the cells' borders make the grid, as for a top-level table, whose rows
    // are separate lazy items and cannot share one.
    Column(Modifier.fillMaxWidth()) {
        LigneDeTableau(tableau.header, tableau.alignments, entete = true, bord = bord, onLien = onLien)
        tableau.rows.forEach { LigneDeTableau(it, tableau.alignments, entete = false, bord = bord, onLien = onLien) }
    }
}

/** Columns share the width, cells wrap — `flutter_markdown_plus`'s default `FlexColumnWidth`. */
@Composable
private fun LigneDeTableau(
    cellules: List<List<TextRun>>,
    alignements: List<CellAlignment>,
    entete: Boolean,
    bord: Color,
    onLien: (LinkTarget) -> Unit,
) {
    // `IntrinsicSize.Min`: every cell of a row as tall as its tallest, so the borders line up.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        cellules.forEachIndexed { index, cellule ->
            Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, bord).padding(6.dp)) {
                Text(
                    text = texteDe(cellule, onLien),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (entete) FontWeight.Bold else null,
                    ),
                    textAlign = when (alignements.getOrNull(index)) {
                        CellAlignment.CENTER -> TextAlign.Center
                        CellAlignment.END -> TextAlign.End
                        CellAlignment.START, null -> TextAlign.Start
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The runs of a paragraph, heading or cell, as one annotated text.
 *
 * ⚠️ **Consecutive runs with the same target are ONE link.** `[see *this*](…)` is two runs — one
 * plain, one italic — and two annotations would make a screen reader announce two links where the
 * user wrote one.
 */
@Composable
private fun texteDe(runs: List<TextRun>, onLien: (LinkTarget) -> Unit): AnnotatedString {
    val couleurs = MaterialTheme.colorScheme
    val styleDeLien =
        TextLinkStyles(style = SpanStyle(color = couleurs.primary, textDecoration = TextDecoration.Underline))
    val fondDeCode = couleurs.surfaceContainerHighest
    val attenue = couleurs.onSurfaceVariant
    return remember(runs, onLien, couleurs) {
        buildAnnotatedString {
            var debut = 0
            while (debut < runs.size) {
                val cible = runs[debut].link
                var fin = debut
                while (cible != null && fin + 1 < runs.size && runs[fin + 1].link == cible) fin++
                val morceaux = runs.subList(debut, fin + 1)
                if (cible == null) {
                    morceaux.forEach { withStyle(styleDe(it, fondDeCode, attenue)) { append(it.text) } }
                } else {
                    val lien = LinkAnnotation.Clickable(
                        tag = cible.toString(),
                        styles = styleDeLien,
                        linkInteractionListener = { onLien(cible) },
                    )
                    withLink(lien) {
                        morceaux.forEach { withStyle(styleDe(it, fondDeCode, attenue)) { append(it.text) } }
                    }
                }
                debut = fin + 1
            }
        }
    }
}

private fun styleDe(run: TextRun, fondDeCode: Color, attenue: Color): SpanStyle {
    val styles = run.styles
    return SpanStyle(
        fontWeight = if (RunStyle.BOLD in styles) FontWeight.Bold else null,
        fontStyle = if (RunStyle.ITALIC in styles || RunStyle.IMAGE in styles) FontStyle.Italic else null,
        textDecoration = if (RunStyle.STRIKETHROUGH in styles) TextDecoration.LineThrough else null,
        fontFamily = if (RunStyle.CODE in styles) FontFamily.Monospace else null,
        background = if (RunStyle.CODE in styles) fondDeCode else Color.Unspecified,
        // An image's stand-in text is muted, as 2.0.9's `onSurfaceVariant` italic.
        color = if (RunStyle.IMAGE in styles) attenue else Color.Unspecified,
    )
}

private val ESPACE_EN_TETE = 12.dp
private val ESPACE_ENTRE_BLOCS = 10.dp
private val ESPACE_DANS_UNE_LISTE = 4.dp
private val LARGEUR_DE_BARRE = 3.dp
private const val DELAI_AVANT_INDICATEUR_MS = 300L
