package com.filestech.notes_tech.ui.secure

import com.filestech.notes_tech.data.prefs.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qui demande `FLAG_SECURE`, et pour combien de temps.
 *
 * ## 🔴 Pourquoi un compteur, et pas un booléen
 *
 * La phase 5 posait le drapeau depuis un seul endroit : le réglage utilisateur. Un booléen suffisait
 * alors, et c'est exactement le piège — **le second poseur ne casse rien tant qu'il n'existe pas**.
 *
 * Trois demandeurs coexistent maintenant :
 *
 * | Demandeur | Durée | Ce qu'il protège |
 * |---|---|---|
 * | le réglage utilisateur | permanente | toute l'application |
 * | une feuille de coffre | le temps de la feuille | la saisie d'une phrase secrète ou d'un code |
 * | le mode panique | jusqu'à la mort du processus | le dialogue de confirmation et l'écran de fin |
 *
 * Sans compteur, deux poseurs qui retirent chacun de leur côté font disparaître la protection du
 * premier : la feuille de code se ferme, appelle `clearFlags`, et l'éditeur d'une note de coffre
 * resté ouvert derrière devient capturable — sans que rien ne le signale. Le drapeau effectif est
 * donc `réglage OU compteur > 0`, et il n'appartient à personne en particulier.
 *
 * C'est le mécanisme de la version publiée (`MainActivity.kt:123` côté natif, piloté depuis
 * `secure_window_service.dart`), transposé sans passer par un canal de méthodes : ici tout est
 * Kotlin, le compteur vit dans le processus et l'activité l'observe.
 *
 * ## ⚠️ Un `release()` de trop ne peut pas rendre l'application capturable
 *
 * Le compteur est borné à zéro par le bas. Ce n'est pas une coquetterie défensive : sans la borne,
 * un déséquilibre — une exception entre le `force()` et son `release()`, un écran quitté par un
 * chemin oublié — laisserait le compteur négatif, et le `force()` **suivant** le ramènerait
 * seulement à zéro. La protection serait alors absente précisément au moment où quelqu'un vient de
 * la demander, et aucune trace n'expliquerait pourquoi.
 *
 * Le déséquilibre reste un défaut ; la borne décide seulement de quel côté il échoue.
 */
@Singleton
class SecureWindowController @Inject constructor(private val settings: AppSettings) {

    private val demandes = MutableStateFlow(0)

    /**
     * Le drapeau tel qu'il doit être posé, **maintenant**.
     *
     * ⚠️ Lecture synchrone volontairement conservée : la valeur initiale de la collecte doit être
     * juste dès la première composition. Attendre la première émission du flux laisserait la
     * fenêtre capturable le temps que l'aperçu des applications récentes se prenne — c'est-à-dire
     * exactement la fenêtre qu'on veut fermer.
     */
    fun activeNow(): Boolean = settings.secureWindowNow() || demandes.value > 0

    /** Le drapeau effectif, réglage utilisateur et demandes ponctuelles confondus. */
    val active: Flow<Boolean> =
        combine(settings.secureWindow, demandes) { reglage, enCours -> reglage || enCours > 0 }
            .distinctUntilChanged()

    /**
     * Demande le drapeau pour la durée d'un écran, quel que soit le réglage.
     *
     * ⚠️ **Chaque appel doit être suivi d'exactement un [release]**, quel que soit le chemin de
     * sortie — y compris une annulation ou une exception. Depuis une interface, ne pas appeler ceci
     * directement : `SecureWindowGuard` s'en charge et lie les deux gestes au cycle de vie de la
     * composition.
     */
    fun force() = demandes.update { it + 1 }

    /** Rend une demande obtenue par [force]. Le réglage utilisateur reprend la main à zéro. */
    fun release() = demandes.update { (it - 1).coerceAtLeast(0) }

    /**
     * Demande le drapeau **définitivement**, sans contrepartie.
     *
     * Réservé au mode panique : la séquence est irréversible, l'application n'a plus rien à
     * afficher après elle, et le drapeau doit couvrir le dialogue de confirmation comme l'écran de
     * fin. Un `release()` correspondant n'existe pas — c'est le seul point du programme où
     * l'absence de contrepartie est le comportement voulu et non un déséquilibre.
     */
    fun forcePermanently() = force()
}
