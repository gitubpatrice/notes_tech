package com.filestech.notes_tech.ui.voice

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.voice.SttModel
import com.filestech.notes_tech.domain.voice.SttModelCatalogue
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Ce que l'écran d'installation du modèle DIT dans chacun de ses états.**
 *
 * Ligne `voice_setup_screen.dart` de `docs/05-PARITE.md`. 462 lignes de production, **aucun test**.
 *
 * ## 🔴🔴 Pourquoi ces états n'étaient pas atteignables
 *
 * Six d'entre eux ne s'obtenaient **que** par le magasin réel : la vérification au démarrage, la
 * progression d'un import, les **quatre** causes d'échec, et le dialogue de retrait. Les atteindre
 * par `VoiceSetupRoute` demanderait de fabriquer un fichier de 57 Mo — pour en voir **un seul**.
 *
 * D'où le découpage sans état, le même que pour les feuilles de coffre (§86) et pour la même
 * raison : *un état qu'aucun test ne peut atteindre est un état que personne n'a jamais regardé.*
 *
 * ## Ce qui se joue sur cet écran
 *
 * La cause `EMPREINTE` est la plus importante des quatre : elle dit que le fichier de 57 Mo que
 * quelqu'un vient d'installer **n'est pas celui qu'il croit**. Le texte le dit, et dit aussi qu'il a
 * été supprimé — deux affirmations qu'un dialogue muet ou générique effacerait.
 */
@RunWith(AndroidJUnit4::class)
class EcranDInstallationVocaleTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private var retraitDemande: SttModel? = null
    private var retraitConfirme: SttModel? = null
    private var importDemande: SttModel? = null
    private var erreurFermee = 0
    private var importAnnule = 0

    private val etatCourant = mutableStateOf(VoiceSetupState())
    private val retraitEnCours = mutableStateOf<String?>(null)
    private var pose = false

    /**
     * Pose le contenu **une fois**, puis ne fait plus que changer d'état.
     *
     * ⚠️ `setContent` deux fois lève. Plusieurs cas d'ici parcourent quatre états d'affilée : sans
     * ce détour, ils ne mesureraient que le premier.
     */
    private fun poser(etat: VoiceSetupState, aRetirer: String? = null) {
        if (pose) {
            regle.runOnIdle {
                etatCourant.value = etat
                retraitEnCours.value = aRetirer
            }
            regle.waitForIdle()
            return
        }
        etatCourant.value = etat
        retraitEnCours.value = aRetirer
        pose = true
        regle.setContent {
            NotesTechTheme {
                val etatRendu by etatCourant
                val aRetirerRendu by retraitEnCours
                EcranDInstallationVocale(
                    etat = etatRendu,
                    snackbars = remember { SnackbarHostState() },
                    aRetirer = aRetirerRendu,
                    onBack = {},
                    onCopierLeLien = {},
                    onImporter = { importDemande = it },
                    onDemanderLeRetrait = { retraitDemande = it },
                    onConfirmerLeRetrait = { retraitConfirme = it },
                    onAnnulerLeRetrait = { retraitEnCours.value = null },
                    onAnnulerLImport = { importAnnule++ },
                    onFermerLErreur = { erreurFermee++ },
                )
            }
        }
        regle.waitForIdle()
    }

    private fun texte(id: Int) = regle.activity.getString(id)

    // ── Les quatre causes d'échec ────────────────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Chaque cause d'échec dit ce qui s'est passé, et aucune ne dit celle d'à côté.**
     *
     * Le `when` du dialogue est exhaustif sur l'énumération, donc ajouter une cause sans texte casse
     * la compilation. Ce que le compilateur ne vérifie pas, c'est que les quatre textes soient
     * **branchés dans le bon ordre** — une paire intervertie enverrait quelqu'un libérer de la place
     * alors que son fichier est corrompu.
     *
     * ⚠️ Le cas parcourt les quatre et exige, pour chacune, la présence de son texte **et** l'absence
     * des trois autres. C'est ce second volet qui attrape l'interversion.
     */
    @Test
    fun chaque_cause_d_echec_affiche_SON_texte_et_pas_celui_des_autres() {
        val attendus = mapOf(
            ErreurImport.FICHIER_INADAPTE to R.string.voice_setup_error_source_invalid,
            ErreurImport.PLACE_INSUFFISANTE to R.string.voice_setup_error_storage_full,
            ErreurImport.EMPREINTE to R.string.voice_setup_error_checksum,
            ErreurImport.TECHNIQUE to R.string.voice_setup_error_import_failed,
        )
        // Témoin : la table couvre bien toutes les causes. Une cause ajoutée sans texte décidé doit
        // faire tomber ce cas, pas passer inaperçue.
        assertThat(attendus.keys).containsExactlyElementsIn(ErreurImport.entries)

        for ((cause, ressource) in attendus) {
            poser(VoiceSetupState(erreur = cause))

            regle.onNodeWithText(texte(R.string.voice_setup_import_error_title)).assertIsDisplayed()
            regle.onNodeWithText(texte(ressource)).assertIsDisplayed()
            attendus.values.filterNot { it == ressource }.forEach { autre ->
                regle.onNodeWithText(texte(autre)).assertDoesNotExist()
            }
        }
    }

    /**
     * 🔴 **L'empreinte : le message dit que le fichier a été supprimé, et il faut que ce soit lu.**
     *
     * C'est le seul des quatre où l'application a **agi** — elle a effacé les 57 Mo. Quelqu'un qui
     * ne le lit pas cherchera un fichier qui n'est plus là.
     */
    @Test
    fun l_echec_d_empreinte_dit_que_le_fichier_a_ete_supprime() {
        poser(VoiceSetupState(erreur = ErreurImport.EMPREINTE))

        val message = texte(R.string.voice_setup_error_checksum)
        assertThat(message).contains("supprimé")
        regle.onNodeWithText(message).assertIsDisplayed()
    }

    /** Fermer le dialogue rend la main une fois — pas zéro, pas deux. */
    @Test
    fun fermer_le_dialogue_d_erreur_previent_l_appelant() {
        poser(VoiceSetupState(erreur = ErreurImport.TECHNIQUE))

        regle.onNodeWithText(texte(R.string.common_ok)).performClick()

        assertThat(erreurFermee).isEqualTo(1)
    }

    // ── Pendant qu'un import tourne ──────────────────────────────────────────────────────────────

    /**
     * 🔴 **Un seul import à la fois, et les autres cartes se DÉSARMENT.**
     *
     * Le commentaire du code dit pourquoi : *« un bouton qui ne répond pas se réappuie »*. Accepter
     * un second geste que le magasin ferait attendre en silence produirait exactement ça.
     *
     * ⚠️ Le témoin est l'état au repos : sans lui, un écran dont les boutons sont toujours inertes
     * passerait la moitié du cas.
     */
    @Test
    fun pendant_un_import_les_autres_cartes_sont_desarmees() {
        val modele = SttModelCatalogue.tous.first()

        // ⚠️ Le catalogue compte **deux** modèles, donc deux boutons du même libellé. `onAllNodes`
        // + `assertAll` dit « toutes les cartes », ce qui est exactement la promesse à vérifier ;
        // viser un nœud unique n'aurait mesuré qu'une carte sur deux.
        poser(VoiceSetupState())
        regle.onAllNodesWithText(texte(R.string.voice_setup_select_file)).assertAll(isEnabled())

        poser(VoiceSetupState(progression = 42))
        regle.onAllNodesWithText(texte(R.string.voice_setup_select_file)).assertAll(isNotEnabled())
        assertThat(modele.id).isNotEmpty()
    }

    /** La progression s'affiche, et son annulation remonte. */
    @Test
    fun la_progression_s_affiche_et_s_annule() {
        poser(VoiceSetupState(progression = 42))

        // ⚠⚠ **`performScrollTo` avant de toucher, et c'est une mesure.** Le bloc de progression est
        // posé sous les deux cartes de modèle : sur le S9, il tombe hors de la fenêtre. Sans défilement,
        // `performClick` ne lève pas — il touche des coordonnées qui ne sont plus à l'écran, et le cas
        // échoue sur un compteur à zéro, ce qui ressemble à un rappel non câblé.
        regle.onNodeWithText(regle.activity.getString(R.string.voice_setup_copying_progress, 42))
            .performScrollTo()
        regle.onNodeWithText(texte(R.string.common_cancel)).performScrollTo().performClick()

        assertThat(importAnnule).isEqualTo(1)
    }

    /** ⚠️ La vérification au démarrage a son propre message, distinct de la progression. */
    @Test
    fun la_verification_au_demarrage_le_dit() {
        poser(VoiceSetupState(verificationEnCours = true))

        regle.onNodeWithText(texte(R.string.voice_setup_verifying)).assertIsDisplayed()
    }

    // ── Le retrait ───────────────────────────────────────────────────────────────────────────────

    /**
     * 🔴 **Le dialogue de retrait dit ce qu'on perd, et ce qu'on ne perd pas.**
     *
     * « Vous devrez le retélécharger » et « vos notes ne sont pas concernées » : la seconde moitié
     * est celle qui évite qu'on renonce à un geste inoffensif par peur du premier.
     */
    @Test
    fun le_dialogue_de_retrait_dit_ce_qu_on_perd_et_ce_qu_on_garde() {
        val modele = SttModelCatalogue.tous.first()
        poser(VoiceSetupState(installe = modele.id), aRetirer = modele.id)

        regle.onNodeWithText(texte(R.string.voice_setup_remove_confirm_title)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.voice_setup_remove_confirm_body)).assertIsDisplayed()
    }

    /**
     * ⚠️ Le contrôle négatif : **sans identifiant, aucun dialogue**.
     *
     * `aRetirer` est un identifiant hissé depuis la Route, où il vit en `rememberSaveable`. Un écran
     * qui afficherait le dialogue sur la seule foi de `installe` le montrerait à l'ouverture.
     */
    @Test
    fun sans_identifiant_a_retirer_aucun_dialogue() {
        poser(VoiceSetupState(installe = SttModelCatalogue.tous.first().id), aRetirer = null)

        regle.onNodeWithText(texte(R.string.voice_setup_remove_confirm_title)).assertDoesNotExist()
    }

    /**
     * 🔴 **Les deux temps du retrait remontent le MODÈLE, pas un booléen.**
     *
     * Le catalogue en compte plusieurs : un rappel sans identité désinstallerait celui que l'écran
     * affiche en premier, quel que soit le bouton touché.
     */
    @Test
    fun demander_puis_confirmer_le_retrait_remonte_le_bon_modele() {
        val modele = SttModelCatalogue.tous.first()
        poser(VoiceSetupState(installe = modele.id))

        regle.onNodeWithText(texte(R.string.voice_setup_remove)).performScrollTo().performClick()
        assertThat(retraitDemande).isEqualTo(modele)

        // ⚠⚠ Le bouton de confirmation **reprend le libellé de la carte** : deux nœuds portent alors le
        // même nom à l'écran. On vise celui du dialogue par `isDialog()` — un ascendant TEXTUEL ne
        // marche pas, le titre et les boutons d'un `AlertDialog` sont frères, pas parent et enfant.
        poser(VoiceSetupState(installe = modele.id), aRetirer = modele.id)
        regle.onNode(
            hasText(texte(R.string.voice_setup_remove)) and hasAnyAncestor(isDialog()),
        ).performClick()

        assertThat(retraitConfirme).isEqualTo(modele)
    }

    // ── Ce que l'écran dit de lui-même ───────────────────────────────────────────────────────────

    /** ⚠️ Le balayage maison, sur l'état le plus chargé. */
    @Test
    fun aucun_element_actionnable_n_est_sans_nom() {
        poser(VoiceSetupState(installe = SttModelCatalogue.tous.first().id))

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }
}
