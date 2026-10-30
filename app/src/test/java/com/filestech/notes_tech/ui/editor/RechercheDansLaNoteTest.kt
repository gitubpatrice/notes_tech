package com.filestech.notes_tech.ui.editor

import com.filestech.notes_tech.domain.markdown.MarkdownPreviewReader
import com.filestech.notes_tech.domain.recherche.RechercheDansLeTexte
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** "Find in note" (D-033): what the screen shows is searched, in the order it shows it. */
@DisplayName("Find in note")
class RechercheDansLaNoteTest {

    private fun mots(saisie: String) = RechercheDansLeTexte.mots(saisie)

    @Test
    fun `while reading, every kind of block is searched as drawn, a link's address is not`() {
        val note = listOf(
            "# Titre pomme",
            "",
            "Un paragraphe avec **pomme** et un [lien pomme](https://pomme.example).",
            "",
            "- item pomme",
            "  - imbriqué pomme",
            "",
            "> citation pomme",
            "",
            "```",
            "code pomme",
            "```",
            "",
            "| a | b |",
            "|---|---|",
            "| pomme | x |",
            "",
            "---",
        ).joinToString("\n")
        val elements = aplatir(MarkdownPreviewReader.read(note))

        val resultat = chercherDansLaNote(SourceDeRecherche.Lecture("Pomme du titre", elements), mots("pomme"))

        // The note's title first, outside the list; then heading, paragraph (twice: the bold word and
        // the link's text, not its address), the list item and its nested one, the quote, the code,
        // the table's row — its header has none.
        assertThat(resultat.occurrences.map { it.element }).containsExactly(-1, 0, 1, 1, 2, 2, 3, 4, 6).inOrder()
        assertThat(resultat.occurrences.first().cle).isSameInstanceAs(CleDuTitre)
        assertThat(resultat.tronque).isFalse()
    }

    @Test
    fun `while writing, the title then the body field, the source as typed`() {
        val resultat = chercherDansLaNote(
            SourceDeRecherche.Ecriture(titre = "Courses", corps = "**courses** du [samedi](https://courses.example)"),
            mots("courses"),
        )

        // Writing shows the Markdown as typed: the link's address is on screen, so it is found.
        assertThat(resultat.occurrences.map { it.cle }).containsExactly(CleDuTitre, CleDuCorps, CleDuCorps).inOrder()
        assertThat(resultat.plagesDe(CleDuCorps).map { it.debut }).containsExactly(2, 32).inOrder()
    }

    @Test
    fun `the ceiling is shared by the title and the body, and says it was reached`() {
        val source = SourceDeRecherche.Ecriture(titre = "a a", corps = "a a a")

        val coupe = chercherDansLaNote(source, mots("a"), max = 4)
        val juste = chercherDansLaNote(source, mots("a"), max = 5)

        assertThat(coupe.occurrences).hasSize(4)
        assertThat(coupe.tronque).isTrue()
        assertThat(juste.occurrences).hasSize(5)
        assertThat(juste.tronque).isFalse()
    }

    /**
     * 🔴 A vault note's query is not saved: it is a word of its plaintext, and the activity's saved state
     * must hold none (audit 3 axes of 3.2.0, S1). Another note's is, as before.
     */
    @Test
    @DisplayName("the bar's query is saved, a vault note's excepted")
    fun saisieSauvegardee() {
        val etat = EtatDeRechercheDansLaNote(ouverte = true, saisie = "salaire", index = 2, deplacer = false)
        val portee = androidx.compose.runtime.saveable.SaverScope { true }
        fun sauvee() = with(EtatDeRechercheDansLaNote.Saver) { portee.save(etat) }

        assertThat(sauvee()).isEqualTo(listOf(true, "salaire", 2))

        etat.saisieSauvegardee = false

        assertThat(sauvee()).isEqualTo(listOf(true, "", 2))
    }
}
