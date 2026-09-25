package com.filestech.notes_tech.ui.editor

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.CHAMP_DE_SAISIE
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.champsDeSaisieSansNom
import com.filestech.notes_tech.ui.seuleLaPoigneeEstSansNom
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * **Ce que la feuille d'autocomplétion `[[…]]` annonce, et ce qu'elle fait d'une validation.**
 *
 * Ligne `link_autocomplete_sheet.dart` de `docs/05-PARITE.md`. Son défaut était **localisé et écrit**
 * avant qu'on ouvre la ligne — comme celui de la recherche à sa propre ligne, et pour le même motif :
 * une réponse qui ne dit pas à quelle question elle répond.
 *
 * ## 🔴🔴 Le défaut, et pourquoi il ne se voyait pas
 *
 * Les suggestions passent par un freinage de 120 ms, pendant lequel la liste est **vide** — vidée
 * exprès, pour qu'on ne puisse pas toucher une proposition de la requête précédente. Mais « vide parce
 * que je n'ai pas cherché » et « vide parce qu'il n'y a rien » étaient indiscernables, et la feuille
 * en tirait deux conclusions fausses : elle proposait **de créer** une note qui existe peut-être, et sa
 * validation au clavier **créait** un homonyme là où elle devait lier.
 *
 * ⚠️ La fenêtre dure 120 ms : aucun pilotage manuel ne la tient. C'est le même genre d'état que
 * §75 et §76 — visible seulement parce qu'on peut poser la réponse à la main.
 *
 * ⚠️ **Ce n'est pas une régression de parité** : l'application publiée crée toujours, et propose
 * « Créer » dans la même fenêtre. C'est la divergence **délibérée** du portage qui était
 * incomplètement efficace. Cf. `04-PIEGES.md` §84.
 */
@RunWith(AndroidJUnit4::class)
class AutocompletionTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val requetes = mutableListOf<String>()
    private val titresChoisis = mutableListOf<String>()
    private val creations = mutableListOf<String>()
    private val fermetures = mutableListOf<Unit>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    private val reponseCourante = mutableStateOf(SuggestionsDeLien())

    private var pose = false

    /** Pose la feuille une fois, puis ne fait plus que **remplacer la réponse** du ViewModel. */
    private fun poser(reponse: SuggestionsDeLien = SuggestionsDeLien()) {
        if (pose) {
            regle.runOnIdle { reponseCourante.value = reponse }
            regle.waitForIdle()
            return
        }
        reponseCourante.value = reponse
        pose = true
        regle.setContent {
            NotesTechTheme {
                FeuilleDAutocompletion(
                    suggestions = reponseCourante.value,
                    onRequeteChange = { requetes += it },
                    onChoisirUnTitre = { titresChoisis += it },
                    onCreer = { creations += it },
                    onDismiss = { fermetures += Unit },
                )
            }
        }
        regle.waitForIdle()
    }

    private fun champ() = regle.onNode(CHAMP_DE_SAISIE)

    /**
     * 🔴🔴 **Le défaut lui-même : tant que la réponse n'est pas là, la feuille n'affirme rien.**
     *
     * Ni « Créer *Alpha* » — qui suppose que la note n'existe pas — ni « Aucun résultat », qui l'affirme.
     *
     * ⚠️ Le témoin suit immédiatement, **même saisie, réponse arrivée** : là, « Créer » est une
     * proposition fondée. Sans lui, l'assertion passerait sur une feuille qui ne proposerait jamais rien.
     */
    @Test
    fun tant_que_la_reponse_n_est_pas_arrivee_la_feuille_ne_propose_pas_de_creer() {
        poser()
        champ().performTextInput("Alpha")
        regle.waitForIdle()

        regle.onNodeWithText(creerTexte("Alpha")).assertDoesNotExist()
        regle.onNodeWithText(texte(R.string.link_autocomplete_empty)).assertDoesNotExist()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertIsDisplayed()

        // Le témoin : la réponse arrive, elle ne contient pas « Alpha », et là créer est fondé.
        poser(SuggestionsDeLien(pour = "Alpha", titres = emptyList()))
        regle.onNodeWithText(creerTexte("Alpha")).assertIsDisplayed()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertDoesNotExist()
    }

    /**
     * 🔴 **A failed search says so, and offers nothing** — neither "Create" nor "No matching note" —
     * and Enter creates nothing: the search could not see whether the note exists (GPT-5.6 review,
     * 2026-09-25). The witness: the same input, answered, offers to create.
     */
    @Test
    fun une_recherche_en_echec_le_dit_et_ne_propose_pas_de_creer() {
        poser()
        champ().performTextInput("Alpha")
        regle.waitForIdle()
        poser(SuggestionsDeLien(pour = "Alpha", echec = true))

        regle.onNodeWithText(texte(R.string.link_autocomplete_failed)).assertIsDisplayed()
        regle.onNodeWithText(creerTexte("Alpha")).assertDoesNotExist()
        regle.onNodeWithText(texte(R.string.link_autocomplete_empty)).assertDoesNotExist()
        champ().performImeAction()
        regle.waitForIdle()
        assertThat(creations).isEmpty()
        assertThat(titresChoisis).isEmpty()

        poser(SuggestionsDeLien(pour = "Alpha", titres = emptyList()))
        regle.onNodeWithText(creerTexte("Alpha")).assertIsDisplayed()
    }

    /**
     * 🔴🔴 **Le cas qui coûte une note en double, mesuré de bout en bout.**
     *
     * Valider au clavier **dans** la fenêtre de freinage : l'ancienne feuille consultait une liste vide
     * et **créait**. Ici la validation est **retenue**, puis appliquée dès que la réponse arrive — et la
     * réponse dit que « Alpha » existe, donc elle **lie**.
     *
     * ⚠️ Trois assertions, et chacune tomberait seule si la correction était défaite : rien ne part
     * pendant l'attente, **une** liaison part ensuite, et **aucune** création n'a lieu. La dernière est
     * celle qui nomme le défaut ; la première est celle qui prouve que l'attente est réelle et non un
     * effet de bord de l'ordre des assertions.
     */
    @Test
    fun valider_pendant_l_attente_lie_la_note_existante_au_lieu_d_en_creer_un_homonyme() {
        poser()
        champ().performTextInput("Alpha")
        regle.waitForIdle()

        champ().performImeAction()
        regle.waitForIdle()
        assertThat(titresChoisis).isEmpty()
        assertThat(creations).isEmpty()

        poser(SuggestionsDeLien(pour = "Alpha", titres = listOf(note("Alpha"))))

        assertThat(titresChoisis).containsExactly("Alpha")
        assertThat(creations).isEmpty()
    }

    /**
     * ⚠️⚠️ **Deux appuis sur « Entrée » pendant l'attente ne font qu'UNE action.**
     *
     * C'est la règle §65 du dépôt — un événement qui **agit** doit avoir lieu une fois et pas deux — et
     * c'est le geste que fait quelqu'un d'impatient devant une feuille qui ne réagit pas tout de suite.
     * Le drapeau de retenue est un booléen, donc le poser deux fois ne le pose qu'une ; et il est remis
     * à zéro **avant** que l'action ne parte, sans quoi la recomposition qui suit la rejouerait.
     *
     * ⚠️ Une assertion de **compte**, et pas seulement de contenu : `containsExactly` sur une liste de
     * deux éléments identiques échouerait, là où un `contains` passerait.
     */
    @Test
    fun deux_validations_pendant_l_attente_ne_font_qu_une_seule_action() {
        poser()
        champ().performTextInput("Alpha")
        regle.waitForIdle()

        champ().performImeAction()
        champ().performImeAction()
        regle.waitForIdle()
        assertThat(titresChoisis).isEmpty()

        poser(SuggestionsDeLien(pour = "Alpha", titres = listOf(note("Alpha"))))

        assertThat(titresChoisis).containsExactly("Alpha")
        assertThat(creations).isEmpty()
    }

    /**
     * ⚠️⚠️ **Une validation retenue est ANNULÉE par la frappe suivante.**
     *
     * Elle portait sur un autre titre que celui qui est à l'écran. Sans cette annulation, taper
     * « Alpha », valider, puis continuer à taper « Alphabet » ferait partir un lien vers « Alpha » au
     * moment où la réponse arrive — un geste que l'utilisateur a demandé pour un mot qu'il a depuis
     * remplacé.
     *
     * Le témoin est le test précédent : sans frappe, la même validation part bien.
     */
    @Test
    fun une_frappe_annule_une_validation_retenue() {
        poser()
        champ().performTextInput("Alpha")
        regle.waitForIdle()
        champ().performImeAction()
        regle.waitForIdle()

        champ().performTextInput("bet")
        regle.waitForIdle()
        poser(SuggestionsDeLien(pour = "Alphabet", titres = emptyList()))

        assertThat(titresChoisis).isEmpty()
        assertThat(creations).isEmpty()
    }

    /**
     * Une validation sur une réponse **à jour** part tout de suite, sans rien retenir.
     *
     * ⚠️ C'est le cas nominal, et il vaut d'être mesuré : la retenue ne doit pas s'appliquer quand il
     * n'y a rien à attendre — une touche « Entrée » qui ne fait rien jusqu'à la prochaine émission
     * serait une régression que le test précédent, à lui seul, ne verrait pas.
     */
    @Test
    fun valider_sur_une_reponse_a_jour_agit_immediatement() {
        poser()
        champ().performTextInput("Beta")
        regle.waitForIdle()
        poser(SuggestionsDeLien(pour = "Beta", titres = emptyList()))

        champ().performImeAction()
        regle.waitForIdle()

        assertThat(creations).containsExactly("Beta")
        assertThat(titresChoisis).isEmpty()
    }

    /** Toucher une suggestion remonte **son** titre, et la saisie est bien transmise au ViewModel. */
    @Test
    fun toucher_une_suggestion_remonte_son_titre() {
        poser()
        champ().performTextInput("Al")
        regle.waitForIdle()
        assertThat(requetes.last()).isEqualTo("Al")

        poser(SuggestionsDeLien(pour = "Al", titres = listOf(note("Alpha"), note("Alphabet"))))
        regle.onNodeWithText("Alphabet").performClick()

        assertThat(titresChoisis).containsExactly("Alphabet")
        assertThat(creations).isEmpty()
    }

    /**
     * ⚠️ **« Aucun résultat » n'est dit qu'après avoir cherché**, et sur une saisie vide c'est le
     * comportement de l'application publiée.
     */
    @Test
    fun une_saisie_vide_annonce_l_absence_de_resultat_sans_indicateur() {
        poser(SuggestionsDeLien(pour = ""))

        regle.onNodeWithText(texte(R.string.link_autocomplete_empty)).assertIsDisplayed()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ les trois balayages

    /**
     * 🔴 Le troisième balayage, sur un champ **rempli** — l'état où le défaut §80 existe.
     *
     * ⚠️ Le compte des champs vient d'abord : un balayage qui n'a rien trouvé **à balayer** est vert
     * lui aussi (§78, §83). Cette feuille en porte **un**.
     */
    @Test
    fun aucun_champ_de_saisie_de_la_feuille_n_est_sans_nom() {
        poser()
        champ().performTextInput("Alpha")
        regle.waitForIdle()

        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).hasSize(1)
        assertThat(regle.champsDeSaisieSansNom()).isEmpty()
    }

    /** Le balayage §71, sur l'état qui porte le plus d'actionnables : deux suggestions et « Créer ». */
    @Test
    fun aucun_element_actionnable_de_la_feuille_n_est_sans_nom() {
        poserAvecPropositions()

        // 🔴🔴 **material3 pose DEUX nœuds sur la poignée de la feuille, aux mêmes coordonnées.**
        //
        // Mesuré sur le S9 le 2026-08-17, arbre fusionné, `Rect(492, 168, 588, 312)` :
        //
        // | Nœud | Actions | Nom |
        // |---|---|---|
        // | A | `OnLongClick` **seul** | **aucun** |
        // | B | `Collapse`, `Dismiss`, `OnClick` | « Poignée de déplacement » |
        //
        // Le nœud A est donc un actionnable sans nom — et il n'appartient pas au portage : c'est
        // `BottomSheetDefaults.DragHandle`, que `ModalBottomSheet` pose par défaut. Il paraîtra sur
        // **toutes** les feuilles de l'application, et il n'est pas nommable depuis l'appelant.
        //
        // ⚠️⚠️ **L'instrument n'est PAS affaibli pour autant.** L'exception est nommée ici, ancrée sur
        // la poignée **mesurée** — le seul nœud de cette feuille qui porte une action `Dismiss` — et
        // l'assertion reste un `containsExactly` : tout autre actionnable muet la fait tomber. Elle
        // tombera aussi le jour où material3 nommera son nœud, et ce sera l'ordre de retirer
        // l'exception, pas le signe d'un défaut. Même idiome que le fil-piège material3 d'`AccueilTest`.
        //
        // ⚠️ L'extension du balayage à l'appui long vient des **deux relectures externes** du
        // 2026-08-17, et elle était justifiée. Ce constat en est le premier effet de bord : *un filtre
        // élargi voit aussi ce que les bibliothèques laissent traîner.*
        //
        // Since 2026-09-24 the check is `seuleLaPoigneeEstSansNom`, shared by every sheet: the same
        // exception, with both bounds read in one frame.
        regle.seuleLaPoigneeEstSansNom()
    }

    /** Le filet de régression de §74. */
    @Test
    fun aucune_action_de_la_feuille_n_est_perdue_a_la_fusion() {
        poserAvecPropositions()

        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    /** Deux suggestions **et** l'entrée de création : tout ce que cette feuille sait afficher. */
    private fun poserAvecPropositions() {
        poser()
        champ().performTextInput("Al")
        regle.waitForIdle()
        poser(SuggestionsDeLien(pour = "Al", titres = listOf(note("Alpha"), note("Alphabet"))))
    }

    private fun creerTexte(requete: String): String =
        regle.activity.getString(R.string.link_autocomplete_create_new, requete)

    private fun note(titre: String): Note = Note(
        id = titre,
        title = titre,
        content = "",
        folderId = "dossier",
        tags = emptyList(),
        pinned = false,
        favorite = false,
        archived = false,
        trashedAt = null,
        createdAt = Instant.ofEpochMilli(HORODATAGE),
        updatedAt = Instant.ofEpochMilli(HORODATAGE),
        encrypted = null,
        encVersion = 1,
    )

    private companion object {
        val INDICATEUR_D_ACTIVITE = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

        const val HORODATAGE = 1_700_000_000_000L
    }
}
