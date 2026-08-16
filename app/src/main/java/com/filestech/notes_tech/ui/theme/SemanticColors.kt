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
 * Portage de `core/a11y.dart`. Elles existent parce qu'aucun rôle de `ColorScheme` ne veut dire
 * « favori » ni « c'est validé » : `primary` dit « c'est l'action principale », ce qui n'est pas la
 * même chose et se confond avec le reste de la barre.
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
        if (scheme.surface.luminance() < DARK_SURFACE_THRESHOLD) AMBER_200 else DEEP_ORANGE_900

    /**
     * Le vert de la coche de validation, dans la barre de l'éditeur.
     *
     * 🔴 **Relevé par Patrice sur le S9 le 2026-08-16 : « on la voit pas bien ».** Elle héritait de
     * `onSurfaceVariant`, la teinte des icônes ordinaires — donc la coche qui dit « c'est
     * enregistré, vous pouvez partir » avait exactement le même poids visuel que le micro et le
     * lien, qui ne disent rien de tel.
     *
     * ⚠️ **Ce n'est pas `primary`.** Ce rôle veut dire « l'action principale de cet écran » et il
     * sert déjà ailleurs ; le réemployer ici rendrait la coche indistincte de ce qui l'entoure sous
     * un thème Material You où `primary` dérive du fond d'écran. Il faut une couleur qui veuille
     * dire **validé**, et rien d'autre.
     *
     * ⚠️ La bascule se fait sur la **luminance de la surface réelle**, pour la raison exposée en
     * [favoriteIcon] : sous Material You, un thème déclaré clair peut porter une surface sombre.
     *
     * 🔧 Sa proposition alternative — remplacer la coche par un bouton libellé « Enregistrer » — a
     * été écartée, et c'est **mesuré** : un bouton libellé dans cette barre porte le compte à cinq
     * actions et écrase le titre **à zéro pixel**. C'est le défaut qu'il avait lui-même signalé le
     * matin même. Cf. `NoteEditorScreen.kt` et `04-PIEGES.md` §63.
     */
    val validationIcon: Color
        @Composable @ReadOnlyComposable
        get() = validationIconOn(MaterialTheme.colorScheme)

    /** Voir [validationIcon]. Extraite pour être testable sans composition. */
    fun validationIconOn(scheme: ColorScheme): Color =
        if (scheme.surface.luminance() < DARK_SURFACE_THRESHOLD) GREEN_300 else GREEN_800

    /** Material amber 200 — claire, pour un fond sombre. Mesurée à 12,9:1 sur la surface sombre. */
    private val AMBER_200 = Color(0xFFFFCC80)

    /**
     * Material **deep orange 900** — la teinte foncée, pour un fond clair.
     *
     * 🔴 **C'était `amber 700` (`#FB8C00`), et il échouait : 2,25:1 mesuré** sur la surface claire,
     * pour un seuil de 3:1 (WCAG 2.1, 1.4.11, éléments non textuels). L'étoile porte un **état** —
     * favori ou non — donc elle doit être perceptible, pas seulement jolie.
     *
     * ⚠️ Le défaut a été trouvé par le test écrit pour la coche verte, pas par un audit
     * d'accessibilité : la couleur était en place depuis le portage de `core/a11y.dart`, transposée
     * fidèlement, et **la version Flutter porte donc le même défaut**. Transposer fidèlement un
     * défaut le reconduit — c'est le sens inverse de la vérification, `04-PIEGES.md`.
     *
     * ⚠️ Cette teinte reste **insuffisante pour du texte** (4,5:1). Elle n'habille qu'une icône ; ne
     * pas la réemployer pour un libellé sans remesurer.
     */
    private val DEEP_ORANGE_900 = Color(0xFFE65100)

    /** Material green 300 — claire, pour un fond sombre. */
    private val GREEN_300 = Color(0xFF81C784)

    /** Material green 800 — foncée, pour un fond clair. */
    private val GREEN_800 = Color(0xFF2E7D32)

    /**
     * Seuil de bascule. `0.5` serait le milieu arithmétique ; `0.35` place la frontière là où la
     * perception bascule réellement, la luminance relative n'étant pas linéaire.
     */
    private const val DARK_SURFACE_THRESHOLD = 0.35f
}
