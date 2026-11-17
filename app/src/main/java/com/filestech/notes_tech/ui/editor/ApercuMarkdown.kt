package com.filestech.notes_tech.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.filestech.notes_tech.domain.markdown.ListItem
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
 * @param onCase a tapped task box, given its `[`'s offset in the note (D-047, 3.5.0); `null` leaves the
 *   boxes drawn but inert — the legal pages. Before [onLien], so that a trailing lambda stays [onLien].
 * @param onLien a tapped link: a `[[Title]]` to open or create, or an address for another app.
 */
fun LazyListScope.apercuMarkdown(
    lecture: LectureDeLApercu,
    onCase: ((Int) -> Unit)? = null,
    onLien: (LinkTarget) -> Unit,
) {
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
                // A note shown as written is cut in pieces (see [aplatir]): its last one closes the block.
                val dernier = index == elements.lastIndex || elements[index + 1].partie == 0
                Box(marges.padding(top = ecart)) {
                    CompositionLocalProvider(LocalBasculeDeCase provides onCase) { Element(element, dernier, onLien) }
                }
            }
        }
    }
}

private fun typeDeContenu(element: ElementDApercu): String = when (element.bloc) {
    is PreviewBlock.AsWritten -> "morceau-tel-quel"
    is PreviewBlock.ListBlock -> "element-de-liste"
    is PreviewBlock.Table -> "ligne-de-tableau"
    else -> "bloc"
}

/**
 * The texts [Element] draws for [element], in the order it draws them, each with the key its drawing
 * reads its highlight by (3.2.0, "Find in note"): a paragraph's runs, a code block itself. Walks the
 * blocks as [Element] and [Bloc] do — keep the two together; their `when`s are both exhaustive, so a
 * new kind of block does not compile until both know it.
 */
internal fun textesDeLElement(element: ElementDApercu, ajouter: (cle: Any, texte: String) -> Unit) {
    when (val bloc = element.bloc) {
        is PreviewBlock.ListBlock -> bloc.items[element.partie].blocks.forEach { textesDuBloc(it, ajouter) }
        is PreviewBlock.Table -> {
            val cellules = if (element.partie == 0) bloc.header else bloc.rows[element.partie - 1]
            cellules.forEach { ajouter(it, texteDesRuns(it)) }
        }
        else -> textesDuBloc(bloc, ajouter)
    }
}

private fun textesDuBloc(bloc: PreviewBlock, ajouter: (cle: Any, texte: String) -> Unit) {
    when (bloc) {
        is PreviewBlock.Heading -> ajouter(bloc.runs, texteDesRuns(bloc.runs))
        is PreviewBlock.Paragraph -> ajouter(bloc.runs, texteDesRuns(bloc.runs))
        is PreviewBlock.ListBlock -> bloc.items.forEach { item -> item.blocks.forEach { textesDuBloc(it, ajouter) } }
        is PreviewBlock.Quote -> bloc.blocks.forEach { textesDuBloc(it, ajouter) }
        is PreviewBlock.Code -> ajouter(bloc, bloc.text)
        is PreviewBlock.RawHtml -> ajouter(bloc, bloc.text)
        is PreviewBlock.Table -> (listOf(bloc.header) + bloc.rows).forEach { ligne ->
            ligne.forEach { ajouter(it, texteDesRuns(it)) }
        }
        PreviewBlock.Rule -> Unit
        is PreviewBlock.AsWritten -> ajouter(bloc, bloc.text)
    }
}

/** What [texteDe] writes for [runs]: their texts, end to end. */
private fun texteDesRuns(runs: List<TextRun>): String = runs.joinToString("") { it.text }

/** @param dernier the last element of its block — for a note shown as written, its last piece. */
@Composable
private fun Element(element: ElementDApercu, dernier: Boolean, onLien: (LinkTarget) -> Unit) {
    when (val bloc = element.bloc) {
        is PreviewBlock.AsWritten -> TelQuel(bloc, premier = element.partie == 0, dernier = dernier)
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
        is PreviewBlock.Heading -> TexteSurligne(
            texte = texteDe(bloc.runs, onLien),
            cle = bloc.runs,
            style = styleDeTitre(bloc.level),
            modifier = Modifier.semantics { heading() },
        )
        is PreviewBlock.Paragraph -> TexteSurligne(
            texte = texteDe(bloc.runs, onLien),
            cle = bloc.runs,
            style = MaterialTheme.typography.bodyLarge,
        )
        is PreviewBlock.ListBlock -> Liste(bloc, onLien)
        is PreviewBlock.Quote -> Citation(bloc, onLien)
        is PreviewBlock.Code -> BlocDeCode(bloc.text, cle = bloc)
        // Shown as written, like code: it is markup the preview does not interpret.
        is PreviewBlock.RawHtml -> BlocDeCode(bloc.text, cle = bloc)
        is PreviewBlock.Table -> Tableau(bloc, onLien)
        PreviewBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        // Top level only, where [Element] draws it piece by piece: here, whole.
        is PreviewBlock.AsWritten -> TelQuel(bloc, premier = true, dernier = true)
    }
}

/**
 * A note the preview did not parse — see [MarkdownPreviewReader.read] — with the reason, then its
 * text, one piece of it per lazy item ([morceauxTelQuel]): the pieces touch, and only the first and
 * the last round the block's corners.
 */
@Composable
private fun TelQuel(bloc: PreviewBlock.AsWritten, premier: Boolean, dernier: Boolean) {
    val arrondi = 8.dp
    val forme = RoundedCornerShape(
        topStart = if (premier) arrondi else 0.dp,
        topEnd = if (premier) arrondi else 0.dp,
        bottomStart = if (dernier) arrondi else 0.dp,
        bottomEnd = if (dernier) arrondi else 0.dp,
    )
    val marges = PaddingValues(
        start = 12.dp,
        end = 12.dp,
        top = if (premier) 12.dp else 0.dp,
        bottom = if (dernier) 12.dp else 0.dp,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (premier) {
            Text(
                text = stringResource(R.string.note_preview_as_written),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BlocDeCode(bloc.text, cle = bloc, forme = forme, marges = marges)
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
    val tache = element.task
    Row(modifier) {
        // A numbered task keeps its number before its box, as on GitHub: 🔴 seen on the S9 (3.5.0), the
        // menu's "Checklist" turns `1. a` into `1. [ ] a` — the number is the user's, D-048 keeps it in the
        // text, and the reading dropped it. A bulleted task shows its box alone, the box being its bullet.
        if (tache == null || liste.ordered) {
            Box(PLACE_DE_LA_MARQUE) {
                // `Long`: a list may start at 999 999 999, and its tenth item must not wrap.
                val marque = if (liste.ordered) "${liste.start.toLong() + index}." else "•"
                Text(marque, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (tache != null) CaseDeTache(tache, element.taskOffset, libelle = libelleDeLElement(element))
        // D-047 (3.5.0): a done task's own text is greyed — the same `onSurfaceVariant` whose contrast
        // `CouleursDeNoteTest` measures on every note colour —, not struck through, which reads worse. An
        // open task sets its colour back, so that one nested in a done task is not greyed with it.
        val couleur = when (tache) {
            TaskMark.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
            TaskMark.OPEN -> MaterialTheme.colorScheme.onSurface
            null -> LocalContentColor.current
        }
        CompositionLocalProvider(LocalContentColor provides couleur) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ESPACE_DANS_UNE_LISTE)) {
                element.blocks.forEach { Bloc(it, onLien) }
            }
        }
    }
}

/**
 * What a screen reader says a task is about: the texts of its first block, whatever it is (a paragraph, a
 * heading, code…). Empty only for an item with no text at all — [CaseDeTache] then says its state.
 */
private fun libelleDeLElement(element: ListItem): String {
    val libelle = StringBuilder()
    element.blocks.firstOrNull()?.let { bloc ->
        textesDuBloc(bloc) { _, texte -> libelle.append(if (libelle.isEmpty()) texte else " $texte") }
    }
    return libelle.toString().trim()
}

/**
 * A GFM task box, drawn and **named**: notes_tech 2.0.9 draws the icon with no label, so a screen
 * reader passes over whether the task is done.
 *
 * Since 3.5.0 (D-047) a tap turns it over, where the screen allows it ([LocalBasculeDeCase]) and the box
 * is known in the note's text ([position]). ONE node then, as `04-PIEGES.md` asks of every control with a
 * state: the role, the state ("Done" / "To do") and what the task is about ([libelle] — its state when the
 * item has no text, never an empty name).
 *
 * ⚠️ The BOX is the control, not the whole row as in the settings (`04-PIEGES.md`): an item's text may hold
 * links, and nested items — a row that ticks would tick from a tap meant for them. The cost, accepted: the
 * task's text is read once more after the box, on its own node.
 *
 * ⚠️ 48 dp, Material's minimum target: a task row is taller, and its text further right, than a bullet's —
 * a checklist is made to be tapped. The icon stays on the item's first line, the rest of the target below.
 */
@Composable
private fun CaseDeTache(tache: TaskMark, position: Int?, libelle: String) {
    val (icone, etat) = when (tache) {
        TaskMark.DONE -> Icons.Outlined.CheckBox to R.string.note_preview_task_done
        TaskMark.OPEN -> Icons.Outlined.CheckBoxOutlineBlank to R.string.note_preview_task_open
    }
    val etatDit = stringResource(etat)
    val teinte = MaterialTheme.colorScheme.onSurfaceVariant
    val dessin = Modifier.padding(top = 2.dp).size(20.dp)
    val basculer = LocalBasculeDeCase.current
    if (basculer == null || position == null) {
        Box(PLACE_DE_LA_MARQUE) {
            Icon(icone, contentDescription = etatDit, tint = teinte, modifier = dessin)
        }
        return
    }
    // 🔴 Seen on the S9 and the S24 (3.5.0, Patrice: "it goes well past the box"): the press drew a grey
    // 48 dp square with the box in its top corner, down past the item's second line. The press now shows
    // on the box itself, as a round state layer — an Android checkbox's —, the 48 dp target unchanged.
    val appuis = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .size(CIBLE_DE_CASE)
            .toggleable(
                value = tache == TaskMark.DONE,
                interactionSource = appuis,
                indication = null,
                role = Role.Checkbox,
                onValueChange = { basculer(position) },
            )
            .semantics {
                contentDescription = libelle.ifBlank { etatDit }
                stateDescription = etatDit
            },
    ) {
        val appui = ripple(bounded = false, radius = RAYON_DE_L_APPUI)
        Icon(icone, contentDescription = null, tint = teinte, modifier = dessin.indication(appuis, appui))
    }
}

/**
 * What a box does when tapped, given the `[`'s offset in the note (D-047): `null` where boxes are only
 * shown — the legal pages, which go through the same reader.
 *
 * A composition local, unlike `onLien`, which every block takes as a parameter: links are in every kind of
 * text, the boxes only in list items, deep in the tree — through quotes and nested lists. A parameter would
 * cross every block for the one that reads it. Private: this file alone provides and reads it.
 */
private val LocalBasculeDeCase = compositionLocalOf<((Int) -> Unit)?> { null }

/** Material's minimum touch target. */
private val CIBLE_DE_CASE = 48.dp

/** The press around a box: Material's checkbox state layer, 40 dp across, centred on the box. */
private val RAYON_DE_L_APPUI = 20.dp

/** The place of a bullet, a number, or a box that cannot be tapped: before the item's text. */
private val PLACE_DE_LA_MARQUE = Modifier.widthIn(min = 24.dp).padding(end = 6.dp)

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
private fun BlocDeCode(
    texte: String,
    cle: PreviewBlock,
    forme: Shape = RoundedCornerShape(8.dp),
    marges: PaddingValues = PaddingValues(12.dp),
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = forme,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TexteSurligne(
            texte = remember(texte) { AnnotatedString(texte) },
            cle = cle,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(marges),
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
                TexteSurligne(
                    texte = texteDe(cellule, onLien),
                    cle = cellule,
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
