package com.filestech.notes_tech.domain.recherche

import com.filestech.notes_tech.domain.recherche.RechercheDansLeTexte.Plage
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** "Find in note" (D-033): the same rule as the app's search, and positions in the text as written. */
class RechercheDansLeTexteTest {

    private fun trouve(texte: String, saisie: String, max: Int = 1000): List<String> =
        RechercheDansLeTexte.occurrences(texte, RechercheDansLeTexte.mots(saisie), max)
            .map { texte.substring(it.debut, it.fin) }

    @Test
    fun `case and accents do not count, either way`() {
        assertThat(trouve("Été, ete, ÉTÉ et éte.", "ete")).containsExactly("Été", "ete", "ÉTÉ", "éte").inOrder()
        assertThat(trouve("Une fete, une FÊTE.", "fête")).containsExactly("fete", "FÊTE").inOrder()
    }

    @Test
    fun `a decomposed accent is found, and kept inside the occurrence`() {
        val decompose = "café noir"

        assertThat(trouve(decompose, "café")).containsExactly("café")
        assertThat(trouve(decompose, "cafe")).containsExactly("café")
    }

    @Test
    fun `the typographic apostrophe is the keyboard's`() {
        assertThat(trouve("Souvenirs de l’été", "l'été")).containsExactly("l’été")
        assertThat(trouve("l'été", "l’ete")).containsExactly("l'été")
    }

    @Test
    fun `each word is looked for on its own, inside words too`() {
        assertThat(trouve("Budget 2026 : les budgets du réunion", "réunion budget"))
            .containsExactly("Budget", "budget", "réunion").inOrder()
    }

    @Test
    fun `positions are in the original text, past characters of two UTF-16 units`() {
        val texte = "😀 İstanbul et 😀 istanbul"

        val plages = RechercheDansLeTexte.occurrences(texte, RechercheDansLeTexte.mots("istanbul"), 10)

        // Each 😀 is two UTF-16 units: "İstanbul" starts at 3, the second "istanbul" at 18.
        assertThat(plages).containsExactly(Plage(3, 11), Plage(18, 26)).inOrder()
        assertThat(plages.map { texte.substring(it.debut, it.fin) }).containsExactly("İstanbul", "istanbul").inOrder()
    }

    @Test
    fun `overlapping occurrences of two words are one`() {
        assertThat(trouve("budgétaire", "budget geta")).containsExactly("budgéta")
    }

    @Test
    fun `at most max occurrences, the first ones`() {
        val plages = RechercheDansLeTexte.occurrences("a a a a a", RechercheDansLeTexte.mots("a"), 3)

        assertThat(plages).containsExactly(Plage(0, 1), Plage(2, 3), Plage(4, 5)).inOrder()
    }

    @Test
    fun `a search of spaces only finds nothing`() {
        assertThat(RechercheDansLeTexte.mots("   \t")).isEmpty()
        assertThat(trouve("du texte", "   ")).isEmpty()
    }
}
