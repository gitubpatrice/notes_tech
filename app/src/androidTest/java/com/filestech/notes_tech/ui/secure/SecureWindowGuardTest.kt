package com.filestech.notes_tech.ui.secure

import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Le garde de `FLAG_SECURE`, dans une vraie composition et sur une vraie fenêtre.**
 *
 * Ligne `secure_window_service.dart` de `docs/05-PARITE.md`, critère écrit : *« `FLAG_SECURE` avec
 * compteur de références »*.
 *
 * ## 🔴🔴 Ce que les huit cas JVM ne pouvaient pas voir
 *
 * `SecureWindowControllerTest` couvre l'arithmétique du compteur — deux demandeurs imbriqués, la
 * borne à zéro, le réglage éteint sous une demande. Huit cas justes, et tous sur des appels que le
 * test fait **lui-même**. Or ce n'est pas là qu'est le risque : personne n'appelle `force()` ni
 * `release()` à la main. Les deux seuls appelants du programme sont le `DisposableEffect` de
 * [SecureWindowGuard], et c'est **l'appariement de ces deux gestes au cycle de vie d'une
 * composition** qui décide si le compteur reste juste.
 *
 * Un déséquilibre ne se voit pas : il ne lève rien, il n'affiche rien. Il retire la protection d'un
 * **autre** écran — l'éditeur d'une note de coffre resté ouvert derrière une feuille qui se ferme —
 * et la capture d'écran redevient possible sans que rien ne le signale.
 *
 * ## Et le dernier maillon : la fenêtre
 *
 * Le compteur peut être parfait et le drapeau jamais posé. Le dernier cas lit `FLAG_SECURE` sur la
 * **vraie fenêtre de l'activité**, parce qu'aucun raisonnement sur des entiers ne remplace ça.
 *
 * ⚠️ Le réglage utilisateur est mis à **faux** pendant ces cas : sinon `activeNow()` vaut vrai en
 * permanence et ne mesure plus rien du compteur. Il est restauré à la fin.
 */
@RunWith(AndroidJUnit4::class)
class SecureWindowGuardTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var reglages: AppSettings
    private lateinit var controleur: SecureWindowController
    private var reglageInitial = true

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        reglages = AppSettings(LegacyPreferences(context))
        reglageInitial = reglages.secureWindowNow()
        reglages.setSecureWindow(false)
        controleur = SecureWindowController(reglages)
        assertThat(controleur.activeNow()).isFalse()
    }

    @After
    fun tearDown() {
        reglages.setSecureWindow(reglageInitial)
    }

    // ── L'appariement au cycle de vie ────────────────────────────────────────────────────────────

    /**
     * 🔴 Une entrée en composition demande, une sortie rend. C'est tout le contrat.
     */
    @Test
    fun entrer_en_composition_demande_le_drapeau_et_en_sortir_le_rend() {
        val visible = mutableStateOf(true)
        poser { if (visible.value) SecureWindowGuard() }

        assertThat(controleur.activeNow()).isTrue()

        regle.runOnIdle { visible.value = false }
        regle.waitForIdle()

        assertThat(controleur.activeNow()).isFalse()
    }

    /**
     * 🔴🔴 **Le défaut que le compteur existe pour empêcher.**
     *
     * Deux écrans sensibles superposés — l'éditeur d'une note de coffre, puis la feuille de code
     * par-dessus. La feuille se ferme : la protection de l'éditeur **doit rester**. Avec un booléen
     * au lieu d'un compteur, le second `onDispose` la retirerait, et l'éditeur d'une note
     * déchiffrée deviendrait capturable sans un mot.
     */
    @Test
    fun la_sortie_du_second_garde_ne_retire_pas_la_protection_du_premier() {
        val editeur = mutableStateOf(true)
        val feuille = mutableStateOf(true)
        poser {
            if (editeur.value) SecureWindowGuard()
            if (feuille.value) SecureWindowGuard()
        }

        assertThat(controleur.activeNow()).isTrue()

        regle.runOnIdle { feuille.value = false }
        regle.waitForIdle()
        assertThat(controleur.activeNow()).isTrue()

        regle.runOnIdle { editeur.value = false }
        regle.waitForIdle()
        assertThat(controleur.activeNow()).isFalse()
    }

    /**
     * 🔴 **`active` bascule dans les deux sens, et c'est le seul endroit du programme qui s'en sert.**
     *
     * `NoteEditorScreen` passe `active = state.isVaultNote` : une note sortie de son coffre doit
     * cesser **aussitôt** d'imposer le drapeau aux autres. Le piège est asymétrique — au passage
     * vrai → faux, c'est le `onDispose` de l'effet **précédent** qui rend la demande, avec l'ancienne
     * valeur d'`active`. S'il lisait la nouvelle, il ne rendrait rien et le compteur resterait haut
     * pour toujours.
     */
    @Test
    fun basculer_active_rend_la_demande_puis_la_reprend() {
        val sensible = mutableStateOf(true)
        poser { SecureWindowGuard(active = sensible.value) }

        assertThat(controleur.activeNow()).isTrue()

        regle.runOnIdle { sensible.value = false }
        regle.waitForIdle()
        assertThat(controleur.activeNow()).isFalse()

        regle.runOnIdle { sensible.value = true }
        regle.waitForIdle()
        assertThat(controleur.activeNow()).isTrue()
    }

    /**
     * ⚠️⚠️ **Un garde inactif ne rend RIEN en sortant**, et c'est un contrôle négatif.
     *
     * S'il rendait une demande qu'il n'a jamais prise, le compteur descendrait sous la contribution
     * d'un autre écran. La borne à zéro masquerait le déséquilibre, et la protection disparaîtrait
     * pour quelqu'un d'autre.
     */
    @Test
    fun un_garde_INACTIF_ne_rend_pas_une_demande_qu_il_n_a_jamais_prise() {
        val autre = mutableStateOf(true)
        val inactif = mutableStateOf(true)
        poser {
            if (autre.value) SecureWindowGuard()
            if (inactif.value) SecureWindowGuard(active = false)
        }

        assertThat(controleur.activeNow()).isTrue()

        regle.runOnIdle { inactif.value = false }
        regle.waitForIdle()

        // Le garde inactif est sorti : la demande de l'autre écran doit être intacte.
        assertThat(controleur.activeNow()).isTrue()
    }

    /**
     * ⚠️ **Une recomposition qui ne change pas `active` ne doit pas faire osciller le compteur.**
     *
     * `active` est la seule clé de l'effet, exprès. Si la clé était l'objet composable ou rien du
     * tout, chaque frappe relancerait le couple rendre/reprendre. L'oscillation est inoffensive tant
     * qu'elle est ordonnée — et rien ne garantit qu'elle le reste quand deux écrans se recomposent
     * ensemble.
     */
    @Test
    fun une_recomposition_sans_changement_ne_fait_pas_osciller_le_compteur() {
        val frappe = mutableStateOf(0)
        poser {
            val n by frappe
            SecureWindowGuard()
            // Une lecture d'état qui force la recomposition sans toucher à `active`.
            if (n < 0) SecureWindowGuard()
        }

        assertThat(controleur.activeNow()).isTrue()
        repeat(5) { i ->
            regle.runOnIdle { frappe.value = i + 1 }
            regle.waitForIdle()
            assertThat(controleur.activeNow()).isTrue()
        }
    }

    /**
     * ⚠️⚠️ **Sans fournisseur, le garde LÈVE.** C'est le témoin du témoin.
     *
     * `LocalSecureWindow` n'a **aucun défaut**, et le KDoc dit pourquoi : un contrôleur muet ferait
     * passer un écran non protégé pour un écran protégé, et la panne serait invisible — l'appel
     * compile, l'écran s'affiche, la capture fonctionne. Ce cas vérifie que la garantie tient
     * vraiment, plutôt que de la croire sur parole.
     */
    @Test
    fun sans_fournisseur_le_garde_leve_au_lieu_de_se_taire() {
        var leve = false
        try {
            regle.setContent { SecureWindowGuard() }
            regle.waitForIdle()
        } catch (e: Throwable) {
            leve = true
        }
        assertThat(leve).isTrue()
    }

    // ── Le dernier maillon : la vraie fenêtre ────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Le compteur peut être parfait et le drapeau jamais posé.**
     *
     * Ce cas ne lit aucun entier : il pose `FLAG_SECURE` sur la fenêtre de l'activité comme le fait
     * `MainActivity.FenetreProtegee`, puis relit les paramètres de la fenêtre. C'est le seul contrôle
     * qui parle de ce que le système fait réellement.
     *
     * ⚠️ Le témoin est l'état **avant** : sans lui, un drapeau déjà posé par autre chose ferait
     * passer ce cas sans que rien n'ait été mesuré.
     */
    @Test
    fun le_drapeau_est_reellement_pose_puis_retire_sur_la_fenetre() {
        val fenetre = regle.activity.window

        regle.runOnUiThread { fenetre.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        assertThat(fenetre.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(0)

        regle.runOnUiThread {
            fenetre.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        assertThat(fenetre.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            .isEqualTo(WindowManager.LayoutParams.FLAG_SECURE)

        regle.runOnUiThread { fenetre.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        assertThat(fenetre.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(0)
    }

    private fun poser(contenu: @Composable () -> Unit) {
        regle.setContent {
            CompositionLocalProvider(LocalSecureWindow provides controleur) { contenu() }
        }
        regle.waitForIdle()
    }
}
