package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * L'insertion d'un `[[Titre]]` là où l'utilisateur écrit.
 *
 * Ce que ces tests protègent : la position du curseur après coup, et le fait que la fonction ne lève
 * jamais. Les deux sont invisibles à la relecture et l'un des deux — la borne — ne se manifeste que
 * sur une course entre un rechargement de note et une sélection en cours.
 */
class InsertionDeTexteTest {

    private fun valeur(texte: String, debut: Int, fin: Int = debut) =
        TextFieldValue(text = texte, selection = TextRange(debut, fin))

    @Test
    @DisplayName("au milieu du texte, le fragment s'insère au curseur")
    fun auMilieu() {
        val issue = InsertionDeTexte.dansLaSelection(valeur("Voir  pour la suite", 5), "[[Notes]]")

        assertThat(issue.text).isEqualTo("Voir [[Notes]] pour la suite")
        // Le curseur suit le fragment : « Voir [[Notes]]| pour la suite ».
        assertThat(issue.selection).isEqualTo(TextRange(14))
    }

    @Test
    @DisplayName("une sélection est REMPLACÉE, pas contournée")
    fun surUneSelection() {
        val issue = InsertionDeTexte.dansLaSelection(valeur("Voir ceci pour la suite", 5, 9), "[[Notes]]")

        assertThat(issue.text).isEqualTo("Voir [[Notes]] pour la suite")
        assertThat(issue.selection).isEqualTo(TextRange(14))
    }

    /**
     * ⚠️ La sélection inversée n'est pas un cas d'école : elle se produit dès que l'utilisateur
     * sélectionne **de droite à gauche**, ce que fait n'importe quel glissement du doigt vers le
     * début du mot.
     */
    @Test
    @DisplayName("une sélection tirée à l'envers donne le même résultat")
    fun selectionInversee() {
        val issue = InsertionDeTexte.dansLaSelection(valeur("Voir ceci pour la suite", 9, 5), "[[Notes]]")

        assertThat(issue.text).isEqualTo("Voir [[Notes]] pour la suite")
        assertThat(issue.selection).isEqualTo(TextRange(14))
    }

    @Test
    @DisplayName("sur un contenu vide, le fragment devient tout le texte")
    fun texteVide() {
        val issue = InsertionDeTexte.dansLaSelection(valeur("", 0), "[[Notes]]")

        assertThat(issue.text).isEqualTo("[[Notes]]")
        assertThat(issue.selection).isEqualTo(TextRange(9))
    }

    @Test
    @DisplayName("en fin de texte, rien n'est perdu")
    fun enFin() {
        val issue = InsertionDeTexte.dansLaSelection(valeur("Voir ", 5), "[[Notes]]")

        assertThat(issue.text).isEqualTo("Voir [[Notes]]")
        assertThat(issue.selection).isEqualTo(TextRange(14))
    }

    /**
     * 🔴 Le cas qui ferait planter l'application au lieu d'insérer un lien.
     *
     * L'état porte le texte et la sélection, mais ils ne sont pas écrits au même instant : un
     * rechargement de note qui aboutit pendant qu'une sélection est en cours laisse une position qui
     * désigne un texte plus long que le nouveau. Sans les bornes, `replaceRange` lève.
     */
    @Test
    @DisplayName("une sélection qui déborde le texte ne lève pas — elle est ramenée à la fin")
    fun selectionQuiDeborde() {
        val issue = InsertionDeTexte.dansLaSelection(valeur("court", 40, 80), "[[Notes]]")

        assertThat(issue.text).isEqualTo("court[[Notes]]")
        assertThat(issue.selection).isEqualTo(TextRange(14))
    }

    /**
     * ⚠️ **Ce test a d'abord été écrit avec une position négative, et il échouait à construire son
     * propre cas** : `TextRange(-3)` lève. Une borne basse dans la fonction ne protégeait donc de
     * rien — Compose rend le cas impossible en amont — et elle a été retirée.
     *
     * Ce qui reste vérifiable, et qui compte, c'est l'asymétrie : `TextFieldValue` **n'exige pas**
     * que sa sélection tienne dans son texte (cf. le test au-dessus, qui construit `("court",
     * 40..80)` sans broncher), là où `TextRange` **exige** des positions non négatives. Une seule des
     * deux bornes est donc atteignable, et une seule est gardée.
     */
    @Test
    @DisplayName("TextRange interdit les positions négatives — la garde basse serait inatteignable")
    fun positionNegativeImpossible() {
        val leve = runCatching { TextRange(-3) }.exceptionOrNull()

        assertThat(leve).isInstanceOf(IllegalArgumentException::class.java)
    }
}
