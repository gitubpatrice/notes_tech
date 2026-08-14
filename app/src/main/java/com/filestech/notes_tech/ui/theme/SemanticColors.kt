package com.filestech.notes_tech.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Les couleurs sémantiques que Material 3 ne nomme pas.
 *
 * Portage de `core/a11y.dart`. Une seule pour l'instant — l'étoile des favoris — et elle existe
 * parce qu'aucun rôle de `ColorScheme` ne veut dire « favori ».
 */
object SemanticColors {

    /**
     * L'ambre de l'étoile des favoris.
     *
     * ⚠️ **Deux teintes, et le choix ne se fait pas sur le thème mais sur le FOND RÉEL.**
     *
     * La version Flutter bascule sur `brightness == dark`. Ça marche tant que la surface suit le
     * thème — ce qui cesse d'être vrai sous Material You, où `surface` dérive du fond d'écran de
     * l'utilisateur. Un thème déclaré clair peut y porter une surface sombre, et l'ambre foncée
     * posée dessus tombe alors sous le seuil de contraste.
     *
     * On décide donc sur la **luminance de la surface effective**, pas sur l'étiquette du thème.
     * C'est la même leçon que celle notée pour SMS Tech : aucune couleur de premier plan fixée à
     * l'avance ne garantit un rapport de contraste sur un fond inconnu.
     */
    val favoriteIcon: Color
        @Composable @ReadOnlyComposable
        get() = favoriteIconOn(MaterialTheme.colorScheme)

    /** Voir [favoriteIcon]. Extraite pour être testable sans composition. */
    fun favoriteIconOn(scheme: ColorScheme): Color =
        if (scheme.surface.luminance() < DARK_SURFACE_THRESHOLD) AMBER_200 else AMBER_700

    /** Material amber 200 — claire, pour un fond sombre. */
    private val AMBER_200 = Color(0xFFFFCC80)

    /** Material amber 700 — foncée, pour un fond clair. */
    private val AMBER_700 = Color(0xFFFB8C00)

    /**
     * Seuil de bascule. `0.5` serait le milieu arithmétique ; `0.35` place la frontière là où la
     * perception bascule réellement, la luminance relative n'étant pas linéaire.
     */
    private const val DARK_SURFACE_THRESHOLD = 0.35f
}
