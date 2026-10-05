package com.filestech.notes_tech.ui.secure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Le contrôleur du drapeau, accessible depuis n'importe quel écran.
 *
 * ## ⚠️ Aucun défaut, et c'est délibéré
 *
 * Un défaut inerte — un contrôleur muet qui ne pose rien — ferait passer un écran non protégé pour
 * un écran protégé. La panne serait invisible : l'appel compile, l'écran s'affiche, et la capture
 * fonctionne. Sans défaut, l'oubli du fournisseur échoue **à la première composition**, sur le
 * poste de développement, pas chez l'utilisateur.
 */
val LocalSecureWindow = staticCompositionLocalOf<SecureWindowController> {
    error("Aucun SecureWindowController fourni — cet écran croirait être protégé sans l'être.")
}

/**
 * Force `FLAG_SECURE` tant que cet écran est composé.
 *
 * À poser dans tout écran qui affiche un secret : la saisie d'une phrase secrète ou d'un code, le
 * contenu déchiffré d'une note de coffre. Le réglage utilisateur reprend la main dès que le dernier
 * écran demandeur disparaît.
 *
 * ```kotlin
 * @Composable
 * fun FeuilleDeCode() {
 *     SecureWindowGuard()
 *     // …
 * }
 * ```
 *
 * ## ⚠️ La clé de l'effet compte autant que son corps
 *
 * [active] est la **seule** clé. Une recomposition déclenchée par autre chose — une frappe, un
 * changement de thème — ne doit pas relancer l'effet : le `onDispose` rendrait la demande, le corps
 * en reprendrait une, et le compteur oscillerait autour de sa valeur. L'oscillation est inoffensive
 * tant qu'elle est ordonnée, mais rien ne garantit qu'elle le reste quand deux écrans se
 * recomposent ensemble. Avec cette clé, l'invariant tient : **une demande par entrée en
 * composition, une seule restitution à la sortie**.
 *
 * ⚠️ Ce n'est pas un `LaunchedEffect` : la restitution doit survivre à l'annulation de la
 * composition, et un effet suspendable annulé n'exécute pas sa suite. `DisposableEffect.onDispose`
 * s'exécute, lui, y compris quand l'écran est quitté brutalement.
 *
 * @param active `false` n'annule pas seulement la demande à venir, il **rend** celle en cours : un
 *   écran dont le contenu cesse d'être sensible — une note sortie de son coffre — cesse aussitôt
 *   d'imposer le drapeau aux autres.
 */
@Composable
fun SecureWindowGuard(active: Boolean = true) {
    val controleur = LocalSecureWindow.current
    DisposableEffect(active) {
        if (active) controleur.force()
        onDispose { if (active) controleur.release() }
    }
}
