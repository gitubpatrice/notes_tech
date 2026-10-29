package com.filestech.notes_tech.ui.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.recherche.RechercheDansLeTexte
import com.filestech.notes_tech.domain.recherche.RechercheDansLeTexte.Plage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/*
 * "Find in note" (3.2.0, D-033; hcarrega's idea, issue #10): the occurrences of a search highlighted
 * in the note on screen, "3 / 12", previous and next — while reading as while writing, and opened
 * already filled when the note comes from a search result.
 *
 * What is searched is what the screen shows: the title and the body field while writing, the title
 * and the DRAWN Markdown while reading (a link's address, a `**`, are not on screen, so not found
 * there). Nothing is indexed nor written: a vault note is searched in the memory it is read from.
 */

/** The title, while reading and while writing: one key, the title being one text in both. */
internal object CleDuTitre

/** The body field, while writing. While reading, each text the preview draws is its own key. */
internal object CleDuCorps

/** What the search reads, the screen's own texts — never a copy of them. */
internal sealed interface SourceDeRecherche {
    val titre: String

    data class Ecriture(override val titre: String, val corps: String) : SourceDeRecherche

    /**
     * The preview's items, compared by IDENTITY: a reading is never changed, a new text makes a new
     * one, and comparing a long note's items one by one at every recomposition would cost its size.
     */
    class Lecture(override val titre: String, val elements: List<ElementDApercu>) : SourceDeRecherche {
        override fun equals(other: Any?): Boolean =
            other is Lecture && other.elements === elements && other.titre == titre

        override fun hashCode(): Int = 31 * System.identityHashCode(elements) + titre.hashCode()
    }
}

/**
 * One occurrence, where it is drawn: the text whose key is [cle], and for the preview the lazy item
 * holding it ([element]; -1 outside the list — the title, the body field).
 */
internal data class Occurrence(val cle: Any, val element: Int, val plage: Plage)

/** The answer to [mots] in [source]: the first [MAX_OCCURRENCES] occurrences, in the order shown. */
internal class ResultatDeRecherche(
    val source: SourceDeRecherche,
    val mots: List<String>,
    val occurrences: List<Occurrence>,
    /** More than [MAX_OCCURRENCES]: the counter says "1000+". */
    val tronque: Boolean,
) {
    private val parCle = IdentityHashMap<Any, MutableList<Plage>>().also { carte ->
        occurrences.forEach { carte.getOrPut(it.cle) { ArrayList() } += it.plage }
    }

    fun plagesDe(cle: Any): List<Plage> = parCle[cle].orEmpty()
}

/**
 * Looks for [mots] in [source], text by text, in the order the screen shows them. Pure, and off the
 * main thread by its caller: a note has no size limit.
 */
internal fun chercherDansLaNote(
    source: SourceDeRecherche,
    mots: List<String>,
    max: Int = MAX_OCCURRENCES,
    verifier: () -> Unit = {},
): ResultatDeRecherche {
    val occurrences = ArrayList<Occurrence>()
    var tronque = false
    fun chercher(cle: Any, element: Int, texte: String) {
        if (tronque) return
        // One more than what is left: finding it is what says the count is cut.
        for (plage in RechercheDansLeTexte.occurrences(texte, mots, max - occurrences.size + 1, verifier)) {
            if (occurrences.size == max) {
                tronque = true
                return
            }
            occurrences += Occurrence(cle, element, plage)
        }
    }
    chercher(CleDuTitre, element = -1, source.titre)
    when (source) {
        is SourceDeRecherche.Ecriture -> chercher(CleDuCorps, element = -1, source.corps)
        is SourceDeRecherche.Lecture -> source.elements.forEachIndexed { index, element ->
            textesDeLElement(element) { cle, texte -> chercher(cle, index, texte) }
        }
    }
    return ResultatDeRecherche(source, mots, occurrences, tronque)
}

/**
 * The bar's state: open or not, what is searched, the current occurrence, and whether a move to it
 * is owed. Saved: a rotation keeps the search — as the app's search keeps its own.
 */
@Stable
internal class EtatDeRechercheDansLaNote(ouverte: Boolean, saisie: String, index: Int, deplacer: Boolean) {
    var ouverte by mutableStateOf(ouverte)
        private set
    var saisie by mutableStateOf(saisie)
        private set
    private var index by mutableIntStateOf(index)

    /**
     * 🔴 **The screen moves to the current occurrence only when the user asked**: a new search, next,
     * previous, the arrival from a result. Typing in the body while the bar is open recomputes the
     * occurrences, and must not throw the cursor back to one at every keystroke.
     */
    var deplacementDemande by mutableStateOf(deplacer)
        private set

    /** The field takes the focus once, when the bar is opened from the menu — not on arrival. */
    var focaliser by mutableStateOf(false)
        private set

    /**
     * While reading: the occurrence the text holding it must bring into view, once its line is laid
     * out. Taken by that text, and only once — a lazy item composed again later (scrolled back to,
     * prefetched) must not pull the list back to it.
     */
    var aAmener by mutableStateOf<Occurrence?>(null)
        private set

    fun ouvrir() {
        ouverte = true
        focaliser = true
        deplacementDemande = true
    }

    fun fermer() {
        ouverte = false
        focaliser = false
        aAmener = null
    }

    fun amener(occurrence: Occurrence) {
        aAmener = occurrence
    }

    fun amenee(occurrence: Occurrence) {
        if (aAmener == occurrence) aAmener = null
    }

    fun saisir(texte: String) {
        saisie = texte
        index = 0
        deplacementDemande = true
    }

    /** The current occurrence's index among [total] — the occurrences shrink as the user types. */
    fun indexParmi(total: Int): Int = if (total == 0) 0 else index.coerceIn(0, total - 1)

    fun suivante(total: Int) {
        if (total == 0) return
        index = (indexParmi(total) + 1) % total
        deplacementDemande = true
    }

    fun precedente(total: Int) {
        if (total == 0) return
        index = (indexParmi(total) - 1 + total) % total
        deplacementDemande = true
    }

    fun deplacementFait() {
        deplacementDemande = false
    }

    fun focalise() {
        focaliser = false
    }

    companion object {
        /** A move owed is not saved: it was made before the rotation. */
        val Saver = listSaver<EtatDeRechercheDansLaNote, Any>(
            save = { listOf(it.ouverte, it.saisie, it.index) },
            restore = { EtatDeRechercheDansLaNote(it[0] as Boolean, it[1] as String, it[2] as Int, deplacer = false) },
        )
    }
}

/** [initiale]: the search of the result the note was opened from — the bar then opens with it. */
@Composable
internal fun rememberEtatDeRechercheDansLaNote(initiale: String?): EtatDeRechercheDansLaNote =
    rememberSaveable(saver = EtatDeRechercheDansLaNote.Saver) {
        val ouverte = !initiale.isNullOrBlank()
        EtatDeRechercheDansLaNote(ouverte, initiale.orEmpty(), index = 0, deplacer = ouverte)
    }

/**
 * The answer to the bar's search in [source], computed off the main thread — or `null` while there
 * is none for THIS source and THESE words: an answer computed for another text is never drawn, its
 * positions belonging to that text.
 */
@Composable
internal fun rememberResultatDeRecherche(
    etat: EtatDeRechercheDansLaNote,
    source: SourceDeRecherche?,
): ResultatDeRecherche? {
    val mots = remember(etat.saisie) { RechercheDansLeTexte.mots(etat.saisie) }
    val resultat = remember { mutableStateOf<ResultatDeRecherche?>(null) }
    LaunchedEffect(source, mots, etat.ouverte) {
        if (!etat.ouverte || source == null || mots.isEmpty()) {
            resultat.value = null
            return@LaunchedEffect
        }
        // A new text or new words cancel this effect; `ensureActive` hands that to the search.
        resultat.value = withContext(Dispatchers.Default) { chercherDansLaNote(source, mots) { ensureActive() } }
    }
    return resultat.value?.takeIf { it.source == source && it.mots == mots }
}

/**
 * What the bar searches now: the fields while writing, the drawn note while reading — or `null`
 * while there is nothing to search yet. A note still being read is not searched by its title alone:
 * the move owed on arrival from a result would be spent there before the text is in.
 */
internal fun sourceDeRecherche(
    etat: EtatDeRechercheDansLaNote,
    noteOuverte: Boolean,
    lecture: Boolean,
    state: EditorUiState,
    rendu: LectureDeLApercu,
): SourceDeRecherche? = when {
    !etat.ouverte || !noteOuverte -> null
    !lecture -> SourceDeRecherche.Ecriture(state.titre.text, state.content.text)
    rendu is LectureDeLApercu.Lue -> SourceDeRecherche.Lecture(state.titre.text, rendu.elements)
    rendu is LectureDeLApercu.Vide -> SourceDeRecherche.Lecture(state.titre.text, emptyList())
    else -> null
}

/**
 * Takes the screen to the current occurrence when a move is owed (see
 * [EtatDeRechercheDansLaNote.deplacementDemande]), once the answer is in. While writing, the cursor
 * goes to it, and the field scrolls itself to its cursor; while reading, the list goes to the item
 * holding it, and the text there brings the line into view ([TexteSurligne]).
 */
@Composable
internal fun DeplacementDeLaRecherche(
    recherche: EtatDeRechercheDansLaNote,
    resultat: ResultatDeRecherche?,
    courante: Occurrence?,
    lecture: Boolean,
    defilement: LazyListState,
    onPlacerLeCurseur: (Occurrence) -> Unit,
) {
    val placer by rememberUpdatedState(onPlacerLeCurseur)
    LaunchedEffect(recherche.deplacementDemande, resultat) {
        if (!recherche.deplacementDemande || resultat == null) return@LaunchedEffect
        recherche.deplacementFait()
        val cible = courante ?: return@LaunchedEffect
        if (!lecture) {
            placer(cible)
            return@LaunchedEffect
        }
        if (cible.element >= 0 && defilement.layoutInfo.visibleItemsInfo.none { it.index == cible.element }) {
            defilement.scrollToItem(cible.element)
        }
        recherche.amener(cible)
    }
}

/** The highlight of the text field whose key is [cle] — none while the bar is closed. */
@Composable
internal fun surlignageDuChamp(cle: Any): VisualTransformation {
    val surlignage = LocalSurlignage.current
    val plages = surlignage.plagesDe(cle)
    val courante = surlignage.couranteDans(cle)
    val styles = stylesDeSurlignage()
    return remember(plages, courante, styles) {
        if (plages.isEmpty()) VisualTransformation.None else TransformationDeSurlignage(plages, courante, styles)
    }
}

/**
 * What the texts of the note highlight: every occurrence, and the current one more strongly; and,
 * while reading, the occurrence to bring into view ([aAmener]), reported done through [onAmenee].
 */
@Immutable
internal class Surlignage(
    private val resultat: ResultatDeRecherche?,
    val courante: Occurrence?,
    val aAmener: Occurrence? = null,
    val onAmenee: (Occurrence) -> Unit = {},
) {
    fun plagesDe(cle: Any): List<Plage> = resultat?.plagesDe(cle).orEmpty()

    /** The current occurrence's range, if it is in the text whose key is [cle]. */
    fun couranteDans(cle: Any): Plage? = courante?.takeIf { it.cle === cle }?.plage

    companion object {
        val AUCUN = Surlignage(null, null)
    }
}

/** Read by the preview's texts: the legal pages draw the same preview, and highlight nothing. */
internal val LocalSurlignage = compositionLocalOf { Surlignage.AUCUN }

/** The highlight's two styles, from the theme: readable in light and dark, on every note colour. */
@Immutable
internal data class StylesDeSurlignage(val autre: SpanStyle, val courante: SpanStyle)

@Composable
internal fun stylesDeSurlignage(): StylesDeSurlignage {
    val schema = MaterialTheme.colorScheme
    return remember(schema) {
        StylesDeSurlignage(
            autre = SpanStyle(background = schema.tertiaryContainer, color = schema.onTertiaryContainer),
            courante = SpanStyle(background = schema.tertiary, color = schema.onTertiary),
        )
    }
}

/** [this] with [plages] highlighted; a range past its end — never expected — is left out. */
internal fun AnnotatedString.surligne(
    plages: List<Plage>,
    courante: Plage?,
    styles: StylesDeSurlignage,
): AnnotatedString {
    if (plages.isEmpty()) return this
    val texte = this
    return buildAnnotatedString {
        append(texte)
        plages.forEach { plage ->
            if (plage.fin <= texte.length) {
                addStyle(if (plage == courante) styles.courante else styles.autre, plage.debut, plage.fin)
            }
        }
    }
}

/**
 * A text of the note being read, its occurrences highlighted — [cle] is how the search knows it —
 * and the current one brought into view when the bar moved to it: the list was scrolled to the item,
 * this finishes the move inside a block taller than the screen.
 */
@Composable
internal fun TexteSurligne(
    texte: AnnotatedString,
    cle: Any,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontStyle: FontStyle? = null,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val surlignage = LocalSurlignage.current
    val plages = surlignage.plagesDe(cle)
    val courante = surlignage.couranteDans(cle)
    val styles = stylesDeSurlignage()
    val affiche = remember(texte, plages, courante, styles) { texte.surligne(plages, courante, styles) }
    val requete = remember { BringIntoViewRequester() }
    val mise = remember { mutableStateOf<TextLayoutResult?>(null) }
    val aAmener = surlignage.aAmener?.takeIf { it.cle === cle }
    if (aAmener != null) {
        LaunchedEffect(aAmener) {
            val disposition = snapshotFlow { mise.value }.filterNotNull().first()
            val plage = aAmener.plage
            if (plage.fin <= disposition.layoutInput.text.length) {
                requete.bringIntoView(disposition.getBoundingBox(plage.debut))
            }
            surlignage.onAmenee(aAmener)
        }
    }
    Text(
        text = affiche,
        style = style,
        color = color,
        fontStyle = fontStyle,
        textAlign = textAlign,
        onTextLayout = { mise.value = it },
        modifier = modifier.bringIntoViewRequester(requete),
    )
}

/** The highlight on a text field: styles only, so every offset maps to itself. */
internal data class TransformationDeSurlignage(
    private val plages: List<Plage>,
    private val courante: Plage?,
    private val styles: StylesDeSurlignage,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(text.surligne(plages, courante, styles), OffsetMapping.Identity)
}

/** The bar, in place of the editor's top bar while it is open. */
@Composable
internal fun BarreDeRechercheDansLaNote(etat: EtatDeRechercheDansLaNote, resultat: ResultatDeRecherche?) {
    val total = resultat?.occurrences?.size ?: 0
    val champ = remember { FocusRequester() }
    LaunchedEffect(etat.focaliser) {
        if (etat.focaliser) {
            champ.requestFocus()
            etat.focalise()
        }
    }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = etat::fermer) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.note_find_close))
            }
        },
        title = {
            // `label`, not `placeholder`: a placeholder goes at the first letter, and the field is
            // then announced by its text alone — the editor's fields learnt it (`champsDeSaisieSansNom`).
            TextField(
                value = etat.saisie,
                onValueChange = etat::saisir,
                label = { Text(stringResource(R.string.note_editor_menu_find)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { etat.suivante(total) }),
                colors = champSansDecor(),
                modifier = Modifier.fillMaxWidth().focusRequester(champ),
            )
        },
        actions = {
            if (resultat != null) Compteur(etat.indexParmi(total), total, resultat.tronque)
            IconButton(onClick = { etat.precedente(total) }, enabled = total > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.note_find_previous))
            }
            IconButton(onClick = { etat.suivante(total) }, enabled = total > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.note_find_next))
            }
        },
    )
}

/** "3 / 12", said in words, and said again when it changes. */
@Composable
private fun Compteur(index: Int, total: Int, tronque: Boolean) {
    val visible = when {
        total == 0 -> stringResource(R.string.note_find_count, 0, 0)
        tronque -> stringResource(R.string.note_find_count_more, index + 1, total)
        else -> stringResource(R.string.note_find_count, index + 1, total)
    }
    val dit = when {
        total == 0 -> stringResource(R.string.note_find_none)
        tronque -> stringResource(R.string.note_find_count_more_description, index + 1, total)
        else -> stringResource(R.string.note_find_count_description, index + 1, total)
    }
    Text(
        text = visible,
        style = MaterialTheme.typography.labelLarge,
        color = if (total == 0) MaterialTheme.colorScheme.error else Color.Unspecified,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .semantics {
                contentDescription = dit
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/** Past this, the count shows "1000+" and the occurrences after it are neither listed nor drawn. */
internal const val MAX_OCCURRENCES = 1000
