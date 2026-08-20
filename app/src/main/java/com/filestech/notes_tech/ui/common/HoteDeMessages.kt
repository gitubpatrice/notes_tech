package com.filestech.notes_tech.ui.common

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.filestech.notes_tech.ui.theme.SemanticColors

/**
 * L'hôte des messages éphémères, aux couleurs de Files Tech.
 *
 * ## Pourquoi un composant, et pas six `SnackbarHost` habillés
 *
 * Six écrans en posent un : accueil, éditeur, corbeille, réglages, à propos, installation vocale.
 * Les habiller un par un, ce serait six endroits où la teinte peut diverger, et six endroits à
 * retoucher au prochain changement. Un seul point de passage, et la question ne se repose pas.
 *
 * *C'est la même raison qui a fait extraire `SemanticColors` : une couleur écrite deux fois est une
 * couleur qui finira par valoir deux choses.*
 *
 * ## 🔴 Ce que Material pose par défaut, et pourquoi on s'en écarte
 *
 * Sans habillage, `Snackbar` prend `inverseSurface` / `inverseOnSurface` : un bandeau **noir à
 * texte blanc**. Lisible, mais anonyme — c'est le message d'à peu près toutes les applications
 * Android. Écart demandé le 2026-08-20 après essai sur appareil.
 *
 * ⚠️ **Écart assumé avec la version Flutter publiée**, qui garde le noir. Il est documenté ici et
 * sur [SemanticColors.messageBackground] ; ce n'est pas une dérive du portage.
 *
 * ## ⚠️ Les quatre couleurs, pas seulement les deux évidentes
 *
 * `containerColor` et `contentColor` ne suffisent pas. Un `Snackbar` peut porter une **action** et
 * une **croix de fermeture**, qui tirent leur teinte de `actionColor` et
 * `dismissActionContentColor` — laissés à leur valeur par défaut, ils resteraient calculés pour le
 * fond noir de Material, sur un fond devenu bleu. Le défaut ne se verrait que sur les messages qui
 * portent une action, c'est-à-dire rarement, et donc tard.
 */
@Composable
fun HoteDeMessages(etat: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = etat, modifier = modifier) { donnees ->
        Snackbar(
            snackbarData = donnees,
            containerColor = SemanticColors.messageBackground,
            contentColor = SemanticColors.messageForeground,
            actionColor = SemanticColors.messageForeground,
            dismissActionContentColor = SemanticColors.messageForeground,
        )
    }
}
