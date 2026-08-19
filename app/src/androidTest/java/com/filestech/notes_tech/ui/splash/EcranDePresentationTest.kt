package com.filestech.notes_tech.ui.splash

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Les trois portes de sortie de l'écran de présentation, et le chronomètre de la signature.**
 *
 * Ligne `splash_screen.dart` de `docs/05-PARITE.md`, critère écrit : *« signature Files Tech ; masque
 * l'acquisition de la KEK »*.
 *
 * ## 🔴🔴 Ce que rien ne mesurait
 *
 * `SplashScreen.kt` fait 230 lignes et n'avait **aucun test**. Deux propriétés distinctes y sont
 * affirmées par des commentaires, et aucune n'était vérifiée.
 *
 * **L'idempotence.** Trois portes — la touche, le retour, l'échéance — déclenchées depuis trois
 * contextes différents. Le KDoc dit : *« sans elle, un retour pressé pendant la fermeture
 * automatique produit deux navigations, et la seconde s'applique à l'écran d'accueil déjà
 * affiché »*. Un défaut de ce genre ne lève pas : il fait disparaître un écran que l'utilisateur
 * venait d'ouvrir.
 *
 * **Le chronomètre.** Les durées ne sont pas des réglages, ce sont une **signature de marque**
 * partagée par les neuf applications Files Tech. Les valeurs ci-dessous sont transcrites du Dart
 * publié (`splash_screen.dart:53-85`), pas relues depuis le portage — dont les constantes sont
 * privées, ce qui est bien.
 *
 * ## ⚠️ Ce que ces cas NE prouvent pas
 *
 * Que l'échéance ne soit pas raccourcie quand le système demande de réduire les animations. Le
 * portage affirme le choix — *« le réglage système dit "ne bouge pas", pas "va plus vite" »* — et le
 * vérifier demanderait d'écrire `animator_duration_scale` dans les réglages **globaux de
 * l'appareil**, ce qui rendrait ce fichier dépendant d'un état hors du test. Mesuré une fois à la
 * main, hors suite : cf. `04-PIEGES.md` §113.
 *
 * Que l'écran masque l'acquisition de la KEK. Il ne la masque pas au sens d'une garde : il s'affiche
 * pendant qu'elle a lieu, et le contourner ne donne accès à rien — `ContenuPrincipal` attend
 * `StartupState.Ready` avant d'afficher quoi que ce soit. C'est vérifié par lecture du câblage, pas
 * par ces cas.
 */
@RunWith(AndroidJUnit4::class)
class EcranDePresentationTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val fermetures = AtomicInteger(0)

    private fun poser() {
        regle.setContent { NotesTechTheme { SplashScreen(onFinished = { fermetures.incrementAndGet() }) } }
    }

    private fun etiquetteComplete(): String {
        val activite = regle.activity
        return activite.getString(R.string.splash_semantics_label) + ". " +
            activite.getString(R.string.splash_skip_hint)
    }

    // ── L'idempotence des trois portes ───────────────────────────────────────────────────────────

    /**
     * 🔴 La touche ferme, et **une seule fois** même répétée.
     */
    @Test
    fun toucher_l_ecran_ferme_une_seule_fois() {
        regle.mainClock.autoAdvance = false
        poser()

        regle.onNodeWithContentDescription(etiquetteComplete()).performClick()
        regle.onNodeWithContentDescription(etiquetteComplete()).performClick()

        assertThat(fermetures.get()).isEqualTo(1)
    }

    /**
     * 🔴🔴 **Le cas que le KDoc décrit : un retour pressé pendant que l'échéance court.**
     *
     * La seconde fermeture s'appliquerait à l'écran d'accueil déjà affiché — l'utilisateur verrait
     * disparaître l'écran qu'il vient d'ouvrir, sans rien pour l'expliquer. Ici les **trois** portes
     * sont franchies, dans l'ordre le plus défavorable, et le compte doit rester à un.
     */
    @Test
    fun les_TROIS_portes_franchies_ne_ferment_qu_une_fois() {
        regle.mainClock.autoAdvance = false
        poser()

        regle.onNodeWithContentDescription(etiquetteComplete()).performClick()
        Espresso.pressBack()
        regle.mainClock.advanceTimeBy(FERMETURE_MILLIS + 500)
        regle.waitForIdle()

        assertThat(fermetures.get()).isEqualTo(1)
    }

    /**
     * Le retour seul ferme aussi. ⚠️ Sans ce cas, celui du dessus passerait avec un `BackHandler`
     * qui ne fait rien : la touche aurait déjà consommé l'unique fermeture.
     */
    @Test
    fun le_retour_seul_ferme() {
        regle.mainClock.autoAdvance = false
        poser()

        Espresso.pressBack()

        assertThat(fermetures.get()).isEqualTo(1)
    }

    // ── Le chronomètre de la signature ───────────────────────────────────────────────────────────

    /**
     * 🔴 **L'échéance vaut 5 500 ms, transcrits du Dart, et pas une milliseconde de moins.**
     *
     * L'horloge de composition est pilotée à la main : à 5 400 ms l'écran doit **encore** être là,
     * et c'est le témoin — sans lui, un portage qui fermerait immédiatement passerait la seconde
     * moitié du cas.
     */
    @Test
    fun l_echeance_de_fermeture_vaut_exactement_celle_du_Dart() {
        regle.mainClock.autoAdvance = false
        poser()

        regle.mainClock.advanceTimeBy(FERMETURE_MILLIS - 100)
        regle.waitForIdle()
        assertThat(fermetures.get()).isEqualTo(0)

        regle.mainClock.advanceTimeBy(200)
        regle.waitForIdle()
        assertThat(fermetures.get()).isEqualTo(1)
    }

    // ── Ce que l'écran dit ───────────────────────────────────────────────────────────────────────

    /**
     * ⚠️ **Un seul nœud, portant l'étiquette ET l'indice.**
     *
     * L'écran fusionne ses descendants exprès : un lecteur d'écran doit entendre à la fois ce qu'il
     * regarde et comment en sortir. Poser l'étiquette sans l'indice laisserait quelqu'un attendre
     * cinq secondes et demie sans savoir qu'un appui suffit.
     */
    @Test
    fun l_ecran_annonce_ce_qu_il_est_ET_comment_en_sortir() {
        regle.mainClock.autoAdvance = false
        poser()

        regle.onNodeWithContentDescription(etiquetteComplete()).assertIsDisplayed()
    }

    private companion object {
        /** `splash_screen.dart:85`. Transcrit, jamais lu depuis le portage. */
        const val FERMETURE_MILLIS = 5_500L
    }
}
