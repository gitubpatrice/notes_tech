package com.filestech.notes_tech.ui.vault

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Une feuille qui doit refuser de se fermer le refuse-t-elle vraiment ?**
 *
 * ## Pourquoi ce test existe
 *
 * `VaultSheets.kt` transpose un dialogue Flutter **volontairement bloquant**
 * (`PopScope(canPop: false)`, `barrierDismissible: false`). Pendant le chiffrement du contenu d'un
 * coffre, le coffre est **déjà créé** : fermer annule la coroutine, le dossier reste un coffre, ses
 * notes restent **en clair**, et la feuille disparaît sans rien dire. L'utilisateur croit avoir
 * annulé une création qui a eu lieu.
 *
 * Le portage posait cette garde dans le seul `onDismissRequest`. Deux relectures externes du
 * 2026-08-16 ont donné des réponses **opposées** sur sa suffisance : l'une affirmait que le
 * balayage la contourne, l'autre refusait de se prononcer sans exécution. Ce fichier arbitre par la
 * mesure — c'est le seul arbitre admis ici.
 *
 * ## 🔴 Le cas témoin n'est pas une politesse
 *
 * [sans_veto_un_balayage_fait_disparaitre_la_feuille] existe pour prouver que **le geste a bien
 * lieu**. Sans lui, les trois autres cas passeraient tout aussi bien si `swipeDown()` ne touchait
 * rien du tout : une feuille qui ne bouge pas parce que personne ne l'a poussée serait comptée
 * comme une feuille correctement verrouillée. Il faut valider l'instrument sur un cas positif connu
 * avant de croire ses cas négatifs.
 *
 * ⚠️ Le voile (scrim) n'est **pas** couvert : son nœud ne s'atteint que par une description de
 * contenu traduite, donc dépendante de la langue de l'appareil. Les deux relecteurs s'accordent sur
 * le fait qu'un clic sur le voile finit par demander `Hidden` à l'état, donc que
 * `confirmValueChange` le couvre ; ce test ne le **démontre pas**, et le dire vaut mieux que de
 * l'écrire dans un nom de méthode.
 */
@RunWith(AndroidJUnit4::class)
class FermetureDeFeuilleTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val fermeturesDemandees = mutableListOf<Unit>()

    /**
     * Pose une feuille dont la fermeture est refusée ou non, **sans jamais la retirer de l'arbre**.
     *
     * ⚠️ C'est délibéré : ne pas la retirer reproduit exactement ce que fait le code de production
     * quand sa garde décide de ne rien faire. Une feuille retirée à chaque demande masquerait la
     * question posée.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun poserLaFeuille(veto: Boolean) {
        regle.setContent {
            val etat = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { cible -> !(veto && cible == SheetValue.Hidden) },
            )
            ModalBottomSheet(
                onDismissRequest = { fermeturesDemandees += Unit },
                sheetState = etat,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .testTag(CONTENU),
                )
            }
        }
        regle.waitForIdle()
        regle.onNodeWithTag(CONTENU).assertIsDisplayed()
    }

    /**
     * 🔴 **Le cas témoin, et la démonstration du défaut.**
     *
     * Sans veto, le balayage fait disparaître la feuille **et** `onDismissRequest` est appelé
     * ensuite. Autrement dit : au moment où la garde s'exécute, la feuille est déjà partie de
     * l'écran. Ignorer l'appel ne la ramène pas — ça laisse un composable invisible et un travail
     * qui continue sans rien à l'écran pour le dire.
     */
    @Test
    fun sans_veto_un_balayage_fait_disparaitre_la_feuille() {
        poserLaFeuille(veto = false)

        regle.onNodeWithTag(CONTENU).performTouchInput { swipeDown() }
        regle.waitForIdle()

        regle.onNodeWithTag(CONTENU).assertIsNotDisplayed()
        assertThat(fermeturesDemandees).hasSize(1)
    }

    @Test
    fun avec_veto_un_balayage_ne_ferme_pas_la_feuille() {
        poserLaFeuille(veto = true)

        regle.onNodeWithTag(CONTENU).performTouchInput { swipeDown() }
        regle.waitForIdle()

        regle.onNodeWithTag(CONTENU).assertIsDisplayed()
        assertThat(fermeturesDemandees).isEmpty()
    }

    /**
     * 🔴🔴 **Le cas qui départage les deux relectures, et la raison de garder les DEUX gardes.**
     *
     * Mesuré sur le S9 le 2026-08-16 : avec le veto, un appui sur Retour **laisse la feuille à
     * l'écran** — mais `onDismissRequest` **est appelé quand même**. Le Retour ne passe donc pas par
     * la machine d'état : il s'adresse directement à l'appelant.
     *
     * ⚠️ Une relecture affirmait l'inverse — « le bouton Retour est ignoré, `onDismissRequest` ne
     * sera jamais appelé ». C'était faux, et le croire aurait conduit à **retirer** la garde de
     * `onDismissRequest` comme devenue redondante. Elle ne l'est pas : c'est elle, et elle seule,
     * qui empêche l'annulation du travail sur ce chemin-là.
     *
     * ## Ce qui rend la paire exhaustive
     *
     * Il n'y a que deux façons de faire disparaître une `ModalBottomSheet` : demander `Hidden` à
     * son état, ou appeler `onDismissRequest`. Le veto ferme la première, la garde ferme la
     * seconde. C'est pour ça que le voile, non mesuré ici, est couvert quelle que soit celle des
     * deux qu'il emprunte.
     */
    @Test
    fun avec_veto_le_retour_laisse_la_feuille_mais_appelle_quand_meme_le_rappel() {
        poserLaFeuille(veto = true)

        Espresso.pressBack()
        regle.waitForIdle()

        regle.onNodeWithTag(CONTENU).assertIsDisplayed()
        assertThat(fermeturesDemandees).hasSize(1)
    }

    /**
     * ⚠️ Le pendant du précédent : sans veto, Retour **ferme**. Il vaut pour les mêmes raisons que
     * le témoin du balayage — sans lui, le test ci-dessus passerait si `pressBack()` n'atteignait
     * jamais la feuille.
     */
    @Test
    fun sans_veto_le_retour_ferme_la_feuille() {
        poserLaFeuille(veto = false)

        Espresso.pressBack()
        regle.waitForIdle()

        regle.onNodeWithTag(CONTENU).assertIsNotDisplayed()
        assertThat(fermeturesDemandees).hasSize(1)
    }

    /**
     * 🔴🔴 **Le piège que le veto pouvait introduire — mesuré, pas supposé.**
     *
     * `rememberModalBottomSheetState` construit son état sous un `rememberSaveable` dont
     * `confirmValueChange` **est une clé**. Une lambda dont l'instance change à chaque
     * recomposition reconstruit donc l'état **à chaque fois** : mesuré ici, six compositions ont
     * donné **six** états. Sur la feuille de saisie, où chaque frappe recompose, cela
     * réinitialiserait la feuille sous les doigts de l'utilisateur.
     *
     * Ce test fige le piège plutôt que de le corriger : il capture `n`, qui change à chaque tour,
     * donc la lambda n'est jamais mémorisable. C'est le repoussoir dont
     * [la_forme_du_code_de_production_conserve_un_seul_etat] est le pendant.
     *
     * ⚠️ Compter les **identités** est ce qui rend la question décidable. Une feuille recréée puis
     * ré-affichée ressemble à s'y méprendre à une feuille intacte : la regarder n'aurait rien dit.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun une_lambda_non_memorisable_recree_l_etat_a_chaque_recomposition() {
        val sonde = Sonde()
        val compteur = mutableStateOf(0)
        val etatsVus = mutableSetOf<SheetState>()

        regle.setContent {
            val n = compteur.value
            val etat = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { cible -> !(cible == SheetValue.Hidden && sonde.bloque(n)) },
            )
            etatsVus += etat
            FeuillePourLaSonde(etat)
        }
        regle.waitForIdle()

        repeat(RECOMPOSITIONS) {
            regle.runOnUiThread { compteur.value += 1 }
            regle.waitForIdle()
        }

        assertThat(etatsVus).hasSize(RECOMPOSITIONS + 1)
    }

    /**
     * ✅ **La forme retenue dans `VaultSheets.kt`, mesurée telle quelle.**
     *
     * `etatDeFeuilleDeCoffre(bloquer = viewModel::chiffrementEnCours)` : la lambda interne ne
     * capture qu'une **référence de méthode liée**, dont l'égalité porte sur le récepteur et la
     * méthode. Le compilateur Compose peut donc la mémoriser, et l'état survit aux recompositions.
     *
     * Sans ce test, le correctif du balayage aurait pu réparer un chemin en cassant la saisie —
     * un échange qu'aucun des deux relecteurs n'avait envisagé.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun la_forme_du_code_de_production_conserve_un_seul_etat() {
        val sonde = Sonde()
        val compteur = mutableStateOf(0)
        val etatsVus = mutableSetOf<SheetState>()

        regle.setContent {
            val n = compteur.value
            val etat = etatCommeEnProduction(bloquer = sonde::bloquant)
            etatsVus += etat
            FeuillePourLaSonde(etat, marqueur = n)
        }
        regle.waitForIdle()

        repeat(RECOMPOSITIONS) {
            regle.runOnUiThread { compteur.value += 1 }
            regle.waitForIdle()
        }

        assertThat(compteur.value).isEqualTo(RECOMPOSITIONS)
        assertThat(etatsVus).hasSize(1)
        regle.onNodeWithTag(CONTENU).assertIsDisplayed()

        // Et le veto tient encore : un état conservé mais devenu sourd ne vaudrait pas mieux.
        regle.onNodeWithTag(CONTENU).performTouchInput { swipeDown() }
        regle.waitForIdle()
        regle.onNodeWithTag(CONTENU).assertIsDisplayed()
    }

    /** La copie conforme de `VaultSheets.etatDeFeuilleDeCoffre`, qui est privée. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun etatCommeEnProduction(bloquer: () -> Boolean = { false }) = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { cible -> !(cible == SheetValue.Hidden && bloquer()) },
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FeuillePourLaSonde(etat: SheetState, marqueur: Int = 0) {
        ModalBottomSheet(onDismissRequest = { fermeturesDemandees += Unit }, sheetState = etat) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .testTag(CONTENU),
            )
            Text(text = "$marqueur")
        }
    }

    /** Volontairement une classe nue, sans `@Stable` : voir les deux tests qui l'utilisent. */
    private class Sonde {
        fun bloque(@Suppress("UNUSED_PARAMETER") n: Int): Boolean = true

        fun bloquant(): Boolean = true
    }

    private companion object {
        const val CONTENU = "contenu-de-la-feuille"
        const val RECOMPOSITIONS = 5
    }
}
