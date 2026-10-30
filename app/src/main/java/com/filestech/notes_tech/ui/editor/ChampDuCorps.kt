package com.filestech.notes_tech.ui.editor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.DragEvent
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextSnapshot
import android.view.textclassifier.TextClassifier
import android.widget.EditText
import android.widget.Scroller
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.children
import androidx.core.view.doOnAttach
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.widget.TextViewCompat
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.recherche.RechercheDansLeTexte.Plage
import com.filestech.notes_tech.ui.secure.LocalCopieDeSaisie
import com.filestech.notes_tech.ui.secure.LocalSaisieProtegee
import com.filestech.notes_tech.ui.secure.copierDepuisUnChamp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The body field's tag, for the screen's tests. */
internal const val TAG_DU_CHAMP_DU_CORPS = "champ-du-corps"

/**
 * 🔴 **The note's body while writing: Android's own `EditText`, not a Compose text field (3.2.0).**
 *
 * Compose draws every line of a text, on screen or not: its `TextAndroidCanvas.getClipBounds` answers
 * a clip of infinite height (bytecode of ui-text 1.12.1, read on 2026-10-30), so that a text recorded
 * once scrolls without being drawn again. A text field is drawn again at every keystroke and every
 * blink of its cursor — a long note, line by line, each time. Measured on the S9 (Android 10), release
 * build, ten letters typed, time per frame (median): 20 000 characters 9 ms, 50 000 150 ms,
 * 100 000 950 ms, 200 000 3.35 s — the keyboard came up after 9 s. Compose's newer `TextFieldState`
 * field did no better (1.2 s at 100 000). An `EditText` lays out again only the paragraphs a change
 * touches and draws only the lines on screen: 13 ms at 100 000 characters, 15 ms at 200 000.
 *
 * The note's state keeps its [TextFieldValue]: what the field shows is synchronised with it both
 * ways — see [ChampNatifDuCorps.appliquer] — so the links, the dictation, the search and the save are
 * unchanged.
 *
 * ⚠️ What a vault note's fields get from `SaisieDeCoffre` reaches Compose's fields only; this field
 * applies the same protections itself, and closes what an `EditText` opens that Compose's field did
 * not — see [ChampNatifDuCorps].
 *
 * @param focaliser `true` to put the focus and the keyboard in the field — "Edit" means "write";
 *   [onFocalise] is called once done.
 */
@Composable
internal fun ChampDuCorps(
    valeur: TextFieldValue,
    onValeurChange: (TextFieldValue) -> Unit,
    focaliser: Boolean,
    onFocalise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val protegee = LocalSaisieProtegee.current
    val natif = LocalClipboard.current
    val copieDeSaisie = LocalCopieDeSaisie.current
    // Outside `SaisieDeCoffre` — the screen's tests — a plain clip, as there.
    val copieNative: suspend (String) -> Boolean = remember(natif) {
        { texte -> copierDepuisUnChamp(texte, protegee = false, natif = natif, deposer = {}, surEchec = {}) }
    }
    val copie by rememberUpdatedState(copieDeSaisie ?: copieNative)
    val portee = rememberCoroutineScope()
    val surlignage = LocalSurlignage.current
    val plages = surlignage.plagesDe(CleDuCorps)
    val courante = surlignage.couranteDans(CleDuCorps)
    val schema = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge
    val libelle = stringResource(R.string.note_editor_content)
    val indication = stringResource(R.string.note_editor_content_hint)
    var focalise by remember { mutableStateOf(false) }
    val vue = remember { mutableStateOf<ChampNatifDuCorps?>(null) }
    val auFocus by rememberUpdatedState(onFocalise)

    LaunchedEffect(focaliser) {
        if (!focaliser) return@LaunchedEffect
        snapshotFlow { vue.value }.filterNotNull().first().focaliser()
        auFocus()
    }

    // Material 3's filled field, as the title's: the label sits small above the text once the field
    // holds the focus or a text; empty and idle, it is the field's own hint, and the placeholder takes
    // its place once the field is focused.
    val flottant = focalise || valeur.text.isNotEmpty()
    // The tag on the whole field, its label included — as Compose's field, whose label is inside it.
    Column(modifier.testTag(TAG_DU_CHAMP_DU_CORPS)) {
        if (flottant) {
            Text(
                text = libelle,
                style = MaterialTheme.typography.bodySmall,
                color = if (focalise) schema.primary else schema.onSurfaceVariant,
                // The field itself carries this name for TalkBack: read once, not twice.
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).clearAndSetSemantics {},
            )
        }
        AndroidView(
            factory = { contexte -> ChampNatifDuCorps(contexte).also { vue.value = it } },
            update = { champ ->
                champ.onValeur = onValeurChange
                champ.onFocus = { focalise = it }
                champ.ecrire = { texte, fini -> portee.launch { fini(copie(texte)) } }
                champ.protegee = protegee
                champ.apparence(Apparence.de(style, schema, champ.context, enHaut = !flottant))
                champ.indications(indication = if (focalise) indication else libelle, nom = libelle)
                champ.appliquer(valeur)
                champ.surligner(plages, courante, CouleursDeSurlignage.de(schema))
            },
            onRelease = { champ ->
                vue.value = null
                champ.relacher()
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** What the field looks like: the title's style and colours, from the theme. */
internal data class Apparence(
    val tailleSp: Float,
    val interligneSp: Float,
    val espacementEm: Float,
    val texte: Int,
    val indication: Int,
    val selection: Int,
    val curseur: Int,
    val margeDuHautPx: Int,
    val margeDeCotePx: Int,
    val margeDuBasPx: Int,
) {
    companion object {
        fun de(style: TextStyle, schema: ColorScheme, contexte: Context, enHaut: Boolean): Apparence {
            val taille = if (style.fontSize.isSp) style.fontSize.value else TAILLE_PAR_DEFAUT_SP
            val interligne = if (style.lineHeight.isSp) style.lineHeight.value else taille * INTERLIGNE_PAR_DEFAUT
            val espacement = if (style.letterSpacing.isSp) style.letterSpacing.value / taille else 0f
            fun px(dp: Float) =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, contexte.resources.displayMetrics).toInt()
            return Apparence(
                tailleSp = taille,
                interligneSp = interligne,
                espacementEm = espacement,
                texte = schema.onSurface.toArgb(),
                indication = schema.onSurfaceVariant.toArgb(),
                // Compose's selection: the primary colour at 40 %.
                selection = schema.primary.copy(alpha = ALPHA_DE_LA_SELECTION).toArgb(),
                curseur = schema.primary.toArgb(),
                // The label above takes the place of this margin once it shows.
                margeDuHautPx = if (enHaut) px(MARGE_DU_HAUT_DP) else 0,
                margeDeCotePx = px(MARGE_DE_COTE_DP),
                margeDuBasPx = px(MARGE_DU_BAS_DP),
            )
        }

        private const val TAILLE_PAR_DEFAUT_SP = 16f
        private const val INTERLIGNE_PAR_DEFAUT = 1.5f
        private const val ALPHA_DE_LA_SELECTION = 0.4f
        private const val MARGE_DU_HAUT_DP = 16f
        private const val MARGE_DE_COTE_DP = 16f
        private const val MARGE_DU_BAS_DP = 8f
    }
}

/** The search's two highlights — those of [stylesDeSurlignage] — as colours a span takes. */
internal data class CouleursDeSurlignage(val fond: Int, val encre: Int, val fondCourante: Int, val encreCourante: Int) {
    companion object {
        fun de(schema: ColorScheme) = CouleursDeSurlignage(
            fond = schema.tertiaryContainer.toArgb(),
            encre = schema.onTertiaryContainer.toArgb(),
            fondCourante = schema.tertiary.toArgb(),
            encreCourante = schema.onTertiary.toArgb(),
        )
    }
}

/**
 * The `EditText` of [ChampDuCorps].
 *
 * ## Closed here, open in any `EditText`
 *
 * - 🔴 **Copy and Cut go through [ecrire]** — the clipboard of `SaisieDeCoffre`: marked sensitive,
 *   cleared, purged by the panic for a vault note (audit 2026-09-26, F1). The text is written FIRST,
 *   and a Cut removes it only once written: a refused copy loses nothing.
 * - 🔴 **The toolbar keeps editing entries only** ([MenuDuChampDuCorps]): no Share, no Autofill, no
 *   other app's `PROCESS_TEXT` — on Android 10 and below, every app answering it is listed, and
 *   "Translate" sent the text online (3.1.1). The classifier is [TextClassifier.NO_OP] here too.
 * - 🔴 **A vault note's keyboard is asked not to learn** (F4): `IME_FLAG_NO_PERSONALIZED_LEARNING`,
 *   as `SaisieDeCoffre` asks it for Compose's fields.
 * - 🔴 **A vault note's selection cannot be dragged to another app**: dragging is copying, with none
 *   of [ecrire]'s protections. A drop INTO the field arrives as plain text, as a paste does.
 * - 🔴 **The text is never written to the saved state**: an `EditText` saves its text with the
 *   activity's state — a vault note's plaintext in a Bundle. No id, `isSaveEnabled = false`.
 * - **Not offered to autofill, nor to content capture.** The view's content capture flag exists from
 *   Android 11; on Android 10, a vault note's window carries FLAG_SECURE, which keeps capture off.
 * - **No spell checker**: Android's checks the whole text, word by word, through a service — a
 *   200 000-character note sent piece by piece, and the decrypted text of a vault note with it.
 * - **No fullscreen keyboard** in landscape: its extracted text would copy the whole note.
 * - 🔴 **The keyboard reads a window around the cursor**, not the note: see [ConnexionBornee].
 */
@SuppressLint("ViewConstructor")
internal class ChampNatifDuCorps(context: Context) : EditText(context) {

    var onValeur: (TextFieldValue) -> Unit = {}
    var onFocus: (Boolean) -> Unit = {}

    /** Writes a copied text to the clipboard, then says whether it was written. */
    var ecrire: (texte: String, fini: (Boolean) -> Unit) -> Unit = { _, fini -> fini(false) }

    /** A vault note: see the class. */
    var protegee: Boolean = false
        set(valeur) {
            if (field == valeur) return
            field = valeur
            // The keyboard reads the flag when its connection opens: open it again.
            if (hasFocus()) clavier()?.restartInput(this)
        }

    /**
     * Set once the constructor is done. `TextView`'s own constructor calls overridden methods —
     * [onSelectionChanged] among them — before this class's fields exist.
     */
    private var pret = false

    /** What the state holds, as last sent by the field or applied from the state. */
    private var connue = TextFieldValue()

    /**
     * Values sent and not seen back yet. 🔴 The state reaches the screen a frame or more later, and a
     * recomposition can still carry a value this field sent BEFORE the last keystroke: applied, it
     * would undo that keystroke. A value seen here, compared by identity — the state keeps the very
     * object it was given —, is an echo, never a change.
     */
    private val envoyees = ArrayDeque<TextFieldValue>()
    private var enApplication = false
    private var enModification = false
    private var dernierToucher = -1
    private var menu: ActionMode? = null
    private var apparence: Apparence? = null
    private var indication: String? = null
    private var nom: String = ""
    private val spans = ArrayList<Any>()
    private var plagesSurlignees: List<Plage>? = null
    private var couranteSurlignee: Plage? = null
    private var couleursSurlignees: CouleursDeSurlignage? = null

    init {
        inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        gravity = Gravity.TOP or Gravity.START
        background = null
        includeFontPadding = false
        isVerticalScrollBarEnabled = true
        // An EditText always "freezes" its text (`getFreezesText` is true whatever is set): what keeps it
        // out of the saved state is no id and this.
        isSaveEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextClassifier(TextClassifier.NO_OP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        customSelectionActionModeCallback = FiltreDuMenu()
        customInsertionActionModeCallback = FiltreDuMenu()
        addTextChangedListener(Surveillance())
        setOnFocusChangeListener { _, focus -> onFocus(focus) }
        // A press the listener consumes is not the platform's: no drag starts (see [glisserRetenu]).
        setOnLongClickListener {
            appuiLong = true
            glisserRetenu(protegee, selectionStart, selectionEnd, dernierToucher)
        }
        ViewCompat.setAccessibilityDelegate(
            this,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(hote: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(hote, info)
                    // The field's name, whatever it holds: the label shown above is hidden from TalkBack.
                    info.hintText = nom
                }
            },
        )
        pret = true
    }

    // ------------------------------------------------------------------ the state, both ways

    /**
     * Shows [valeur], the state's. An echo of what this field sent is ignored (see [envoyees]); a
     * change from elsewhere — a link, a dictation, the search's cursor — is applied as the smallest
     * replacement ([DifferenceDeTexte]), the cursor brought into view.
     */
    fun appliquer(valeur: TextFieldValue) {
        if (valeur === connue) {
            envoyees.clear()
            return
        }
        val echo = envoyees.indexOfFirst { it === valeur }
        if (echo >= 0) {
            // Older ones are past; this one stays: another recomposition may carry it again before the
            // latest is in (GPT-5.6 review, 2026-10-30 — dropped at once, its second sight undid a keystroke).
            repeat(echo) { envoyees.removeFirst() }
            return
        }
        envoyees.clear()
        val editable = text
        var texteChange = false
        var selectionChange = false
        enApplication = true
        try {
            DifferenceDeTexte.entre(editable, valeur.text)?.let { d ->
                editable.replace(d.debut, d.finAvant, valeur.text, d.debut, d.finApres)
                texteChange = true
            }
            val longueur = editable.length
            val debut = valeur.selection.start.coerceIn(0, longueur)
            val fin = valeur.selection.end.coerceIn(0, longueur)
            if (selectionStart != debut || selectionEnd != fin) {
                setSelection(debut, fin)
                selectionChange = true
            }
        } finally {
            enApplication = false
        }
        // The very object: the next recomposition carries it again, and must find it known.
        connue = valeur
        // A text changed under the keyboard: its composition and its idea of the text are stale.
        if (texteChange && hasFocus()) clavier()?.restartInput(this)
        if (texteChange || selectionChange) amenerLeCurseur()
    }

    private fun envoyer(valeur: TextFieldValue) {
        connue = valeur
        envoyees.addLast(valeur)
        while (envoyees.size > ENVOYEES_MAX) envoyees.removeFirst()
        onValeur(valeur)
    }

    private fun selectionCourante() = TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))

    /** Changes of the text so far, from the field or from the state: what a Cut checks before removing. */
    private var version = 0L

    /** The text and the selection, once a change is over. */
    private inner class Surveillance : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            enModification = true
            version++
            // Typing brings the cursor into view: a fling still running would take it away again.
            elan.forceFinished(true)
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable) {
            enModification = false
            if (!enApplication) envoyer(TextFieldValue(s.toString(), selectionCourante()))
        }
    }

    /**
     * A selection that moved alone — a tap, a handle dragged. One that moves with the text is sent by
     * [Surveillance]: sending it here too would leave the state's "value before" equal to the new one.
     */
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!pret || enApplication || enModification) return
        val selection = selectionCourante()
        if (selection != connue.selection) envoyer(connue.copy(selection = selection))
    }

    // ------------------------------------------------------------------ focus and keyboard

    /**
     * The focus, the keyboard, and the cursor in view. The keyboard through the window's insets
     * controller: `showSoftInput` right after `requestFocus` is refused while the keyboard does not
     * serve this field yet — seen in the instrumented tests —, the controller waits for it.
     */
    fun focaliser() {
        doOnAttach {
            requestFocus()
            val fenetre = context.activite()?.window
            if (fenetre != null) {
                WindowCompat.getInsetsController(fenetre, this).show(WindowInsetsCompat.Type.ime())
            } else {
                post { clavier()?.showSoftInput(this, 0) }
            }
            amenerLeCurseur()
        }
    }

    private tailrec fun Context.activite(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activite()
        else -> null
    }

    private fun amenerLeCurseur() {
        post { if (layout != null) bringPointIntoView(selectionEnd.coerceAtLeast(0)) }
    }

    private fun clavier() = context.getSystemService(InputMethodManager::class.java)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The keyboard came up and took the bottom of the field: the cursor stays in view.
        if (h < oldh && hasFocus()) amenerLeCurseur()
    }

    /**
     * 🔴 **The keyboard leaves with the field** — called when it leaves the composition. Compose's field
     * closed the keyboard at the end of its input session; an `EditText` taken out of the window leaves
     * it open, and the reading — or the list of notes — came back under it (measured on the emulator,
     * 2026-10-30, the tick and the app bar's arrow).
     *
     * Always, not on a focus change: the tick takes the focus away while the field is still there. The
     * field never leaves for another text field — the search bar and the link sheet open with it on
     * screen —, only for the reading, another screen, a note opened from a link, a rotation: no
     * keyboard is wanted after any of them. Through the window: once the field is gone, no view is the
     * keyboard's, and `hideSoftInputFromWindow` was refused for that (instrumented test, 2026-10-30).
     */
    fun relacher() {
        val fenetre = context.activite()?.window ?: return
        WindowCompat.getInsetsController(fenetre, fenetre.decorView).hide(WindowInsetsCompat.Type.ime())
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connexion = super.onCreateInputConnection(outAttrs) ?: return null
        if (protegee) outAttrs.imeOptions = outAttrs.imeOptions or EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING
        return ConnexionBornee(connexion)
    }

    /**
     * 🔴 **What the keyboard reads of the note: a window around the cursor, never the whole text.**
     *
     * Measured on the S9 (Samsung Keyboard, 2026-10-30): after EVERY keystroke the keyboard asks for
     * the extracted text, and a `TextView` answers with all of it — 900 KB for a note of 450 000
     * characters. Over the 1 MB a Binder transaction carries with everything else in flight, the answer
     * failed (`TransactionTooLargeException`), and the keyboard waited 2 s for it, at each keystroke.
     * Below, it still copied the note to the keyboard's process a keystroke at a time — a vault
     * note's plaintext with it, where the keyboard needs a few words around the cursor.
     *
     * The window keeps the offsets right ([ExtractedText.startOffset]): the keyboard knows where the
     * cursor is in the note. No monitoring: the keyboard asks again, as it does after every keystroke.
     * With no fullscreen keyboard (see the class), nothing shows the extracted text.
     */
    private inner class ConnexionBornee(cible: InputConnection) : InputConnectionWrapper(cible, false) {

        /** `null` for a selection longer than the window: the window holds the selection whole. */
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (selectionLongue()) return null
            val editable = text
            val selDebut = selectionStart.coerceIn(0, editable.length)
            val selFin = selectionEnd.coerceIn(0, editable.length)
            val debut = (minOf(selDebut, selFin) - FENETRE_DU_CLAVIER).coerceAtLeast(0)
            val fin = (maxOf(selDebut, selFin) + FENETRE_DU_CLAVIER).coerceAtMost(editable.length)
            return ExtractedText().apply {
                this.text = editable.substring(debut, fin)
                startOffset = debut
                partialStartOffset = -1
                partialEndOffset = -1
                selectionStart = selDebut - debut
                selectionEnd = selFin - debut
            }
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            super.getTextBeforeCursor(n.coerceAtMost(FENETRE_DU_CLAVIER), flags)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
            super.getTextAfterCursor(n.coerceAtMost(FENETRE_DU_CLAVIER), flags)

        /** A selection longer than the window is not handed over: `null`, as an editor that cannot. */
        override fun getSelectedText(flags: Int): CharSequence? =
            if (selectionLongue()) null else super.getSelectedText(flags)

        @RequiresApi(Build.VERSION_CODES.S)
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
            if (selectionLongue()) {
                null
            } else {
                super.getSurroundingText(
                    beforeLength.coerceAtMost(FENETRE_DU_CLAVIER),
                    afterLength.coerceAtMost(FENETRE_DU_CLAVIER),
                    flags,
                )
            }

        /**
         * Android 13's single read of the keyboard's state. The platform bounds the text around the
         * selection, not the selection itself — included whole.
         */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun takeSnapshot(): TextSnapshot? = if (selectionLongue()) null else super.takeSnapshot()

        private fun selectionLongue() = kotlin.math.abs(selectionEnd - selectionStart) > FENETRE_DU_CLAVIER
    }

    /** No spell checker: see the class. The keyboard's own suggestions are untouched. */
    override fun isSuggestionsEnabled(): Boolean = false

    // ------------------------------------------------------------------ clipboard, menu, drag

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.copy -> copier(couper = false)
        android.R.id.cut -> copier(couper = true)
        // A note is plain text: a styled clip would show styles the note does not keep.
        android.R.id.paste -> super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
        else -> super.onTextContextMenuItem(id)
    }

    private fun copier(couper: Boolean): Boolean {
        val debut = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val fin = maxOf(selectionStart, selectionEnd).coerceAtMost(text.length)
        if (debut >= fin) return true
        val texte = text.substring(debut, fin)
        val avant = version
        ecrire(texte) { ecrit ->
            // Removed only if written, and only if the text did not change meanwhile: the same characters
            // at the same place may be ones typed since (GPT-5.6 review, 2026-10-30). Left in place, the
            // Cut was a Copy — nothing lost. Likewise if the field leaves the screen during the write: its
            // scope is cancelled, and nothing is removed.
            if (ecrit && couper && version == avant) text.delete(debut, fin)
        }
        menu?.finish()
        return true
    }

    // ------------------------------------------------------------------ fling

    /**
     * 🔴 **A scroll keeps going once the finger is lifted, as in Compose's field.** An `EditText`
     * scrolls by the finger's distance only — `Touch.onTouchEvent` has no fling —, and a long note
     * stopped dead at each lift: "ça rame, même sans le clavier" (Patrice, S24 and S9, 2026-10-30),
     * while every frame took 12 ms. Started only after a drag that scrolled the text, never after a
     * long press — selecting or dragging handles —; stopped by a touch or a change of the text.
     */
    private val elan = Scroller(context)
    private var vitesse: VelocityTracker? = null
    private var defilementAuToucher = 0
    private var appuiLong = false
    private val reglages = ViewConfiguration.get(context)

    /**
     * 🔴 **The gesture stays the field's while its text can scroll.** The editor's column scrolls too
     * (a window too short for the header, the body and the links), and Compose gave it every vertical
     * drag on the field: the field received ACTION_CANCEL 40 to 90 px into each gesture — logged on
     * the S24, 2026-10-30 —, so the text stopped mid-gesture ("ça bloque") and never flung. A short
     * text that cannot scroll leaves the gesture to the column, as before.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dernierToucher = getOffsetForPosition(event.x, event.y)
            elan.forceFinished(true)
            defilementAuToucher = scrollY
            appuiLong = false
            vitesse?.recycle()
            vitesse = VelocityTracker.obtain()
            if (canScrollVertically(1) || canScrollVertically(-1)) parent?.requestDisallowInterceptTouchEvent(true)
        }
        vitesse?.addMovement(event)
        val traite = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> lancer()
            MotionEvent.ACTION_CANCEL -> Unit
            else -> return traite
        }
        vitesse?.recycle()
        vitesse = null
        return traite
    }

    private fun lancer() {
        val suivi = vitesse ?: return
        suivi.computeCurrentVelocity(MILLISECONDES_PAR_SECONDE, reglages.scaledMaximumFlingVelocity.toFloat())
        val vy = suivi.yVelocity
        val aDefile = kotlin.math.abs(scrollY - defilementAuToucher) > reglages.scaledTouchSlop
        if (!aDefile || appuiLong || kotlin.math.abs(vy) < reglages.scaledMinimumFlingVelocity) return
        val bas = ((layout?.height ?: 0) + totalPaddingTop + totalPaddingBottom - height).coerceAtLeast(0)
        elan.fling(scrollX, scrollY, 0, -vy.toInt(), scrollX, scrollX, 0, bas)
        postInvalidateOnAnimation()
    }

    /** Through [scrollTo], not by setting the offsets: the handles and the toolbar follow the text. */
    override fun computeScroll() {
        super.computeScroll()
        if (elan.computeScrollOffset()) {
            scrollTo(elan.currX, elan.currY)
            postInvalidateOnAnimation()
        }
    }

    /**
     * A drop from another app arrives as plain text. A drag within this field — it carries the
     * field's own drag state — keeps the platform's move.
     */
    override fun onDragEvent(event: DragEvent): Boolean {
        if (event.action != DragEvent.ACTION_DROP || event.localState != null) return super.onDragEvent(event)
        val donnees = event.clipData ?: return false
        val texte = buildString {
            for (i in 0 until donnees.itemCount) append(donnees.getItemAt(i).coerceToText(context))
        }
        val position = getOffsetForPosition(event.x, event.y)
        if (texte.isEmpty() || position < 0) return false
        text.insert(position, texte)
        setSelection(position + texte.length)
        return true
    }

    /** Keeps the toolbar's editing entries only — see [MenuDuChampDuCorps]. */
    private inner class FiltreDuMenu : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            this@ChampNatifDuCorps.menu = mode
            return true
        }

        // Called after the platform filled the menu — its `PROCESS_TEXT` entries come in after
        // `onCreateActionMode`.
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val retirees = menu.children
                .filterNot { MenuDuChampDuCorps.garder(it.itemId, intention = it.intent != null) }
                .toList()
            retirees.forEach { menu.removeItem(it.itemId) }
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

        override fun onDestroyActionMode(mode: ActionMode) {
            if (this@ChampNatifDuCorps.menu === mode) this@ChampNatifDuCorps.menu = null
        }
    }

    // ------------------------------------------------------------------ look

    fun apparence(nouvelle: Apparence) {
        val avant = apparence
        if (avant == nouvelle) return
        apparence = nouvelle
        // Each size change lays out the whole text again: only what changed.
        if (avant?.tailleSp != nouvelle.tailleSp) setTextSize(TypedValue.COMPLEX_UNIT_SP, nouvelle.tailleSp)
        if (avant?.interligneSp != nouvelle.interligneSp) {
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                nouvelle.interligneSp,
                resources.displayMetrics,
            )
            TextViewCompat.setLineHeight(this, px.toInt())
        }
        if (avant?.espacementEm != nouvelle.espacementEm) letterSpacing = nouvelle.espacementEm
        setPadding(nouvelle.margeDeCotePx, nouvelle.margeDuHautPx, nouvelle.margeDeCotePx, nouvelle.margeDuBasPx)
        setTextColor(nouvelle.texte)
        setHintTextColor(nouvelle.indication)
        highlightColor = nouvelle.selection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            avant?.curseur != nouvelle.curseur
        ) {
            teindreLeCurseur(nouvelle.curseur)
        }
    }

    /** The cursor and the selection handles in the theme's colour — Android 10 on; before, the platform's. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun teindreLeCurseur(couleur: Int) {
        val largeur = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            LARGEUR_DU_CURSEUR_DP,
            resources.displayMetrics,
        )
        textCursorDrawable = GradientDrawable().apply {
            setColor(couleur)
            setSize(largeur.toInt().coerceAtLeast(1), 0)
        }
        textSelectHandle?.mutate()?.let { setTextSelectHandle(it.apply { setTint(couleur) }) }
        textSelectHandleLeft?.mutate()?.let { setTextSelectHandleLeft(it.apply { setTint(couleur) }) }
        textSelectHandleRight?.mutate()?.let { setTextSelectHandleRight(it.apply { setTint(couleur) }) }
    }

    fun indications(indication: String, nom: String) {
        this.nom = nom
        if (this.indication != indication) {
            this.indication = indication
            hint = indication
        }
    }

    /**
     * The search's occurrences in the field ([plages]), [courante] more strongly — the spans of the
     * previous answer removed first. Spans change the drawing only, not the text nor its layout.
     */
    fun surligner(plages: List<Plage>, courante: Plage?, couleurs: CouleursDeSurlignage) {
        if (plages === plagesSurlignees && courante == couranteSurlignee && couleurs == couleursSurlignees) return
        plagesSurlignees = plages
        couranteSurlignee = courante
        couleursSurlignees = couleurs
        val editable = text
        spans.forEach(editable::removeSpan)
        spans.clear()
        plages.forEach { plage ->
            if (plage.debut < plage.fin && plage.fin <= editable.length) {
                val actuelle = plage == courante
                val fond = BackgroundColorSpan(if (actuelle) couleurs.fondCourante else couleurs.fond)
                val encre = ForegroundColorSpan(if (actuelle) couleurs.encreCourante else couleurs.encre)
                editable.setSpan(fond, plage.debut, plage.fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                editable.setSpan(encre, plage.debut, plage.fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spans += fond
                spans += encre
            }
        }
    }

    private companion object {
        /** Echoes kept: far more than the keystrokes one frame can hold. */
        const val ENVOYEES_MAX = 64
        const val LARGEUR_DU_CURSEUR_DP = 2f
        const val MILLISECONDES_PAR_SECONDE = 1_000

        /** Characters on each side of the cursor the keyboard may read: 20 KB at most. */
        const val FENETRE_DU_CLAVIER = 5_000
    }
}
