package com.filestech.notes_tech.security.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Celui qui rappelle [VaultSessions.sweep]. Sans lui, la moitié active du verrouillage automatique
 * n'existe pas.
 *
 * ## 🔴 Pourquoi cette classe existe, alors que `sessionKey` refuse déjà les sessions périmées
 *
 * Parce que refuser n'est pas effacer. Le contrôle porté par [VaultSessions.sessionKey] garantit
 * qu'une clé expirée ne **sert** plus ; il ne garantit pas qu'elle **quitte** la mémoire. Si
 * personne ne consulte le coffre après son expiration — l'utilisateur ferme l'écran et passe à
 * autre chose —, la clé reste dans le tas jusqu'à ce que l'application soit tuée.
 *
 * C'est exactement le motif « garde échantillonnée qui ne se retente jamais » : la question à poser
 * est *« si la condition devient vraie une seconde plus tard, qui rappelle ce code ? »*. Avant
 * l'écriture de ce fichier, la réponse était **personne**, et `sweep()` était un chemin mort qui
 * donnait l'illusion d'une politique appliquée.
 *
 * ## Le rythme suit les échéances, il n'est pas fixe
 *
 * Le balayage rend le délai jusqu'à la prochaine échéance et la boucle dort exactement ce
 * temps-là. Un réveil périodique arbitraire coûterait de la batterie sans rien fermer plus tôt ; ce
 * schéma-ci ne se réveille que quand il a quelque chose à faire, et pas du tout quand aucun coffre
 * n'est ouvert.
 */
@Singleton
class VaultAutoLocker @Inject constructor(private val sessions: VaultSessions) {

    private var job: Job? = null

    /**
     * Démarre la boucle sur [scope], qui doit vivre aussi longtemps que le processus.
     *
     * Idempotent : un second appel ne lance pas de seconde boucle. Deux balayeurs ne feraient aucun
     * dégât — `sweep` est idempotent — mais ils réveilleraient le processus deux fois plus souvent
     * pour rien.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val prochaine = sessions.sweep()
                // Aucun coffre ouvert : on attend le pas de repos plutôt que de boucler à vide.
                // C'est ce que paie l'absence de signal d'ouverture ; le coût est un réveil par
                // minute au repos, et la simplicité vaut mieux ici qu'un canal de plus à tenir
                // synchronisé avec l'état des sessions.
                delay(prochaine ?: IDLE_POLL_MILLIS)
            }
        }
    }

    /** Arrête la boucle. Ne verrouille rien — [VaultSessions.lockAll] est un geste distinct. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val IDLE_POLL_MILLIS = 60_000L
    }
}
