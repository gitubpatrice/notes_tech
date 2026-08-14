package com.filestech.notes_tech.ui.secure

import app.cash.turbine.test
import com.filestech.notes_tech.data.prefs.AppSettings
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Ce que le compteur doit tenir.
 *
 * Le défaut qu'il ferme n'était pas visible en phase 5 : avec un seul poseur, un booléen se comporte
 * exactement comme un compteur. Ces cas sont donc écrits **contre le second poseur**, celui qui
 * n'existait pas encore quand le code a été écrit.
 */
class SecureWindowControllerTest {

    private val reglage = MutableStateFlow(false)

    private val settings: AppSettings = mockk {
        every { secureWindow } returns reglage
        every { secureWindowNow() } answers { reglage.value }
    }

    private val controleur = SecureWindowController(settings)

    @Test
    @DisplayName("réglage désactivé et aucune demande : le drapeau est absent")
    fun repos() = runTest {
        assertThat(controleur.activeNow()).isFalse()
        controleur.active.test { assertThat(awaitItem()).isFalse() }
    }

    @Test
    @DisplayName("le réglage seul suffit à poser le drapeau")
    fun reglageSeul() = runTest {
        reglage.value = true
        assertThat(controleur.activeNow()).isTrue()
    }

    @Test
    @DisplayName("une demande pose le drapeau MALGRÉ le réglage désactivé")
    fun demandeSeule() = runTest {
        controleur.force()
        assertThat(controleur.activeNow()).isTrue()

        controleur.release()
        assertThat(controleur.activeNow()).isFalse()
    }

    /**
     * 🔴 **Le cas qui justifie tout le reste.**
     *
     * Deux écrans sensibles se superposent — une feuille de code par-dessus l'éditeur d'une note de
     * coffre — et le premier se ferme. Avec un booléen, sa fermeture appelait `clearFlags` et
     * découvrait l'écran resté derrière.
     */
    @Test
    @DisplayName("le départ du premier demandeur ne découvre pas le second")
    fun deuxDemandeursImbriques() = runTest {
        controleur.force()
        controleur.force()

        controleur.release()
        assertThat(controleur.activeNow()).isTrue()

        controleur.release()
        assertThat(controleur.activeNow()).isFalse()
    }

    /**
     * ⚠️ Un déséquilibre reste un défaut ; ce cas fixe seulement **de quel côté il échoue**.
     *
     * Sans la borne, le compteur descendrait à -1 et la demande suivante le ramènerait à 0 : la
     * protection serait absente précisément au moment où un écran vient de la demander.
     */
    @Test
    @DisplayName("un release de trop ne peut pas rendre une demande ultérieure inopérante")
    fun bornéÀZéro() = runTest {
        controleur.release()
        controleur.release()

        controleur.force()
        assertThat(controleur.activeNow()).isTrue()
    }

    @Test
    @DisplayName("le drapeau tient quand le réglage s'éteint sous une demande en cours")
    fun reglageEteintSousUneDemande() = runTest {
        reglage.value = true
        controleur.force()

        reglage.value = false

        assertThat(controleur.activeNow()).isTrue()
    }

    @Test
    @DisplayName("le flux émet les transitions, et seulement elles")
    fun fluxSansRepetition() = runTest {
        controleur.active.test {
            assertThat(awaitItem()).isFalse()

            controleur.force()
            assertThat(awaitItem()).isTrue()

            // Une seconde demande ne change pas le drapeau : rien ne doit être émis.
            controleur.force()
            expectNoEvents()

            controleur.release()
            expectNoEvents()

            controleur.release()
            assertThat(awaitItem()).isFalse()
        }
    }

    /**
     * Le mode panique ne rend jamais sa demande. Le vérifier ici évite qu'un futur nettoyage
     * « symétrise » l'appel par souci de cohérence et rende le dialogue de confirmation capturable.
     */
    @Test
    @DisplayName("la demande permanente du mode panique survit à la fin de la séquence")
    fun demandePermanente() = runTest {
        controleur.forcePermanently()
        assertThat(controleur.activeNow()).isTrue()

        // Un écran quelconque entre et sort par-dessus : la demande permanente ne bouge pas.
        controleur.force()
        controleur.release()

        assertThat(controleur.activeNow()).isTrue()
    }
}
