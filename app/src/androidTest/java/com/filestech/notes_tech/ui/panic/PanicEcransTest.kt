package com.filestech.notes_tech.ui.panic

import android.content.Context
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.security.panic.PanicOutcome
import com.filestech.notes_tech.security.panic.PanicReport
import com.filestech.notes_tech.security.panic.PanicStep
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.secure.LocalSecureWindow
import com.filestech.notes_tech.ui.secure.SecureWindowController
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * **Ce que les deux écrans du mode panique DISENT.**
 *
 * Ligne `panic_service.dart` de `docs/05-PARITE.md`, critère écrit : *« ordre des étapes ; un rapport
 * ne doit jamais mentir sur un effacement »*.
 *
 * ## 🔴🔴 Pourquoi ce fichier n'existait pas, et ce que ça a coûté
 *
 * `PanicReportTest` couvre l'ordre des étapes et ce que [PanicReport] conclut d'une liste d'issues.
 * Quinze cas, tous justes. Il ne rendait **aucun écran**, et c'est exactement là qu'étaient les
 * quatre défauts trouvés le 2026-08-19 :
 *
 * 1. Le dialogue de confirmation **taisait** la destruction du modèle de dictée. La ligne était
 *    masquée au motif que « la dictée arrive en phase 7 » — vrai le jour où c'était écrit, faux
 *    depuis le 2026-08-16. Sur un écran de **consentement**.
 * 2. L'écran de fin taisait le même effacement, alors qu'il avait bien eu lieu.
 * 3. Le message « du clair peut subsister » ne nommait que les archives d'export, alors que le
 *    prédicat qui le déclenche couvre **trois** sources. Le cas « seul le presse-papiers a résisté »
 *    envoyait quelqu'un fouiller des fichiers absents, pour en conclure qu'il est tiré d'affaire.
 * 4. Ce même message affichait un **compte d'étapes en échec** alors que ce résidu-là se **mesure**
 *    à la fin de la séquence. `PanicReportTest.clairRestantEstSignale` construit précisément l'état
 *    où il vaut vrai avec zéro échec : l'écran y annonçait « **0 étape(s)** de nettoyage ont
 *    échoué » juste avant d'avertir qu'il reste du clair.
 *
 * > **Les quatre étaient sous une suite verte.** Un test qui prouve la *branche* ne dit rien de la
 * > *phrase*, et sur cet écran-ci c'est la phrase qui est le produit : quelqu'un décide, en la
 * > lisant, s'il peut se séparer de son appareil.
 *
 * ⚠️ Ces cas ne couvrent pas l'exécution de la séquence — fichiers, Keystore, base. Elle se mesure
 * sur appareil ; cf. le KDoc de `PanicReportTest`.
 */
@RunWith(AndroidJUnit4::class)
class PanicEcransTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val contexte: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * ⚠️ Des ressources **de langue explicite**, jamais celles de l'appareil : le S9 peut être dans
     * l'une ou l'autre, et un inventaire vérifié dans une seule langue laisse l'autre dériver. C'est
     * précisément ainsi que le défaut 3 est né — la phrase anglaise et la française disaient toutes
     * deux « export » et rien d'autre.
     */
    private fun ressources(langue: Locale): Resources = contexte
        .createConfigurationContext(
            android.content.res.Configuration(contexte.resources.configuration).apply {
                setLocale(langue)
            },
        )
        .resources

    private fun texte(id: Int): String = regle.activity.getString(id)

    private val dialogue = mutableStateOf(false)
    private val enCours = mutableStateOf(false)
    private val bilan = mutableStateOf<PanicReport?>(null)
    private var pose = false

    /**
     * Pose le contenu **une fois**, puis ne fait plus que changer d'état.
     *
     * ⚠️⚠️ Le fournisseur de `LocalSecureWindow` est obligatoire : les deux composants appellent
     * `SecureWindowGuard`, qui **lève** sans lui. Le garde-fou est délibéré — un contrôleur muet
     * ferait passer un écran non protégé pour un écran protégé.
     */
    private fun poser(confirmation: Boolean = false, running: Boolean = false, report: PanicReport? = null) {
        if (pose) {
            regle.runOnIdle {
                dialogue.value = confirmation
                enCours.value = running
                bilan.value = report
            }
            regle.waitForIdle()
            return
        }
        dialogue.value = confirmation
        enCours.value = running
        bilan.value = report
        pose = true
        regle.setContent {
            CompositionLocalProvider(
                LocalSecureWindow provides SecureWindowController(AppSettings(LegacyPreferences(regle.activity))),
            ) {
                NotesTechTheme {
                    val confirme by dialogue
                    val tourne by enCours
                    val rapport by bilan
                    if (confirme) {
                        PanicConfirmDialog(onDismiss = {}, onConfirmed = {})
                    } else {
                        PanicOverlay(running = tourne, report = rapport, onClose = {})
                    }
                }
            }
        }
        regle.waitForIdle()
    }

    private fun rapport(vararg echecs: PanicStep, clairSurLeDisque: Boolean = false) = PanicReport(
        PanicStep.entries.map { PanicOutcome(it, failure = if (it in echecs) "Simule" else null) },
        clairSurLeDisque = clairSurLeDisque,
    )

    // ── Ce que l'écran ANNONCE avant de détruire ────────────────────────────

    /**
     * 🔴 **Le défaut 1.** Le deuxième item — le modèle de dictée — n'était pas affiché.
     *
     * `PanicStep.VOICE_MODEL_WIPE` s'exécute depuis le 2026-08-16 et efface `files/stt/`, le seul
     * fichier que l'utilisateur ait mis plusieurs minutes à installer. L'écran où il donne son
     * accord n'en disait rien.
     *
     * ⚠️ Ce cas échouerait aussi si l'on retirait la ligne à l'avenir *sans* retirer l'étape : c'est
     * la même règle, prise dans l'autre sens.
     */
    @Test
    fun le_dialogue_de_confirmation_annonce_les_TROIS_items() {
        poser(confirmation = true)

        for (item in listOf(
            R.string.panic_confirm_item_1,
            R.string.panic_confirm_item_2,
            R.string.panic_confirm_item_3,
        )) {
            regle.onNodeWithText(texte(item)).assertIsDisplayed()
        }
    }

    /** 🔴 **Le défaut 2.** Le bilan omettait un effacement qui avait bien eu lieu. */
    @Test
    fun l_ecran_de_fin_liste_les_QUATRE_puces() {
        poser(report = rapport())

        for (puce in listOf(
            R.string.panic_complete_bullet_1,
            R.string.panic_complete_bullet_2,
            R.string.panic_complete_bullet_3,
            R.string.panic_complete_bullet_4,
        )) {
            regle.onNodeWithText(texte(puce)).assertIsDisplayed()
        }
    }

    // ── Ce que l'écran annonce APRÈS, et qui décide d'un geste réel ─────────

    /**
     * 🔴🔴 **Le défaut 4, et il se contredisait dans sa propre phrase.**
     *
     * Toutes les étapes ont abouti **et** du clair est resté sur le disque : c'est un état atteignable
     * — la mesure regarde le disque, elle ne déduit rien des étapes — et `PanicReportTest`
     * le construit déjà. L'écran y affichait « 0 étape(s) de nettoyage ont échoué » avant d'avertir
     * qu'il reste du lisible.
     *
     * ⚠️ Le cas compare au texte **brut** de la ressource. Si un `%1$d` y revenait, `getString` sans
     * argument le rendrait littéralement et la comparaison échouerait — c'est ce qui rend ce cas
     * durable plutôt que ponctuel.
     */
    @Test
    fun le_message_de_clair_n_annonce_AUCUN_compte_d_etapes() {
        val bilanMesure = rapport(clairSurLeDisque = true)
        assertThat(bilanMesure.failedSteps).isEmpty()
        assertThat(bilanMesure.clairPeutSubsister).isTrue()

        poser(report = bilanMesure)

        regle.onNodeWithText(texte(R.string.panic_incomplete_plaintext)).assertIsDisplayed()
    }

    /**
     * 🔴 **Le défaut 3, mesuré dans les DEUX langues.**
     *
     * `clairPeutSubsister` couvre trois sources — l'archive d'export, l'enregistrement de dictée, et
     * le presse-papiers. La phrase n'en nommait qu'une. Le cas « seul le presse-papiers a résisté »
     * envoyait donc quelqu'un inspecter des fichiers qu'il ne trouverait pas, pour en conclure qu'il
     * est tiré d'affaire pendant qu'une note reste lisible par toute application au premier plan.
     *
     * ⚠️⚠️ Le KDoc de ce prédicat raconte lui-même que son inventaire **s'est trompé deux fois par
     * omission**, et il a été corrigé les deux fois. La phrase affichée, jamais. *Un inventaire
     * corrigé dans le code et pas dans le texte n'est corrigé nulle part, puisque c'est le texte
     * qu'on lit.* Ce cas est la seule chose qui le tienne.
     */
    @Test
    fun l_inventaire_du_clair_nomme_les_TROIS_sources() {
        val attendus = mapOf(
            Locale.FRENCH to listOf("export", "dictée", "presse-papiers"),
            Locale.ENGLISH to listOf("export", "dictation", "clipboard"),
        )

        for ((langue, sources) in attendus) {
            val phrase = ressources(langue).getString(R.string.panic_incomplete_plaintext)
            for (source in sources) {
                assertThat(phrase.lowercase(langue)).contains(source.lowercase(langue))
            }
        }
    }

    /**
     * 🔴 Le seul message que l'écran ne doit jamais adoucir : la clé a survécu, donc les notes
     * restent déchiffrables, et quelqu'un est peut-être sur le point de se séparer de son appareil.
     */
    @Test
    fun la_cle_survivante_porte_son_propre_titre() {
        poser(report = rapport(PanicStep.KEK_DESTROY))

        regle.onNodeWithText(texte(R.string.panic_key_survived_title)).assertIsDisplayed()
        // ⚠️ Le pied de page promet un prochain lancement « sur une base vierge ». Le promettre à
        // côté d'un avertissement disant que les notes restent lisibles ferait douter de celui des
        // deux qui compte.
        regle.onNodeWithText(texte(R.string.panic_complete_footer)).assertDoesNotExist()
    }

    /**
     * ⚠️ L'issue doit être **annoncée** au lecteur d'écran : quelqu'un qui n'a pas les yeux sur
     * l'appareil doit savoir ce qui s'est passé, au moment précis où cette information compte le
     * plus. Le titre porte l'annonce, donc il doit porter la vérité.
     */
    @Test
    fun le_titre_de_l_issue_est_annonce_de_facon_assertive() {
        for (report in listOf(rapport(), rapport(PanicStep.KEK_DESTROY))) {
            poser(report = report)

            val regions = regle
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
                .fetchSemanticsNodes()
                .map { noeud ->
                    val texte = noeud.config.getOrNull(SemanticsProperties.Text).orEmpty()
                        .joinToString(" ") { it.text }
                    texte to noeud.config.getOrNull(SemanticsProperties.LiveRegion)!!
                }

            assertThat(regions).hasSize(1)
            assertThat(regions.single().second).isEqualTo(LiveRegionMode.Assertive)
            assertThat(regions.single().first).isNotEmpty()
        }
    }

    // ── Les balayages ───────────────────────────────────────────────────────

    /**
     * ⚠️ Le décompte **d'abord** : un balayage qui rend une liste vide parce qu'il n'a rien trouvé à
     * balayer est indiscernable d'un écran sain.
     */
    @Test
    fun les_deux_ecrans_n_ont_aucun_actionnable_sans_nom() {
        for (etat in etats) {
            etat()

            assertThat(regle.onAllNodes(hasClickAction()).fetchSemanticsNodes().size).isAtLeast(1)
            assertThat(regle.actionnablesSansNom()).isEmpty()
        }
    }

    @Test
    fun les_deux_ecrans_ne_perdent_aucune_action_a_la_fusion() {
        for (etat in etats) {
            etat()

            assertThat(regle.actionsPerduesALaFusion()).isEmpty()
        }
    }

    private val etats: List<() -> Unit> = listOf(
        { poser(confirmation = true) },
        { poser(report = rapport()) },
        { poser(report = rapport(PanicStep.KEK_DESTROY)) },
        { poser(report = rapport(clairSurLeDisque = true)) },
        { poser(report = rapport(PanicStep.CACHE_PURGE)) },
    )
}
