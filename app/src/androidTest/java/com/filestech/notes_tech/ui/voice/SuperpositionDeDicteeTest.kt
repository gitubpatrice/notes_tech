package com.filestech.notes_tech.ui.voice

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.CHAMP_DE_SAISIE
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Ce que la superposition de dictée annonce, et ce qu'elle laisse faire.**
 *
 * Ligne `voice_recording_overlay.dart` de `docs/05-PARITE.md`. C'est l'écran le plus court du
 * portage et celui où deux défauts se cachaient le mieux, parce qu'il **paraissait** complet : trois
 * états nommés, un `when` exhaustif, un témoin de niveau sonore, et des commentaires abondants.
 *
 * ## Les quatre questions de la phase 8, appliquées ici
 *
 * 1. *Que reçoit un lecteur d'écran ?* → les trois balayages, le décompte des actionnables **et**
 *    les régions actives état par état. Ce sont ces dernières qui ont trouvé le défaut : les
 *    balayages sont propres depuis le début, aucun nœud n'est anonyme, et pourtant **rien** n'est
 *    annoncé au passage d'une étape à l'autre.
 * 2. *Quels états ne sait-on pas atteindre à la main ?* → l'initialisation dure une fraction de
 *    seconde et la transcription dépend d'un modèle de 57 Mo. La superposition était **déjà** sans
 *    état — elle ne reçoit qu'une étape et un niveau — donc rien à découper ici.
 * 3. *Combien de tests ont été ignorés ?* → compté des deux côtés, `-3` / `-4` à l'instrumentation
 *    et somme des `tests=` en JVM. `04-PIEGES.md` §72 et §82.
 * 4. *Que voulait le publié ?* → il pose une `liveRegion` sur **chacun** de ses cinq corps, et deux
 *    boutons pendant l'enregistrement là où le portage n'en avait qu'un.
 *
 * ## 🔴🔴 Ce que la mesure a trouvé
 *
 * - `régionsActives=[]` dans les **trois** états actifs. Un `AlertDialog` est annoncé à son
 *   ouverture ; le texte qui change ensuite dans ses emplacements ne l'est pas. Un utilisateur non
 *   voyant n'entendait jamais le « Parlez » qui dit que le micro est ouvert.
 * - **Une seule action pendant l'enregistrement.** « Annuler » n'existait que dans les deux autres
 *   états : `abandonner` était écrit, traversait jusqu'au moteur natif, et **aucun bouton ne
 *   l'appelait** pendant qu'on parlait.
 *
 * ⚠️ Les deux se mesurent sur un écran dont **les trois balayages étaient déjà verts**. *Un écran
 * sans nœud anonyme peut être un écran qui ne dit rien.*
 */
@RunWith(AndroidJUnit4::class)
class SuperpositionDeDicteeTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val etapeCourante = mutableStateOf(EtapeDeDictee.INACTIVE)
    private val secondesCourantes = mutableStateOf(0)
    private var pose = false
    private val arrets = mutableListOf<Unit>()
    private val abandons = mutableListOf<Unit>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    /** Pose la superposition **une fois**, puis ne fait plus que changer d'étape. */
    private fun poser(etape: EtapeDeDictee, secondes: Int = SECONDES_TEMOIN) {
        if (pose) {
            regle.runOnIdle {
                etapeCourante.value = etape
                secondesCourantes.value = secondes
            }
            regle.waitForIdle()
            return
        }
        etapeCourante.value = etape
        secondesCourantes.value = secondes
        pose = true
        regle.setContent {
            NotesTechTheme {
                val courante by etapeCourante
                val ecoulees by secondesCourantes
                SuperpositionDeDictee(
                    etape = courante,
                    niveau = NIVEAU_TEMOIN,
                    secondes = ecoulees,
                    onArreter = { arrets += Unit },
                    onAbandonner = { abandons += Unit },
                )
            }
        }
        regle.waitForIdle()
    }

    /**
     * Les textes portés par les nœuds qui **annoncent**.
     *
     * ⚠️ Arbre **fusionné** : la consigne pose sa région sur un conteneur sans texte propre, et
     * l'arbre non fusionné la rendrait vide.
     *
     * ⚠️⚠️ Une **liste** et non une table : deux régions au même texte s'effondreraient en une seule
     * clé, et un troisième nœud annonceur passerait inaperçu.
     */
    private fun regionsActives(): List<Pair<String, LiveRegionMode>> = regle
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        .fetchSemanticsNodes()
        .map { noeud ->
            val texte = noeud.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .joinToString(" ") { it.text }
            texte to noeud.config.getOrNull(SemanticsProperties.LiveRegion)!!
        }

    private fun actionnables(): Int = regle.onAllNodes(hasClickAction()).fetchSemanticsNodes().size

    // -----------------------------------------------------------------------
    // Les trois balayages, et le décompte qui les rend lisibles
    // -----------------------------------------------------------------------

    /**
     * ⚠️ Le décompte **d'abord**, comme le veut §78 : un balayage qui rend une liste vide parce qu'il
     * n'a rien trouvé à balayer est indiscernable d'un écran sain.
     */
    @Test
    fun les_trois_etats_actifs_n_ont_aucun_actionnable_sans_nom() {
        for (etape in ETATS_ACTIFS) {
            poser(etape)

            assertThat(actionnables()).isAtLeast(1)
            assertThat(regle.actionnablesSansNom()).isEmpty()
        }
    }

    @Test
    fun les_trois_etats_actifs_ne_perdent_aucune_action_a_la_fusion() {
        for (etape in ETATS_ACTIFS) {
            poser(etape)

            assertThat(regle.actionsPerduesALaFusion()).isEmpty()
        }
    }

    /**
     * **Fil-piège.** Cette superposition ne porte aucun champ de saisie, et l'affirmer est
     * vérifiable là où appeler le balayage des champs serait une assertion creuse.
     */
    @Test
    fun la_superposition_ne_porte_aucun_champ_de_saisie() {
        for (etape in ETATS_ACTIFS) {
            poser(etape)

            assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).isEmpty()
        }
    }

    // -----------------------------------------------------------------------
    // 🔴🔴 Les régions actives — le défaut principal
    // -----------------------------------------------------------------------

    /**
     * 🔴🔴 **Le titre ET la consigne annoncent, dans les trois états.**
     *
     * ⚠️ **Deux régions et non une** : l'une sans l'autre ment. Le titre seul dirait « Dictée en
     * cours » sans dire de parler ; la consigne seule dirait « Veuillez patienter… » aussi bien
     * pendant l'initialisation que pendant la transcription, sans jamais nommer l'étape.
     *
     * ⚠️ `Polite` et non `Assertive` : les deux annonces se suivent au lieu de se couper. En
     * `Assertive`, la seconde interromprait la première et le titre serait perdu.
     */
    @Test
    fun chaque_etat_actif_annonce_son_titre_ET_sa_consigne() {
        val attendus = mapOf(
            EtapeDeDictee.INITIALISATION to
                (R.string.voice_mic_initializing to R.string.voice_transcribing_hint),
            EtapeDeDictee.ENREGISTREMENT to
                (R.string.voice_recording_title to R.string.voice_recording_hint),
            EtapeDeDictee.TRANSCRIPTION to
                (R.string.voice_transcribing to R.string.voice_transcribing_hint),
        )

        for ((etape, textes) in attendus) {
            poser(etape)
            val regions = regionsActives()

            assertThat(regions.map { it.first })
                .containsExactly(texte(textes.first), texte(textes.second))
            assertThat(regions.map { it.second }.toSet()).containsExactly(LiveRegionMode.Polite)
        }
    }

    /**
     * **Le témoin de l'instrument.** Sans lui, un filtre qui lirait la mauvaise propriété rendrait
     * une carte vide à l'état inactif comme ailleurs, et le test précédent passerait pour une mesure.
     */
    @Test
    fun l_etape_inactive_n_affiche_rien_du_tout() {
        poser(EtapeDeDictee.INACTIVE)

        assertThat(actionnables()).isEqualTo(0)
        assertThat(regionsActives()).isEmpty()
    }

    // -----------------------------------------------------------------------
    // 🔴🔴 La sortie pendant l'enregistrement
    // -----------------------------------------------------------------------

    /**
     * 🔴🔴 **Il faut pouvoir renoncer pendant qu'on parle.**
     *
     * « Arrêter » transcrit et insère ; « Annuler » jette. Ce sont deux gestes, et seul le premier
     * existait : un appui involontaire sur le micro, ou une phrase dite à voix haute qu'on ne
     * voulait pas garder, et il ne restait qu'à laisser la dictée aller au bout puis effacer le
     * texte inséré.
     */
    @Test
    fun pendant_l_enregistrement_les_DEUX_gestes_sont_offerts() {
        poser(EtapeDeDictee.ENREGISTREMENT)

        assertThat(actionnables()).isEqualTo(2)
        regle.onNodeWithText(texte(R.string.voice_recording_stop)).assertExists()
        regle.onNodeWithText(texte(R.string.common_cancel)).assertExists()
    }

    @Test
    fun pendant_l_enregistrement_annuler_abandonne_sans_transcrire() {
        poser(EtapeDeDictee.ENREGISTREMENT)

        regle.onNodeWithText(texte(R.string.common_cancel)).performClick()

        assertThat(abandons).hasSize(1)
        assertThat(arrets).isEmpty()
    }

    @Test
    fun pendant_l_enregistrement_arreter_transcrit_sans_abandonner() {
        poser(EtapeDeDictee.ENREGISTREMENT)

        regle.onNodeWithText(texte(R.string.voice_recording_stop)).performClick()

        assertThat(arrets).hasSize(1)
        assertThat(abandons).isEmpty()
    }

    /**
     * ⚠️ Hors enregistrement, **une seule** action, et c'est l'abandon : il n'y a rien à arrêter en
     * douceur — le micro n'est pas encore ouvert, ou le calcul est déjà lancé. Un libellé
     * « Arrêter » y promettrait une action que le code ne peut pas rendre.
     */
    @Test
    fun hors_enregistrement_la_seule_action_abandonne() {
        for (etape in listOf(EtapeDeDictee.INITIALISATION, EtapeDeDictee.TRANSCRIPTION)) {
            poser(etape)
            abandons.clear()

            assertThat(actionnables()).isEqualTo(1)
            regle.onNodeWithText(texte(R.string.voice_recording_stop)).assertDoesNotExist()
            regle.onNodeWithText(texte(R.string.common_cancel)).performClick()

            assertThat(abandons).hasSize(1)
        }
    }

    /**
     * ⚠️ **Deux boutons de même hauteur.** C'est la seule forme qui distingue un bouton entier d'un
     * bouton rogné par la contrainte de hauteur de l'emplacement d'actions — le défaut déjà mesuré
     * sur le dialogue de suppression de dossier, où une troisième action écrasait les deux autres.
     */
    @Test
    fun les_deux_boutons_de_l_enregistrement_ont_la_meme_hauteur() {
        poser(EtapeDeDictee.ENREGISTREMENT)

        val annuler = regle.onNodeWithText(texte(R.string.common_cancel)).fetchSemanticsNode()
        val arreter = regle.onNodeWithText(texte(R.string.voice_recording_stop)).fetchSemanticsNode()

        assertThat(annuler.boundsInRoot.height).isEqualTo(arreter.boundsInRoot.height)
        assertThat(annuler.boundsInRoot.height).isGreaterThan(0f)
    }

    // -----------------------------------------------------------------------
    // 🔴 Le compteur, et la borne qu'il nomme — §96
    // -----------------------------------------------------------------------

    /**
     * 🔴🔴 **Le compteur nomme la borne, il ne montre pas seulement le temps qui passe.**
     *
     * La capture s'arrête à deux minutes là où l'application publiée n'a aucune borne. Un temps
     * écoulé seul ne préviendrait de rien — on ne se méfie pas d'un chiffre qui monte. Écrit
     * « 1:37 / 2:00 », il dit qu'il y a une fin avant qu'elle n'arrive.
     */
    @Test
    fun le_compteur_affiche_le_temps_ECOULE_et_la_BORNE() {
        poser(EtapeDeDictee.ENREGISTREMENT, secondes = 97)

        regle.onNodeWithText("1:37 / 2:00").assertExists()
    }

    /**
     * ⚠️⚠️ **Le compteur est masqué aux lecteurs d'écran, et c'est mesuré.** La colonne qui le porte
     * est une **région active** : un texte qui change chaque seconde y serait annoncé chaque seconde,
     * et couvrirait la consigne. Ce qu'un utilisateur non voyant reçoit, c'est le message
     * d'après-coup — qui, lui, ne se déclenche qu'une fois.
     *
     * ⚠️⚠️ **Affiché ET lisible, mais pas annoncé** — les deux cas ensemble le disent, et il a fallu
     * une mesure pour y arriver : la première version masquait le compteur par `clearAndSetSemantics`,
     * et un nœud effacé disparaît des **deux** arbres. Le cas précédent tombait, ce qui était juste :
     * le compteur n'était plus lisible par personne, même à l'exploration.
     */
    @Test
    fun le_compteur_n_est_PAS_annonce() {
        poser(EtapeDeDictee.ENREGISTREMENT, secondes = 97)

        val annonces = regionsActives().map { it.first }

        assertThat(annonces).doesNotContain("1:37 / 2:00")
        assertThat(annonces.none { it.contains("1:37") }).isTrue()
        assertThat(annonces).contains(texte(R.string.voice_recording_hint))
    }

    /** ⚠️ Hors enregistrement il n'y a rien à compter — le micro est fermé ou le calcul est lancé. */
    @Test
    fun hors_enregistrement_aucun_compteur() {
        for (etape in listOf(EtapeDeDictee.INITIALISATION, EtapeDeDictee.TRANSCRIPTION)) {
            poser(etape, secondes = 97)

            regle.onNodeWithText("1:37 / 2:00").assertDoesNotExist()
        }
    }

    private companion object {
        val ETATS_ACTIFS = listOf(
            EtapeDeDictee.INITIALISATION,
            EtapeDeDictee.ENREGISTREMENT,
            EtapeDeDictee.TRANSCRIPTION,
        )

        /** ⚠️ Ni 0 ni 1 : un témoin de niveau qui vaudrait une borne cacherait un indicateur figé. */
        const val NIVEAU_TEMOIN = 0.4f

        /**
         * ⚠️ Ni 0 ni un multiple de 60 : un formateur qui perdrait les minutes, ou qui oublierait de
         * remplir les secondes, rendrait la même chose que le bon sur « 0:00 » et sur « 1:00 ».
         */
        const val SECONDES_TEMOIN = 97
    }
}
