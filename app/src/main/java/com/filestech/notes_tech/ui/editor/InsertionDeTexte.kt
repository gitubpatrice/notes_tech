package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * L'insertion d'un fragment à l'endroit où l'utilisateur écrit.
 *
 * ## Pourquoi une fonction pure, isolée du ViewModel
 *
 * C'est la seule partie de l'insertion de lien qui puisse se tromper en silence : un décalage d'un
 * caractère met le curseur au mauvais endroit, une sélection non bornée lève sur une chaîne qui a
 * changé sous elle, et rien de tout cela ne se voit à la relecture. Le reste — ouvrir la feuille,
 * programmer l'enregistrement — est du câblage qu'un essai sur appareil révèle immédiatement.
 *
 * Sortie du ViewModel, elle se teste sans base, sans coffre et sans Hilt.
 */
object InsertionDeTexte {

    /**
     * Remplace la sélection courante par [fragment] et laisse le curseur **après** lui.
     *
     * ⚠️ **Seule la borne HAUTE est ramenée, et c'est mesuré, pas supposé.** `TextRange` refuse
     * lui-même les positions négatives — son constructeur lève — donc une borne basse ne protégerait
     * de rien et serait du code que rien ne peut atteindre. `TextFieldValue`, en revanche, ne vérifie
     * **pas** que la sélection tienne dans son texte : la paire `("court", 40..80)` se construit sans
     * broncher. Les deux faits ont été constatés en écrivant le test, qui échouait à fabriquer son
     * propre cas négatif.
     *
     * Le débordement, lui, est atteignable : la sélection et le texte viennent de l'état mais ne sont
     * pas écrits au même instant, et un rechargement de note qui aboutit pendant qu'une sélection est
     * en cours laisse une position qui désigne un texte plus long que le nouveau. Sans la borne,
     * `replaceRange` lèverait au milieu d'un geste anodin.
     *
     * ⚠️ Le curseur est posé **après** le fragment, sélection vide. Le laisser sélectionner ce qui
     * vient d'être inséré ferait disparaître le lien à la frappe suivante — l'utilisateur qui
     * continue sa phrase écraserait ce qu'il vient de demander.
     */
    fun dansLaSelection(valeur: TextFieldValue, fragment: String): TextFieldValue {
        val longueur = valeur.text.length
        val debut = valeur.selection.min.coerceAtMost(longueur)
        val fin = valeur.selection.max.coerceIn(debut, longueur)
        val texte = valeur.text.replaceRange(debut, fin, fragment)
        return TextFieldValue(text = texte, selection = TextRange(debut + fragment.length))
    }
}
