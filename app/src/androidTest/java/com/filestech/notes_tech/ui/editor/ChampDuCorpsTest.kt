package com.filestech.notes_tech.ui.editor

import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.os.SystemClock
import android.text.style.BackgroundColorSpan
import android.util.SparseArray
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.textclassifier.TextClassifier
import android.widget.PopupMenu
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.domain.recherche.RechercheDansLeTexte.Plage
import com.filestech.notes_tech.ui.secure.LocalCopieDeSaisie
import com.filestech.notes_tech.ui.secure.LocalSaisieProtegee
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The note's body field, Android's own `EditText` (3.2.0, [ChampDuCorps])** — what it must keep
 * from the Compose field it replaces, and what an `EditText` opens that must stay closed.
 *
 * Each case answers a defect the field would have without the line it protects; the comment of the
 * field's KDoc says which.
 */
@RunWith(AndroidJUnit4::class)
class ChampDuCorpsTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private var etat by mutableStateOf(TextFieldValue())
    private var protegee by mutableStateOf(false)
    private var surlignage by mutableStateOf(Surlignage.AUCUN)
    private val recues = mutableListOf<TextFieldValue>()
    private val copiees = mutableListOf<String>()
    private var copieAcceptee = true

    /** When set, the clipboard's write waits for it: a slow protected write. */
    private var attente: CompletableDeferred<Unit>? = null

    /** `true`: the state lags — what the field reports is recorded, and given back only when the test says. */
    private var etatEnRetard = false

    private fun poser(texte: String, selection: TextRange = TextRange(texte.length)) {
        etat = TextFieldValue(texte, selection)
        regle.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalSaisieProtegee provides protegee,
                    LocalCopieDeSaisie provides { texte ->
                        copiees += texte
                        attente?.await()
                        copieAcceptee
                    },
                    LocalSurlignage provides surlignage,
                ) {
                    ChampDuCorps(
                        valeur = etat,
                        onValeurChange = {
                            recues += it
                            if (!etatEnRetard) etat = it
                        },
                        focaliser = false,
                        onFocalise = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        regle.waitForIdle()
    }

    private fun champ(): ChampNatifDuCorps = regle.runOnUiThread { checkNotNull(champDuCorpsDe(regle.activity)) }

    private fun <T> surLeChamp(action: ChampNatifDuCorps.() -> T): T = regle.runOnUiThread { champ().action() }

    // ------------------------------------------------------------------ the state, both ways

    /**
     * What is typed reaches the state ONCE, with its cursor. A second, selection-only report of the
     * same change would make the state's "value before" equal to the new one, and a refused Cut of the
     * title could no longer be undone (`NoteEditorViewModel.copieRefusee`).
     */
    @Test
    fun what_is_typed_reaches_the_state_once_with_its_cursor() {
        poser("Bonjour")

        surLeChamp { taper(" le monde") }
        regle.waitForIdle()

        assertThat(etat).isEqualTo(TextFieldValue("Bonjour le monde", TextRange(16)))
        assertThat(recues.count { it.text == "Bonjour le monde" }).isEqualTo(1)
    }

    /**
     * 🔴 The state reaches the screen a frame or more after the field reported it: a recomposition
     * still carrying the value of the keystroke BEFORE must not undo the last one.
     */
    @Test
    fun a_late_echo_of_the_state_does_not_undo_a_keystroke() {
        poser("x")
        etatEnRetard = true
        surLeChamp {
            taper("a")
            taper("b")
        }

        // The screen recomposes with the value of "a" — the very object the field sent —, "b" not in yet.
        regle.runOnIdle { etat = recues.first { it.text == "xa" } }
        regle.waitForIdle()
        assertThat(surLeChamp { text.toString() }).isEqualTo("xab")
        // And again: a search's answer arrives, the field is updated — the same value still there
        // (GPT-5.6 review: the echo was dropped at its first sight, and its second undid "b").
        regle.runOnIdle { surlignage = surlignageDe("xab", Plage(0, 1)) }
        regle.waitForIdle()
        assertThat(surLeChamp { text.toString() }).isEqualTo("xab")

        // Then with "b": the field and the state agree.
        regle.runOnIdle { etat = recues.last() }
        regle.waitForIdle()
        assertThat(surLeChamp { text.toString() }).isEqualTo("xab")
        assertThat(etat.text).isEqualTo("xab")
    }

    /**
     * 🔴 A value the state already gave back, seen once more after the next keystroke — something else
     * recomposed the field while the state lagged — is not a change: it emptied the queue when it was
     * acknowledged, and undid the keystroke (audit 3 axes of 3.2.0, E1).
     */
    @Test
    fun an_acknowledged_value_seen_again_does_not_undo_the_next_keystroke() {
        poser("x")
        surLeChamp { taper("a") }
        regle.waitForIdle()
        val reconnue = etat

        etatEnRetard = true
        surLeChamp { taper("b") }
        regle.runOnIdle { surlignage = surlignageDe("xab", Plage(0, 1)) }
        regle.waitForIdle()

        assertThat(etat).isSameInstanceAs(reconnue)
        assertThat(surLeChamp { text.toString() }).isEqualTo("xab")
    }

    /**
     * A change from the state — a link inserted — is applied as the smallest replacement: what lies
     * outside it stays as it was. Replacing the whole text would lay out a long note again, lose the
     * scroll position and the keyboard's composition; a span far from the change shows it did not.
     */
    @Test
    fun a_change_from_the_state_replaces_only_what_changed() {
        poser("Voir  pour la suite. " + "Fin.".repeat(50), TextRange(5))
        val temoin = Any()
        surLeChamp { text.setSpan(temoin, text.length - 4, text.length, 0) }

        regle.runOnIdle { etat = InsertionDeTexte.dansLaSelection(etat, "[[Notes]]") }
        regle.waitForIdle()

        assertThat(surLeChamp { text.toString() }).startsWith("Voir [[Notes]] pour la suite.")
        assertThat(surLeChamp { selectionStart to selectionEnd }).isEqualTo(14 to 14)
        assertThat(surLeChamp { text.getSpanStart(temoin) }).isAtLeast(0)
    }

    // ------------------------------------------------------------------ clipboard

    /** Copy goes through the note's clipboard (`SaisieDeCoffre`), never the `EditText`'s own plain clip. */
    @Test
    fun copy_goes_through_the_note_s_clipboard() {
        poser("un deux trois", TextRange(3, 7))

        surLeChamp { onTextContextMenuItem(android.R.id.copy) }
        regle.waitForIdle()

        assertThat(copiees).containsExactly("deux")
        assertThat(etat.text).isEqualTo("un deux trois")
    }

    /** A Cut removes the text once it is written — and only then. */
    @Test
    fun a_cut_removes_the_text_once_written() {
        poser("un deux trois", TextRange(3, 7))

        surLeChamp { onTextContextMenuItem(android.R.id.cut) }
        regle.waitForIdle()

        assertThat(copiees).containsExactly("deux")
        assertThat(etat.text).isEqualTo("un  trois")
    }

    /**
     * 🔴 A refused Cut loses nothing: Compose's field removed the selection BEFORE writing it, and a
     * refused write lost it until the state undid it. This one writes first.
     */
    @Test
    fun a_refused_cut_loses_nothing() {
        copieAcceptee = false
        poser("un deux trois", TextRange(3, 7))

        surLeChamp { onTextContextMenuItem(android.R.id.cut) }
        regle.waitForIdle()

        assertThat(copiees).containsExactly("deux")
        assertThat(surLeChamp { text.toString() }).isEqualTo("un deux trois")
        assertThat(etat.text).isEqualTo("un deux trois")
    }

    /**
     * 🔴 A Cut whose text changed while it was being written removes nothing: the same characters at
     * the same place may be ones typed since (GPT-5.6 review, 2026-10-30). Left in place, it was a Copy.
     */
    @Test
    fun a_cut_removes_nothing_if_the_text_changed_meanwhile() {
        val ecriture = CompletableDeferred<Unit>()
        attente = ecriture
        poser("aX", TextRange(0, 1))

        surLeChamp { onTextContextMenuItem(android.R.id.cut) }
        // While the clipboard is being written, "aY" typed at the start.
        surLeChamp {
            setSelection(0)
            taper("aY")
        }
        regle.runOnIdle { ecriture.complete(Unit) }
        regle.waitForIdle()

        assertThat(copiees).containsExactly("a")
        assertThat(surLeChamp { text.toString() }).isEqualTo("aYaX")
    }

    // ------------------------------------------------------------------ the keyboard

    /** A vault note's keyboard is asked not to learn (audit 2026-09-26, F4); another note's is not. */
    @Test
    fun a_vault_note_asks_the_keyboard_not_to_learn() {
        poser("secret")
        val drapeau = EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING
        fun options() = surLeChamp { EditorInfo().also { onCreateInputConnection(it) }.imeOptions }

        assertThat(options() and drapeau).isEqualTo(0)

        regle.runOnIdle { protegee = true }
        regle.waitForIdle()

        assertThat(options() and drapeau).isEqualTo(drapeau)
    }

    /**
     * 🔴 The keyboard reads a window around the cursor, never the note: the Samsung Keyboard asks for
     * the whole extracted text after every keystroke, and a 450 000-character note failed the Binder
     * and stalled each keystroke 2 s (S9, 2026-10-30). The offsets stay right.
     */
    @Test
    fun the_keyboard_reads_a_window_around_the_cursor() {
        val texte = (0 until 3_000).joinToString("") { "ligne $it\n" }
        val curseur = texte.length / 2
        poser(texte, TextRange(curseur))

        val extrait = surLeChamp {
            checkNotNull(onCreateInputConnection(EditorInfo())).getExtractedText(ExtractedTextRequest(), 0)
        }

        assertThat(extrait!!.text.length).isAtMost(2 * FENETRE)
        assertThat(extrait.startOffset).isEqualTo(curseur - FENETRE)
        assertThat(extrait.selectionStart).isEqualTo(FENETRE)
        assertThat(extrait.text.toString()).isEqualTo(texte.substring(curseur - FENETRE, curseur + FENETRE))
        val avant =
            surLeChamp { checkNotNull(onCreateInputConnection(EditorInfo())).getTextBeforeCursor(Int.MAX_VALUE, 0) }
        assertThat(avant!!.length).isEqualTo(FENETRE)
    }

    /**
     * A selection longer than the window is handed to the keyboard by none of its reads — "Select all"
     * on a long vault note, then the keyboard asking for the extracted text, gave it the whole note
     * (GPT-5.6 review, 2026-10-30).
     */
    @Test
    fun a_long_selection_is_not_handed_to_the_keyboard() {
        val texte = "a".repeat(3 * FENETRE)
        poser(texte, TextRange(0, texte.length))

        surLeChamp {
            val connexion = checkNotNull(onCreateInputConnection(EditorInfo()))
            assertThat(connexion.getSelectedText(0)).isNull()
            assertThat(connexion.getExtractedText(ExtractedTextRequest(), 0)).isNull()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) assertThat(connexion.takeSnapshot()).isNull()
        }
    }

    // ------------------------------------------------------------------ scrolling

    /**
     * 🔴 A fling keeps the text going once the finger is lifted: an EditText scrolls by the finger's
     * distance only, and a long note "rame" (Patrice, S24 and S9, 2026-10-30). Discriminating: without
     * the fling, the text moves by the drag and stops.
     */
    @Test
    fun a_fling_keeps_the_text_going() {
        poser((0 until 4_000).joinToString("\n") { "Ligne $it" })
        val avant = surLeChamp {
            scrollTo(0, layout.height + totalPaddingTop + totalPaddingBottom - height)
            scrollY
        }

        val trajet = 600
        surLeChamp {
            val x = width / 2f
            val depart = height / 4f
            val instant = SystemClock.uptimeMillis()
            fun evenement(action: Int, dt: Long, y: Float) =
                MotionEvent.obtain(instant, instant + dt, action, x, y, 0).also { onTouchEvent(it) }.recycle()
            evenement(MotionEvent.ACTION_DOWN, 0, depart)
            for (pas in 1..6) evenement(MotionEvent.ACTION_MOVE, pas * 10L, depart + pas * trajet / 6f)
            evenement(MotionEvent.ACTION_UP, 60, depart + trajet)
        }
        val apresLeDoigt = surLeChamp { scrollY }
        SystemClock.sleep(DUREE_DE_L_ELAN_MS)
        regle.waitForIdle()
        val apres = surLeChamp { scrollY }

        // The drag itself, then far more: the text kept going.
        assertThat(avant - apresLeDoigt).isAtLeast(trajet / 2)
        assertThat(avant - apres).isGreaterThan(3 * trajet)
    }

    // ------------------------------------------------------------------ closed doors

    /**
     * 🔴 The toolbar keeps its editing entries only: no Share, no Autofill, and no other app's
     * `PROCESS_TEXT` action — the "Translate" that sent the text online (3.1.1) — in either toolbar.
     */
    @Test
    fun the_toolbars_offer_no_other_app_and_no_share() {
        poser("un deux trois")

        val restantes = surLeChamp {
            listOf(customSelectionActionModeCallback, customInsertionActionModeCallback).map { rappel ->
                val menu = PopupMenu(context, this).menu
                for (id in listOf(android.R.id.cut, android.R.id.copy, android.R.id.paste, android.R.id.shareText)) {
                    menu.add(Menu.NONE, id, Menu.NONE, "x")
                }
                menu.add(Menu.NONE, android.R.id.autofill, Menu.NONE, "x")
                menu.add(Menu.NONE, Menu.NONE, Menu.NONE, "Translate").intent = Intent(Intent.ACTION_PROCESS_TEXT)
                rappel.onCreateActionMode(ModeFactice, menu)
                rappel.onPrepareActionMode(ModeFactice, menu)
                (0 until menu.size()).map { menu.getItem(it).itemId }
            }
        }

        restantes.forEach { ids ->
            assertThat(ids).containsExactly(android.R.id.cut, android.R.id.copy, android.R.id.paste)
        }
    }

    /**
     * Not offered to autofill, never written to the saved state, no spell checker, no classifier, no
     * fullscreen keyboard — see the field's KDoc for why each.
     */
    @Test
    fun what_an_edit_text_offers_by_default_stays_closed() {
        poser("secret")

        surLeChamp {
            assertThat(importantForAutofill).isEqualTo(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
            // Nothing of the text in the saved state — what Compose's AndroidView saves for its view —,
            // and not even with an id given to it some day.
            assertThat(isSaveEnabled).isFalse()
            val sauvegarde = SparseArray<Parcelable>()
            saveHierarchyState(sauvegarde)
            assertThat(sauvegarde.size()).isEqualTo(0)
            assertThat(isSuggestionsEnabled).isFalse()
            assertThat(textClassifier).isSameInstanceAs(TextClassifier.NO_OP)
            assertThat(imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI).isNotEqualTo(0)
            assertThat(id).isEqualTo(View.NO_ID)
        }
    }

    // ------------------------------------------------------------------ the search

    /** The search's occurrences, highlighted; the current one in its own colour; gone with the bar. */
    @Test
    fun the_search_highlights_its_occurrences_and_clears_them() {
        val texte = "pomme, poire, pomme"
        poser(texte)
        val plages = listOf(Plage(0, 5), Plage(14, 19))
        val resultat = ResultatDeRecherche(
            source = SourceDeRecherche.Ecriture("", texte),
            mots = listOf("pomme"),
            occurrences = plages.map { Occurrence(CleDuCorps, -1, it) },
            tronque = false,
        )

        regle.runOnIdle { surlignage = Surlignage(resultat, resultat.occurrences[1]) }
        regle.waitForIdle()
        val fonds = surLeChamp {
            text.getSpans(0, text.length, BackgroundColorSpan::class.java).map {
                text.getSpanStart(it) to
                    it.backgroundColor
            }
        }

        assertThat(fonds.map { it.first }).containsExactly(0, 14)
        val tertiaire = regle.runOnIdle { couleurTertiaire }
        assertThat(fonds.single { it.first == 14 }.second).isEqualTo(tertiaire)

        regle.runOnIdle { surlignage = Surlignage.AUCUN }
        regle.waitForIdle()

        assertThat(surLeChamp { text.getSpans(0, text.length, BackgroundColorSpan::class.java) }).isEmpty()
    }

    /** The search's answer for [plage] in [texte], none of them the current one. */
    private fun surlignageDe(texte: String, plage: Plage): Surlignage {
        val resultat = ResultatDeRecherche(
            source = SourceDeRecherche.Ecriture("", texte),
            mots = listOf(texte.substring(plage.debut, plage.fin)),
            occurrences = listOf(Occurrence(CleDuCorps, -1, plage)),
            tronque = false,
        )
        return Surlignage(resultat, courante = null)
    }

    private val couleurTertiaire: Int get() = androidx.compose.material3.lightColorScheme().tertiary.toArgb()

    /** An action mode that does nothing: the toolbar's callbacks only read the menu they are given. */
    private object ModeFactice : ActionMode() {
        override fun setTitle(title: CharSequence?) = Unit
        override fun setTitle(resId: Int) = Unit
        override fun setSubtitle(subtitle: CharSequence?) = Unit
        override fun setSubtitle(resId: Int) = Unit
        override fun setCustomView(view: View?) = Unit
        override fun invalidate() = Unit
        override fun finish() = Unit
        override fun getMenu(): Menu? = null
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun getCustomView(): View? = null
        override fun getMenuInflater(): MenuInflater? = null
    }

    private companion object {
        /** The field's window, `ChampNatifDuCorps.FENETRE_DU_CLAVIER`. */
        const val FENETRE = 5_000

        /** Long enough for a fling of a few thousand pixels per second to travel. */
        const val DUREE_DE_L_ELAN_MS = 1_500L
    }
}
