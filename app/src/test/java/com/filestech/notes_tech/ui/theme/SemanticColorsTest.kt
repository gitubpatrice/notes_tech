package com.filestech.notes_tech.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Les couleurs sémantiques tiennent-elles leur promesse de contraste ?
 *
 * ## Pourquoi ce fichier a été écrit après coup
 *
 * `SemanticColors` portait depuis le début la mention « extraite pour être testable sans
 * composition ». Elle était exacte, et **aucun test n'existait** : une affirmation d'intention
 * qu'on lit comme une garantie. Écrit le 2026-08-16, en même temps que la coche verte.
 *
 * ## Ce qui est vérifié, et ce qui ne peut pas l'être
 *
 * Le seuil retenu est **3:1**, celui des éléments non textuels (WCAG 2.1, critère 1.4.11) : ces
 * couleurs habillent des **icônes**, pas du texte.
 *
 * ⚠️ Le test porte sur les schémas Material par défaut. Sous Material You, `surface` dérive du fond
 * d'écran de l'utilisateur et **aucun test ne peut énumérer les fonds possibles** — c'est
 * précisément pourquoi le choix se fait sur la luminance mesurée de la surface et non sur
 * l'étiquette du thème. Ce qui est figé ici, c'est que la bascule **choisit le bon côté**.
 *
 * ## 🔴 Il a d'abord été écrit en JUnit 4, et n'a donc rien exécuté
 *
 * Le projet configure `useJUnitPlatform()` : les tests JVM sont en **JUnit 5**. Avec
 * `org.junit.Test`, la classe **compile**, le gate passe au vert, et aucun de ces cas ne tourne —
 * il n'y a pas d'erreur, juste un fichier de résultats absent. Repéré parce que le total des tests
 * n'avait pas bougé : 171 avant, 171 après avoir ajouté cinq cas.
 *
 * ⚠️ C'est le §60 sous une autre forme : *lire le décompte, pas le `BUILD SUCCESSFUL`.* Un test qui
 * ne tourne pas est indiscernable d'un test qui passe, sauf si on compte.
 */
class SemanticColorsTest {

    @Test
    fun `la coche de validation contraste sur un fond clair`() {
        val surface = lightColorScheme().surface
        val ratio = contraste(SemanticColors.validationIconOn(lightColorScheme()), surface)
        assertThat(ratio).isGreaterThan(SEUIL_NON_TEXTUEL)
    }

    @Test
    fun `la coche de validation contraste sur un fond sombre`() {
        val surface = darkColorScheme().surface
        val ratio = contraste(SemanticColors.validationIconOn(darkColorScheme()), surface)
        assertThat(ratio).isGreaterThan(SEUIL_NON_TEXTUEL)
    }

    @Test
    fun `l etoile des favoris contraste sur les deux fonds`() {
        assertThat(contraste(SemanticColors.favoriteIconOn(lightColorScheme()), lightColorScheme().surface))
            .isGreaterThan(SEUIL_NON_TEXTUEL)
        assertThat(contraste(SemanticColors.favoriteIconOn(darkColorScheme()), darkColorScheme().surface))
            .isGreaterThan(SEUIL_NON_TEXTUEL)
    }

    /**
     * 🔴 **Le fond de marque des messages porte-t-il du texte lisible ?**
     *
     * Le bleu du damier a été choisi parce qu'il **identifie** l'application, pas parce qu'il
     * contraste : rien ne garantit d'avance qu'une couleur de marque soit lisible. Le seuil est
     * donc celui du **texte** (4,5:1), pas celui des éléments non textuels — un `Snackbar` ne
     * contient rien d'autre que des mots.
     *
     * ⚠️ Le fond ne dépend pas du thème, donc une seule mesure suffit ici. C'est l'exception :
     * les autres couleurs de ce fichier se posent sur une surface inconnue et se mesurent deux fois.
     */
    @Test
    fun `le texte des messages contraste sur le bleu du damier`() {
        val ratio = contraste(SemanticColors.messageForeground, SemanticColors.messageBackground)
        assertThat(ratio).isGreaterThan(SEUIL_TEXTE)
    }

    /**
     * The main buttons (3.1.0): white on the checkerboard blue, readable; and the button itself seen
     * against both themes' background (WCAG 1.4.11, 3:1).
     */
    @Test
    fun `the main buttons are readable and stand out on both themes`() {
        assertThat(contraste(SemanticColors.onPrimaryButton, SemanticColors.primaryButton)).isGreaterThan(SEUIL_TEXTE)
        assertThat(contraste(SemanticColors.primaryButton, schemeClair().background)).isAtLeast(3f)
        assertThat(contraste(SemanticColors.primaryButton, schemeSombre().background)).isAtLeast(3f)
    }

    /**
     * 🔴 **Le cas témoin.** Sans lui, les trois tests ci-dessus passeraient tout aussi bien si
     * [contraste] rendait une constante. Deux teintes proches doivent échouer au seuil.
     */
    @Test
    fun `le calcul de contraste sait aussi dire non`() {
        assertThat(contraste(Color(0xFF9E9E9E), Color(0xFFBDBDBD))).isLessThan(SEUIL_NON_TEXTUEL)
    }

    /**
     * ⚠️ **La bascule doit choisir le bon côté, c'est la seule chose que le test peut figer** sous
     * Material You. Une surface sombre appelle la teinte claire, et réciproquement — inverser les
     * deux constantes ne casserait aucun des tests de contraste ci-dessus, puisqu'ils évaluent la
     * couleur **rendue par la fonction** sur le fond correspondant.
     */
    @Test
    fun `la bascule suit la luminance de la surface et non l etiquette du theme`() {
        val surfaceSombre = darkColorScheme().surface
        val surfaceClaire = lightColorScheme().surface
        assertThat(surfaceSombre.luminance()).isLessThan(surfaceClaire.luminance())

        val surSombre = SemanticColors.validationIconOn(darkColorScheme())
        val surClaire = SemanticColors.validationIconOn(lightColorScheme())
        assertThat(surSombre.luminance()).isGreaterThan(surClaire.luminance())
    }

    private fun contraste(premier: Color, fond: Color): Float {
        val a = premier.luminance()
        val b = fond.luminance()
        val clair = maxOf(a, b)
        val sombre = minOf(a, b)
        return (clair + 0.05f) / (sombre + 0.05f)
    }

    private companion object {
        /** WCAG 2.1, critère 1.4.11 — éléments non textuels. Du texte demanderait 4,5:1. */
        const val SEUIL_NON_TEXTUEL = 3.0f

        /** WCAG 2.1, critère 1.4.3 — texte de taille courante. */
        const val SEUIL_TEXTE = 4.5f
    }
}
