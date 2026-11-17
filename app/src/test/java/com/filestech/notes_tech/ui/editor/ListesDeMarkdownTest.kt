package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * D-048 and D-049 (3.5.0). Every case is written as the text the user sees: `|` is the cursor, `«` and `»`
 * the ends of a selection — so that an offset wrong by one fails with the text it produced.
 */
@DisplayName("Listes de Markdown dans l'éditeur")
class ListesDeMarkdownTest {

    @Nested
    @DisplayName("Lecture du début de ligne")
    inner class Lecture {

        @Test
        fun `markers need a space after them, as in CommonMark`() {
            assertThat(DebutsDeLigne.lire("- a").marqueur).isEqualTo("- ")
            assertThat(DebutsDeLigne.lire("12) a").marqueur).isEqualTo("12) ")
            assertThat(DebutsDeLigne.lire("1.Eat").marqueur).isNull()
            assertThat(DebutsDeLigne.lire("-dash").marqueur).isNull()
            assertThat(DebutsDeLigne.lire("1．Eat").marqueur).isNull()
        }

        @Test
        fun `a rule is not a list`() {
            listOf("---", "- - -", "* * *", "___", "  ***").forEach { regle ->
                assertThat(DebutsDeLigne.lire(regle).marqueur).isNull()
            }
        }

        @Test
        fun `container, marker and box keep their spaces as written`() {
            val debut = DebutsDeLigne.lire(">  -   [x]  fait")
            assertThat(debut.conteneur).isEqualTo(">  ")
            assertThat(debut.marqueur).isEqualTo("-   ")
            assertThat(debut.case).isEqualTo("[x]  ")
            assertThat(debut.longueur).isEqualTo(">  -   [x]  ".length)
        }

        @Test
        fun `a box needs a marker before it`() {
            assertThat(DebutsDeLigne.lire("[ ] pas une tache").estUneTache).isFalse()
            assertThat(DebutsDeLigne.lire("- [ ]").estUneTache).isTrue()
            assertThat(DebutsDeLigne.lire("- [y] pas une case").case).isNull()
        }
    }

    @Nested
    @DisplayName("D-048 : insérer une liste à cocher")
    inner class Inserer {

        @Test
        fun `a plain line gets a new item, the cursor follows its text`() {
            assertThat(basculer("Lai|t")).isEqualTo("- [ ] Lai|t")
        }

        @Test
        fun `a blank line becomes an empty item, the cursor after it`() {
            assertThat(basculer("|")).isEqualTo("- [ ] |")
            assertThat(basculer("Courses\n|\nfin")).isEqualTo("Courses\n- [ ] |\nfin")
        }

        @Test
        fun `a bullet keeps its own character, a number its number`() {
            assertThat(basculer("- pai|n")).isEqualTo("- [ ] pai|n")
            assertThat(basculer("* pai|n")).isEqualTo("* [ ] pai|n")
            assertThat(basculer("3. pai|n")).isEqualTo("3. [ ] pai|n")
        }

        @Test
        fun `indentation and quote markers are kept`() {
            assertThat(basculer("  sous|-tâche")).isEqualTo("  - [ ] sous|-tâche")
            assertThat(basculer("> cit|ation")).isEqualTo("> - [ ] cit|ation")
        }

        @Test
        fun `the second press takes the box off`() {
            assertThat(basculer("- [ ] Lai|t")).isEqualTo("Lai|t")
            assertThat(basculer("- [x] Lai|t")).isEqualTo("Lai|t")
            assertThat(basculer("2. [X] Lai|t")).isEqualTo("2. Lai|t")
        }

        @Test
        fun `a cursor inside the removed marker goes to where the text starts`() {
            assertThat(basculer("- [|x] Lait")).isEqualTo("|Lait")
        }

        @Test
        fun `a selection boxes every non-blank line it touches, and follows them`() {
            assertThat(basculer("«Lait\n\n- Pain\n- [x] Beurre»"))
                .isEqualTo("«- [ ] Lait\n\n- [ ] Pain\n- [x] Beurre»")
        }

        @Test
        fun `a selection made of tasks only loses its boxes`() {
            assertThat(basculer("«- [ ] Lait\n- [x] Pain»")).isEqualTo("«Lait\nPain»")
        }

        @Test
        fun `a selection ending at the start of a line does not take that line`() {
            assertThat(basculer("«Lait\n»Pain")).isEqualTo("«- [ ] Lait\n»Pain")
        }

        /** GPT-5.6 review of 3.5.0: a lone `\r` ends a line — `second` alone gets the box. */
        @Test
        fun `the line is found across Windows and old Mac line ends`() {
            assertThat(basculer("premier\r«second»")).isEqualTo("premier\r«- [ ] second»")
            assertThat(basculer("premier\r\nsec|ond")).isEqualTo("premier\r\n- [ ] sec|ond")
            assertThat(basculer("«Lait\r\n»Pain")).isEqualTo("«- [ ] Lait\r\n»Pain")
        }

        @Test
        fun `a selection beyond the text is bounded, not thrown`() {
            val horsDuTexte = TextFieldValue("Lait", TextRange(40, 80))
            assertThat(ListeACocher.basculer(horsDuTexte).text).isEqualTo("- [ ] Lait")
        }
    }

    @Nested
    @DisplayName("D-049 : Entrée continue une liste")
    inner class Continuer {

        @Test
        fun `a task opens an unticked task`() {
            assertThat(entree("- [ ] Lait|")).isEqualTo("- [ ] Lait\n- [ ] |")
            assertThat(entree("- [x] Lait|")).isEqualTo("- [x] Lait\n- [ ] |")
        }

        @Test
        fun `a bullet opens the same bullet, a number the next one`() {
            assertThat(entree("* Lait|")).isEqualTo("* Lait\n* |")
            assertThat(entree("+ Lait|")).isEqualTo("+ Lait\n+ |")
            assertThat(entree("9. Lait|")).isEqualTo("9. Lait\n10. |")
            assertThat(entree("1) Lait|")).isEqualTo("1) Lait\n2) |")
        }

        @Test
        fun `indentation and quotes are kept`() {
            assertThat(entree("  - [ ] sous|")).isEqualTo("  - [ ] sous\n  - [ ] |")
            assertThat(entree("> - cité|")).isEqualTo("> - cité\n> - |")
        }

        /** GPT-5.6 review of 3.5.0: the rest follows as typed — its spaces were trimmed, text the user wrote. */
        @Test
        fun `Enter in the middle of an item takes the rest along, as typed`() {
            assertThat(entree("- [ ] Lait| Pain")).isEqualTo("- [ ] Lait\n- [ ] | Pain")
            assertThat(entree("- foo|  bar")).isEqualTo("- foo\n- |  bar")
        }

        /** GPT-5.6 review of 3.5.0: a "list" in code is code — Enter there is an ordinary line break. */
        @Test
        fun `no item is added in code`() {
            assertThat(entree("```\n- a|\n```")).isNull()
            assertThat(entree("> ~~~\n> - a|")).isNull()
            assertThat(entree("Texte\n\n    - du code|")).isNull()
            // The controls: after the fence closes, and a nested list indented by four, are lists.
            assertThat(entree("```\ncode\n```\n- a|")).isEqualTo("```\ncode\n```\n- a\n- |")
            assertThat(entree("1. un\n    - deux|")).isEqualTo("1. un\n    - deux\n    - |")
        }

        /** GPT-5.6 review of 3.5.0: `\r\n` and a lone `\r` end a line too. */
        @Test
        fun `Windows and old Mac line ends are line ends`() {
            assertThat(entree("- a\r- b|")).isEqualTo("- a\r- b\n- |")
            assertThat(entree("- a\r\n- [ ] b|\r\nsuite")).isEqualTo("- a\r\n- [ ] b\n- [ ] |\r\nsuite")
            assertThat(entree("```\r\n- a|")).isNull()
        }

        /**
         * 🔴 Seen on the S9: with no empty line between them, a line typed under the list was read as part
         * of its last item (CommonMark's lazy continuation) — ticked with it.
         */
        @Test
        fun `Enter on an empty item ends the list, an empty line before what follows`() {
            assertThat(entree("- [ ] Lait\n- [ ] |")).isEqualTo("- [ ] Lait\n\n|")
            assertThat(entree("> - a\n> - |")).isEqualTo("> - a\n>\n> |")
            // Under a nested list, the line goes back to the parent item.
            assertThat(entree("- a\n  - b\n  - |")).isEqualTo("- a\n  - b\n\n  |")
            assertThat(entree("- a\r\n- |\r\nsuite")).isEqualTo("- a\r\n\n|\r\nsuite")
        }

        /** The controls: no second empty line where one is there already, nor above the first line. */
        @Test
        fun `no empty line is added where none is needed`() {
            assertThat(entree("Texte\n\n- [ ] |")).isEqualTo("Texte\n\n|")
            assertThat(entree("> - a\n>\n> - |")).isEqualTo("> - a\n>\n> |")
            assertThat(entree("- [ ]|")).isEqualTo("|")
            assertThat(entree("> - |")).isEqualTo("> |")
            assertThat(entree("- [ ] |\nsuite")).isEqualTo("|\nsuite")
        }

        @Test
        fun `what is not a list item is left as typed`() {
            assertThat(entree("Lait|")).isNull()
            assertThat(entree("1.Eat|")).isNull()
            assertThat(entree("---|")).isNull()
            assertThat(entree("> cité|")).isNull()
        }

        @Test
        fun `Enter inside the marker is an ordinary line break`() {
            assertThat(entree("-| Lait")).isNull()
            assertThat(entree("|- Lait")).isNull()
        }

        @Test
        fun `only ONE line break typed at the cursor counts`() {
            val avant = valeur("- Lait|")
            // A paste of two lines.
            assertThat(SuiteDeListe.apres(avant, valeur("- Lait\nPain\n|"))).isNull()
            // A selection replaced by the line break.
            assertThat(SuiteDeListe.apres(valeur("- «Lait»"), valeur("- \n|"))).isNull()
            // The text changed elsewhere along with the line break (a keyboard correcting a word).
            assertThat(SuiteDeListe.apres(valeur("- Lati|"), valeur("- Lait\n|"))).isNull()
            // The cursor did not end right after the line break.
            assertThat(SuiteDeListe.apres(avant, TextFieldValue("- Lait\n", TextRange(0)))).isNull()
            // A cursor move alone.
            assertThat(SuiteDeListe.apres(avant, valeur("- La|it"))).isNull()
        }

        /** The case [entree] builds: the text with ONE `\n` typed at the cursor. */
        private fun entree(texte: String): String? {
            val avant = valeur(texte)
            val ici = avant.selection.start
            val avecLeRetour = avant.text.substring(0, ici) + "\n" + avant.text.substring(ici)
            val tape = TextFieldValue(avecLeRetour, TextRange(ici + 1))
            return SuiteDeListe.apres(avant, tape)?.let(::rendu)
        }
    }

    private fun basculer(texte: String): String = rendu(ListeACocher.basculer(valeur(texte)))

    /** `|` is the cursor; `«` and `»` the ends of a selection. */
    private fun valeur(texte: String): TextFieldValue {
        val curseur = texte.indexOf('|')
        if (curseur >= 0) return TextFieldValue(texte.removeRange(curseur, curseur + 1), TextRange(curseur))
        val debut = texte.indexOf('«')
        val fin = texte.indexOf('»') - 1
        return TextFieldValue(texte.replace("«", "").replace("»", ""), TextRange(debut, fin))
    }

    private fun rendu(valeur: TextFieldValue): String {
        val selection = valeur.selection
        if (selection.collapsed) {
            return StringBuilder(valeur.text).insert(selection.start, '|').toString()
        }
        return StringBuilder(valeur.text).insert(selection.max, '»').insert(selection.min, '«').toString()
    }
}
