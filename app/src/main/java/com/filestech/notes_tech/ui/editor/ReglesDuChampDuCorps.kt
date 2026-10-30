package com.filestech.notes_tech.ui.editor

/**
 * The smallest replacement turning one text into another: `avant[debut until finAvant]` becomes
 * `apres[debut until finApres]`, the rest is common to both.
 *
 * What the body field applies when the note's state changes under it — a link inserted, a dictation,
 * a Cut of the title undone — instead of the whole text, which would cost the field its scroll
 * position, the keyboard's composition, and a layout of the whole note (see [ChampDuCorps]).
 */
internal data class DifferenceDeTexte(val debut: Int, val finAvant: Int, val finApres: Int) {

    companion object {

        /**
         * `null` when [avant] and [apres] are the same text. The common prefix and suffix never
         * overlap, and never split a surrogate pair: a replacement starting between the two halves of
         * an emoji would leave a lone half in the field for as long as the replacement takes.
         */
        fun entre(avant: CharSequence, apres: CharSequence): DifferenceDeTexte? {
            val commun = minOf(avant.length, apres.length)
            var debut = 0
            while (debut < commun && avant[debut] == apres[debut]) debut++
            if (debut == avant.length && debut == apres.length) return null
            var fin = 0
            while (fin < commun - debut && avant[avant.length - 1 - fin] == apres[apres.length - 1 - fin]) fin++
            if (debut > 0 && Character.isHighSurrogate(avant[debut - 1])) debut--
            if (fin > 0 && Character.isLowSurrogate(avant[avant.length - fin])) fin--
            return DifferenceDeTexte(debut, avant.length - fin, apres.length - fin)
        }
    }
}

/**
 * The entries the body field's selection toolbar keeps: editing ones only.
 *
 * 🔴 An `EditText` adds what the Compose field it replaces never showed: Share, Autofill, the
 * classifier's actions, and one entry per app answering `PROCESS_TEXT` — the very "Translate" that
 * sent a vault note's text to a search engine online (3.1.1, `SansClassificationDeTexte`). Removing
 * the `PROCESS_TEXT` queries hides those apps from Android 11 on only: on Android 10 and below every
 * app is visible. A phone maker's own additions are unknown in advance, so this is a list of what is
 * KEPT, not of what is removed.
 */
internal object MenuDuChampDuCorps {

    private val GARDEES = setOf(
        android.R.id.cut,
        android.R.id.copy,
        android.R.id.paste,
        android.R.id.pasteAsPlainText,
        android.R.id.selectAll,
        android.R.id.replaceText,
    )

    /** Whether the entry [id] stays; never one carrying an [intention] — another app's action. */
    fun garder(id: Int, intention: Boolean): Boolean = !intention && id in GARDEES
}

/**
 * Whether the body field holds back a long press: one inside a vault note's selection, where the
 * platform would start dragging the selected text — to another app too, as a clip none of the
 * clipboard's protections reach. Held back, the press starts nothing; the toolbar stays.
 *
 * @param toucher the offset under the finger, `-1` when unknown.
 */
internal fun glisserRetenu(protegee: Boolean, selectionDebut: Int, selectionFin: Int, toucher: Int): Boolean =
    protegee &&
        selectionDebut != selectionFin &&
        toucher in minOf(selectionDebut, selectionFin) until maxOf(selectionDebut, selectionFin)
