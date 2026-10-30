package com.filestech.notes_tech.ui.editor

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The replacement the body field applies when the state changes under it. What these tests protect:
 * that applying it gives exactly the new text — a wrong bound would corrupt the note without anything
 * showing — and that it never splits a surrogate pair.
 */
class ReglesDuChampDuCorpsTest {

    /** [avant] with the difference applied, as the field's `Editable.replace` does. */
    private fun appliquer(avant: String, apres: String): String {
        val d = DifferenceDeTexte.entre(avant, apres) ?: return avant
        return avant.substring(0, d.debut) + apres.substring(d.debut, d.finApres) + avant.substring(d.finAvant)
    }

    @Test
    @DisplayName("the same text: nothing to apply")
    fun memeTexte() {
        assertThat(DifferenceDeTexte.entre("abc", "abc")).isNull()
        assertThat(DifferenceDeTexte.entre("", "")).isNull()
    }

    @Test
    @DisplayName("an insertion in the middle replaces nothing around it")
    fun insertion() {
        val d = DifferenceDeTexte.entre("Voir  la suite", "Voir [[Notes]] la suite")

        assertThat(d).isEqualTo(DifferenceDeTexte(debut = 5, finAvant = 5, finApres = 14))
    }

    @Test
    @DisplayName("a deletion, at the start, in the middle, at the end")
    fun suppressions() {
        assertThat(DifferenceDeTexte.entre("xabc", "abc")).isEqualTo(DifferenceDeTexte(0, 1, 0))
        assertThat(DifferenceDeTexte.entre("abxc", "abc")).isEqualTo(DifferenceDeTexte(2, 3, 2))
        assertThat(DifferenceDeTexte.entre("abcx", "abc")).isEqualTo(DifferenceDeTexte(3, 4, 3))
    }

    /** Prefix and suffix may claim the same characters ("aa" → "aaa"): they must not overlap. */
    @Test
    @DisplayName("repeated characters: the prefix and the suffix never overlap")
    fun repetitions() {
        for ((avant, apres) in listOf("aa" to "aaa", "aaa" to "aa", "abab" to "ab", "" to "a", "a" to "")) {
            val d = DifferenceDeTexte.entre(avant, apres)!!
            assertThat(d.debut).isAtMost(d.finAvant)
            assertThat(d.debut).isAtMost(d.finApres)
            assertThat(appliquer(avant, apres)).isEqualTo(apres)
        }
    }

    /** Two emoji sharing their high surrogate: the common prefix ends between the two halves. */
    @Test
    @DisplayName("a surrogate pair is replaced whole, never split")
    fun pairesDeSubstitution() {
        val avant = "a😀b"
        val apres = "a😁b"

        val d = DifferenceDeTexte.entre(avant, apres)!!

        assertThat(d).isEqualTo(DifferenceDeTexte(debut = 1, finAvant = 3, finApres = 3))
        assertThat(appliquer(avant, apres)).isEqualTo(apres)
    }

    /** The suffix starting on a low surrogate whose high one differs: an emoji changed for another. */
    @Test
    @DisplayName("a suffix starting inside a surrogate pair gives the pair back to the replacement")
    fun suffixeDansUnePaire() {
        // U+1F600 and U+1FA00: different high surrogates, the same low one.
        val avant = "😀"
        val apres = "🨀"

        val d = DifferenceDeTexte.entre(avant, apres)!!

        assertThat(d).isEqualTo(DifferenceDeTexte(debut = 0, finAvant = 2, finApres = 2))
    }

    @Test
    @DisplayName("on texts of every shape, applying the difference gives the new text")
    fun toujoursLeNouveauTexte() {
        val textes = listOf("", "a", "ab", "ba", "aba", "Bonjour le monde", "Bonjour monde", "x😀y", "\n\n")
        for (avant in textes) {
            for (apres in textes) assertThat(appliquer(avant, apres)).isEqualTo(apres)
        }
    }

    @Test
    @DisplayName("the toolbar keeps the editing entries, and nothing carrying another app's action")
    fun menu() {
        for (id in listOf(android.R.id.cut, android.R.id.copy, android.R.id.paste, android.R.id.selectAll)) {
            assertThat(MenuDuChampDuCorps.garder(id, intention = false)).isTrue()
            assertThat(MenuDuChampDuCorps.garder(id, intention = true)).isFalse()
        }
        assertThat(MenuDuChampDuCorps.garder(android.R.id.shareText, intention = false)).isFalse()
        assertThat(MenuDuChampDuCorps.garder(android.R.id.autofill, intention = false)).isFalse()
        assertThat(MenuDuChampDuCorps.garder(android.R.id.textAssist, intention = false)).isFalse()
        // A phone maker's own entry, unknown here.
        assertThat(MenuDuChampDuCorps.garder(0x7f0a0042, intention = false)).isFalse()
    }

    @Test
    @DisplayName("a long press inside a vault note's selection is held back — and nowhere else")
    fun glisser() {
        // Inside the selection [6, 25), whichever way it was made.
        assertThat(glisserRetenu(protegee = true, selectionDebut = 6, selectionFin = 25, toucher = 14)).isTrue()
        assertThat(glisserRetenu(protegee = true, selectionDebut = 25, selectionFin = 6, toucher = 6)).isTrue()
        // An ordinary note, a press outside or at the end, no selection, an unknown touch.
        assertThat(glisserRetenu(protegee = false, selectionDebut = 6, selectionFin = 25, toucher = 14)).isFalse()
        assertThat(glisserRetenu(protegee = true, selectionDebut = 6, selectionFin = 25, toucher = 25)).isFalse()
        assertThat(glisserRetenu(protegee = true, selectionDebut = 6, selectionFin = 25, toucher = 3)).isFalse()
        assertThat(glisserRetenu(protegee = true, selectionDebut = 9, selectionFin = 9, toucher = 9)).isFalse()
        assertThat(glisserRetenu(protegee = true, selectionDebut = 6, selectionFin = 25, toucher = -1)).isFalse()
    }
}
