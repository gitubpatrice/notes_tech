package com.filestech.notes_tech.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.EncryptedBody
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * **Ce que la recherche annonce, et ce qu'elle montre selon son état.**
 *
 * Troisième ligne d'écran de `docs/05-PARITE.md`. `SearchScreen` a été rendu **sans état** pour ce
 * fichier, comme l'accueil et la corbeille avant lui — et pour la même raison : l'état qui portait le
 * défaut est celui qu'on n'atteint pas en pilotant l'application, la fenêtre entre une frappe et la
 * réponse de l'index.
 *
 * ## 🔴 Le défaut, trouvé par balayage de motif et non par usage
 *
 * L'écran affichait **« Aucun résultat. Essayez un autre mot-clé » pendant la recherche**. Le
 * `combine` du ViewModel mêle deux flux de rythmes différents : la saisie, qui émet à chaque frappe,
 * et les résultats, qui passent par un freinage de 250 ms puis par une requête. Entre les deux,
 * l'état portait la **nouvelle** requête et l'**ancienne** issue.
 *
 * Le message accusait donc la saisie de l'utilisateur pour une réponse qui n'était pas encore
 * arrivée. `search_screen.dart:109` rend un indicateur d'activité dans ce cas : c'était une
 * **régression du portage**, pas un écart hérité. Cf. `04-PIEGES.md` §76.
 *
 * ⚠️ Trouvé en appliquant à tous les `stateIn` du portage la leçon §75 de la corbeille, **pas** en
 * utilisant l'écran : sur un téléphone, la fenêtre se referme en quelques centaines de millisecondes
 * et ce qu'on voit passer ressemble à un scintillement d'affichage.
 */
@RunWith(AndroidJUnit4::class)
class RechercheTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val retours = mutableListOf<Unit>()
    private val notesOuvertes = mutableListOf<Note>()
    private val requetes = mutableListOf<String>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    private val etatCourant = mutableStateOf(SearchUiState())

    private var pose = false

    private fun poser(etat: SearchUiState) {
        if (pose) {
            regle.runOnIdle { etatCourant.value = etat }
            regle.waitForIdle()
            return
        }
        etatCourant.value = etat
        pose = true
        regle.setContent {
            NotesTechTheme {
                SearchScreen(
                    state = etatCourant.value,
                    onQueryChange = { requetes += it },
                    onBack = { retours += Unit },
                    onOpenNote = { notesOuvertes += it },
                )
            }
        }
        regle.waitForIdle()
    }

    /**
     * 🔴🔴 **Le défaut lui-même : « aucun résultat » n'est pas dit avant d'avoir une réponse.**
     *
     * L'état discriminant est **une requête posée, aucune réponse en main** — c'est-à-dire
     * `searching = true` avec des résultats vides. La leçon de la veille est appliquée ici : une
     * assertion négative ne vaut que si un état la rendrait fausse, donc le témoin suit immédiatement,
     * **même requête, réponse arrivée**, et exige le message.
     */
    @Test
    fun tant_qu_aucune_reponse_n_est_arrivee_l_ecran_ne_dit_pas_qu_il_n_y_a_aucun_resultat() {
        poser(SearchUiState(query = "impots", searching = true))

        regle.onNodeWithText(texte(R.string.search_empty)).assertDoesNotExist()
        regle.onNodeWithText(texte(R.string.search_try_other)).assertDoesNotExist()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertIsDisplayed()

        // Le témoin, avec la MÊME requête : la réponse est arrivée, elle est vide, et là le message
        // est une affirmation. Sans ce second état, l'assertion ci-dessus passerait tout aussi bien
        // sur un écran qui n'afficherait jamais ce message.
        poser(SearchUiState(query = "impots", searching = false))
        regle.onNodeWithText(texte(R.string.search_empty)).assertIsDisplayed()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertDoesNotExist()
    }

    /**
     * ⚠️⚠️ **L'indicateur ne remplace PAS une liste déjà affichée, et c'est délibéré.**
     *
     * `state.searching && state.results.isEmpty()` : la seconde moitié de la condition est ce qui
     * évite un clignotement à chaque lettre tapée. L'application publiée laisse les résultats de la
     * requête précédente en place pendant le freinage (`search_screen.dart` ne remplace son
     * `_future` qu'à l'expiration du `Debouncer`), et c'est le comportement retenu.
     *
     * 🔴 Sans ce test, la correction du défaut aurait pu être « toujours l'indicateur dès que
     * `searching` » — plus simple à écrire, et une régression d'usage à chaque frappe.
     */
    @Test
    fun une_recherche_en_cours_laisse_les_resultats_precedents_a_l_ecran() {
        val notes = listOf(note("a", TITRE))

        poser(SearchUiState(query = "impots", results = notes, searching = false))
        regle.onNode(hasContentDescription(TITRE, substring = true)).assertIsDisplayed()

        // La frappe suivante : la réponse en main ne répond plus à la saisie, mais elle est encore
        // la meilleure chose à montrer.
        poser(SearchUiState(query = "impots 2024", results = notes, searching = true))
        regle.onNode(hasContentDescription(TITRE, substring = true)).assertIsDisplayed()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertDoesNotExist()
    }

    /**
     * 🔴 **L'échec d'une requête abandonnée ne s'affiche pas sous la suivante.**
     *
     * `failed` et `searching` sont exclusifs par construction dans le ViewModel — l'issue en échec
     * porte la requête à laquelle elle répond, et `failed` est retenu **seulement** si c'est la
     * requête courante. Cet écran le vérifie du côté de l'affichage : un état qui les porterait tous
     * les deux ne doit pas montrer l'erreur.
     *
     * ⚠️ L'état posé ici est volontairement **impossible** dans la production. C'est le propre d'un
     * composable sans état : il permet de figer ce que l'écran fait d'une combinaison que seul un
     * défaut futur du ViewModel produirait.
     */
    @Test
    fun un_echec_de_requete_abandonnee_ne_s_affiche_pas_pendant_la_recherche_suivante() {
        poser(SearchUiState(query = "impots 2024", failed = true, searching = true))

        regle.onNodeWithText(texte(R.string.search_error_generic)).assertDoesNotExist()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertIsDisplayed()

        // Le témoin : le même échec, mais pour la requête courante, s'affiche bien.
        poser(SearchUiState(query = "impots 2024", failed = true, searching = false))
        regle.onNodeWithText(texte(R.string.search_error_generic)).assertIsDisplayed()
    }

    /**
     * L'état d'accueil : une saisie vide n'est pas une recherche sans résultat.
     *
     * ⚠️ Il prime sur `searching` — une requête effacée pendant qu'une réponse est en vol ne doit pas
     * laisser un indicateur tourner sur un écran d'accueil.
     */
    @Test
    fun une_saisie_vide_montre_l_accueil_et_pas_un_indicateur() {
        poser(SearchUiState(query = "", searching = true))

        regle.onNodeWithText(texte(R.string.search_empty_title)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.search_empty_subtitle_fts)).assertIsDisplayed()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertDoesNotExist()
        regle.onNodeWithText(texte(R.string.search_empty)).assertDoesNotExist()
    }

    /** Le balayage mécanique, sur l'état qui porte le plus d'actionnables. */
    @Test
    fun aucun_element_actionnable_de_la_recherche_n_est_sans_nom() {
        poser(
            SearchUiState(
                query = "impots",
                results = listOf(note("a", TITRE), note("b", "Une autre")),
                folderNamesById = mapOf("dossier-de-test" to "Dossier de test"),
            ),
        )

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    /** Le filet de régression de §74 : aucune action perdue au nœud fusionné qui l'annonce. */
    @Test
    fun aucune_action_de_la_recherche_n_est_perdue_a_la_fusion() {
        poser(
            SearchUiState(
                query = "impots",
                results = listOf(note("a", TITRE), note("b", "Une autre")),
                folderNamesById = mapOf("dossier-de-test" to "Dossier de test"),
            ),
        )

        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    /**
     * 🔴 **La promesse de cet écran : une note verrouillée n'y apparaît jamais.**
     *
     * La garantie vit dans la requête, pas ici — c'est écrit dans l'écran et c'est voulu. Ce test ne
     * la contredit pas : il vérifie que **si** une note scellée arrivait quand même dans les
     * résultats, l'écran ne divulguerait ni son titre ni ses étiquettes. Deux défenses valent mieux
     * qu'une pour un contenu qui ne doit pas fuir, et celle-ci est gratuite — elle vient de
     * `NoteCard`.
     */
    @Test
    fun une_note_scellee_arrivant_dans_les_resultats_ne_divulgue_rien() {
        val scellee = note("a", TITRE, listOf("medical"))
            .copy(encrypted = EncryptedBody(ByteArray(32)))
        poser(SearchUiState(query = "impots", results = listOf(scellee)))

        regle.onNodeWithText(texte(R.string.note_card_locked)).assertIsDisplayed()
        regle.onNodeWithText(TITRE).assertDoesNotExist()
        regle.onNode(hasContentDescription(TITRE, substring = true)).assertDoesNotExist()
        regle.onNode(hasContentDescription("#medical", substring = true)).assertDoesNotExist()
    }

    /** Ouvrir un résultat rend **la** note touchée. */
    @Test
    fun toucher_un_resultat_ouvre_la_note_correspondante() {
        val premiere = note("a", "Première")
        val seconde = note("b", "Seconde")
        poser(SearchUiState(query = "impots", results = listOf(premiere, seconde)))

        regle.onNode(hasContentDescription("Seconde", substring = true)).performClick()

        assertThat(notesOuvertes).containsExactly(seconde)
    }

    /**
     * La saisie remonte frappe par frappe, et le bouton d'effacement la vide.
     *
     * ⚠️ Le bouton d'effacement **n'existe pas** sur une saisie vide : le vérifier évite qu'il
     * devienne un actionnable permanent sans effet.
     */
    @Test
    fun le_champ_remonte_la_saisie_et_le_bouton_d_effacement_la_vide() {
        poser(SearchUiState(query = ""))
        regle.onNodeWithContentDescription(texte(R.string.search_clear)).assertDoesNotExist()

        regle.onNodeWithText(texte(R.string.search_hint)).performTextInput("abc")
        assertThat(requetes).isNotEmpty()

        poser(SearchUiState(query = "abc"))
        regle.onNodeWithContentDescription(texte(R.string.search_clear)).performClick()
        assertThat(requetes.last()).isEmpty()
    }

    /** Le bouton de fermeture remonte : il est la seule sortie de l'écran. */
    @Test
    fun le_bouton_de_fermeture_remonte_a_l_appelant() {
        poser(SearchUiState(query = ""))

        regle.onNodeWithContentDescription(texte(R.string.common_close)).performClick()

        assertThat(retours).hasSize(1)
    }

    private fun note(id: String, titre: String, etiquettes: List<String> = emptyList()): Note = Note(
        id = id,
        title = titre,
        content = "corps de la note",
        folderId = "dossier-de-test",
        tags = etiquettes,
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

        const val TITRE = "Le titre de la note"
        const val HORODATAGE = 1_700_000_000_000L
    }
}
