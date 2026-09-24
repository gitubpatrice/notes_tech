package com.filestech.notes_tech.ui.editor

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.local.dao.NoteLinkRow
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.champsDeSaisieSansNom
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * **Ce que l'éditeur annonce, et ce qu'il montre selon son état.**
 *
 * Cinquième ligne d'écran de `docs/05-PARITE.md`, et la plus grosse du portage — l'écran où
 * l'utilisateur passe son temps, et le seul qui **écrit** des notes.
 *
 * `NoteEditorScreen` a été rendu **sans état** pour ce fichier, comme les quatre écrans précédents.
 * Ici ce ne sont pas une ou deux fenêtres rares qu'il rend atteignables mais **six états** qu'aucun
 * pilotage d'application n'obtient sans abîmer une vraie base : les quatre issues de chargement
 * (introuvable, dossier coffre disparu, contenu abîmé, coffre refermé pendant la frappe), l'échec
 * d'enregistrement, et sa raison nommée.
 *
 * ## 🔴🔴 Les deux défauts que cette ligne a trouvés
 *
 * 1. **Les deux zones de saisie n'avaient AUCUN nom accessible dès qu'elles portaient du texte.**
 *    Elles n'avaient qu'un `placeholder`, qui disparaît à la première lettre — de l'écran **et** de
 *    l'arbre de sémantique. Un lecteur d'écran annonçait deux champs anonymes sur une note ouverte.
 *    Sur une note **vide**, le placeholder est là et nomme le champ : c'est l'état sous lequel cet
 *    écran a toujours été relu, et l'état où le défaut n'existe pas. Le publié porte `labelText` sur
 *    les deux. `04-PIEGES.md` §80.
 * 2. **Le titre n'était pas plafonné à la saisie**, alors que le publié le plafonne à 200 caractères
 *    par un `LengthLimitingTextInputFormatter`. Au-delà, `saveEdits` refuse — **le titre et le corps
 *    ensemble**, puisque c'est un seul appel — donc plus rien ne s'enregistrait. §81.
 *
 * ⚠️ Aucun des deux n'était visible à la relecture, et **aucun des deux balayages existants ne
 * pouvait les voir** : `actionnablesSansNom` exclut délibérément les nœuds portant un `EditableText`,
 * et `actionsPerduesALaFusion` ne regarde que les actionnables. D'où le troisième instrument,
 * `champsDeSaisieSansNom`, et son témoin.
 */
@RunWith(AndroidJUnit4::class)
class EditeurTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val sorties = mutableListOf<Unit>()
    private val titres = mutableListOf<String>()
    private val contenus = mutableListOf<String>()
    private val dictees = mutableListOf<Unit>()
    private val insertionsDeLien = mutableListOf<Unit>()
    private val epingles = mutableListOf<Boolean>()
    private val favoris = mutableListOf<Boolean>()
    private val infos = mutableListOf<Unit>()
    private val deplacements = mutableListOf<Unit>()
    private val exports = mutableListOf<Unit>()
    private val copies = mutableListOf<Unit>()
    private val corbeilles = mutableListOf<Unit>()
    private val notesOuvertes = mutableListOf<String>()
    private val fantomes = mutableListOf<String>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    private val etatCourant = mutableStateOf(EditorUiState())
    private val liensCourants = mutableStateOf(PanneauDeLiens())
    private val dicteeCourante = mutableStateOf(false)

    private var pose = false

    private fun poser(etat: EditorUiState, liens: PanneauDeLiens = PanneauDeLiens(), dicteeActive: Boolean = false) {
        if (pose) {
            regle.runOnIdle {
                etatCourant.value = etat
                liensCourants.value = liens
                dicteeCourante.value = dicteeActive
            }
            regle.waitForIdle()
            return
        }
        etatCourant.value = etat
        liensCourants.value = liens
        dicteeCourante.value = dicteeActive
        pose = true
        regle.setContent {
            NotesTechTheme {
                NoteEditorScreen(
                    state = etatCourant.value,
                    liens = liensCourants.value,
                    messages = remember { SnackbarHostState() },
                    dicteeActive = dicteeCourante.value,
                    onQuitter = { sorties += Unit },
                    onTitreChange = { titres += it },
                    onContenuChange = { contenus += it.text },
                    onDicter = { dictees += Unit },
                    onInsererUnLien = { insertionsDeLien += Unit },
                    onEpingler = { epingles += it },
                    onFavori = { favoris += it },
                    onInfos = { infos += Unit },
                    onDeplacer = { deplacements += Unit },
                    onExporter = { exports += Unit },
                    onCopier = { copies += Unit },
                    onCorbeille = { corbeilles += Unit },
                    onOuvrirNote = { notesOuvertes += it },
                    onLienFantome = { fantomes += it },
                )
            }
        }
        regle.waitForIdle()
    }

    // ------------------------------------------------------------------ les trois balayages

    /**
     * 🔴🔴 **Le défaut §80 lui-même : les deux champs de l'éditeur portent un nom, note remplie.**
     *
     * ⚠️ L'état posé est celui qui **discrimine** : une note qui a un titre et un corps. Sur une note
     * vide, le placeholder nomme le champ et cette assertion passerait avec le défaut **comme sans** —
     * exactement la faute d'assertion vacante relevée deux fois le 2026-08-17. Le témoin de
     * l'instrument, lui, pose les trois formes côte à côte ([com.filestech.notes_tech.ui.BalayageDAccessibiliteTest]).
     */
    @Test
    fun aucun_champ_de_saisie_de_l_editeur_n_est_sans_nom() {
        poser(noteChargee())

        assertThat(regle.champsDeSaisieSansNom()).isEmpty()
    }

    /** Le balayage §71, sur l'état qui porte le plus d'actionnables. */
    @Test
    fun aucun_element_actionnable_de_l_editeur_n_est_sans_nom() {
        poser(noteChargee(), liens = liensComplets())

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    /**
     * Le même balayage, **menu de débordement ouvert** : six entrées de plus, dont deux dont le
     * libellé suit l'état de la note.
     */
    @Test
    fun aucun_element_actionnable_du_menu_de_debordement_n_est_sans_nom() {
        poser(noteChargee())

        regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_more)).performClick()
        regle.waitForIdle()

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    /** Le filet de régression de §74 : aucune action perdue au nœud fusionné qui l'annonce. */
    @Test
    fun aucune_action_de_l_editeur_n_est_perdue_a_la_fusion() {
        poser(noteChargee(), liens = liensComplets())

        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    // ------------------------------------------------- §81, le plafond du titre à la saisie

    /**
     * 🔴 **Coller un paragraphe dans le titre ne peut plus bloquer tous les enregistrements.**
     *
     * Le plafond est celui du dépôt, [NotesRepository.TITLE_MAX_LENGTH], et le publié l'applique au
     * champ (`LengthLimitingTextInputFormatter`). Sans lui, `saveEdits` refusait **le titre et le
     * corps ensemble** à chaque tentative, et le texte tapé ensuite n'était écrit nulle part.
     *
     * ⚠️ Le témoin est dans le même test : une saisie **sous** la limite doit remonter **intacte**.
     * Sans lui, l'assertion passerait sur un champ qui refuserait tout.
     */
    @Test
    fun un_titre_colle_au_dela_de_la_limite_est_plafonne_et_une_saisie_courte_passe_intacte() {
        poser(noteChargee(titre = ""))

        champDuTitre().performTextInput("x".repeat(NotesRepository.TITLE_MAX_LENGTH + 100))
        assertThat(titres.last()).hasLength(NotesRepository.TITLE_MAX_LENGTH)

        // Le témoin : sous la limite, rien n'est touché.
        champDuTitre().performTextInput("court")
        assertThat(titres.last()).isEqualTo("court")
    }

    /**
     * 🔴🔴 **Tout sélectionner puis coller sur un titre EXISTANT : le geste passe, tronqué.**
     *
     * Le test précédent part d'un champ **vide** — et un champ vide passe n'importe quelle garde qui
     * regarde le texte en place. C'est ce qui a masqué, le temps d'une relecture, une règle qui
     * **refusait tout remplacement** débordant : écraser un titre par un autre ne faisait plus rien,
     * en silence, alors que le même collage dans un champ vierge fonctionnait.
     *
     * ⚠️ Relevé par une relecture externe (Gemini Pro, 2026-08-17) sur le correctif d'une **autre**
     * relecture. *Le choix des données initiales d'un test peut désarmer la garde qu'il croit
     * mesurer.*
     */
    @Test
    fun ecraser_un_titre_existant_par_un_collage_trop_long_le_tronque_au_lieu_de_l_ignorer() {
        poser(noteChargee(titre = "A".repeat(150)))

        champDuTitre().performTextReplacement("x".repeat(NotesRepository.TITLE_MAX_LENGTH + 100))

        assertThat(titres.last()).hasLength(NotesRepository.TITLE_MAX_LENGTH)
        assertThat(titres.last()).doesNotContain("A")
    }

    /**
     * **Un titre déjà plus long que la limite n'est pas FIGÉ : le raccourcir passe.**
     *
     * C'est le seul chemin qui débloque l'enregistrement d'une note dont le titre hérité dépasse, et
     * une garde de saisie mal écrite le fermerait — un champ qui refuse tout est aussi bloquant qu'un
     * champ qui tronque.
     *
     * ## ⚠️⚠️ Ce que ce test NE mesure pas, et pourquoi — mesuré, pas supposé
     *
     * L'autre moitié de la règle — *ce qui ferait grandir un titre déjà au plafond est refusé* — n'est
     * **pas mesurable ici**, et deux gestes l'ont établi sur le S9, sur un titre de 250 caractères :
     *
     * | Geste | Candidat remonté au rappel |
     * |---|---|
     * | `performTextInput("x")` | **250** caractères — `x` + 249 des 250 anciens |
     * | `performTextInput("x" × 300)` | **250** caractères, tous des `x` |
     *
     * Dans ce harnais — un champ **contrôlé** dont l'état n'est jamais réécrit, puisque le rappel se
     * contente d'enregistrer ce qu'il reçoit — le candidat n'excède **jamais** la longueur du texte en
     * place. Aucune saisie ne peut donc produire la croissance que la garde refuse : le test serait
     * **vacant**, et il passerait avec la garde comme sans.
     *
     * 🔧 D'où la table de cas JVM, [PlafondDuTitreTest], qui exerce la règle **là où les longueurs se
     * choisissent**. C'est la leçon §76 sous une autre forme : deux niveaux de test, parce qu'un seul
     * aurait été vacant — et la raison est ici une **mesure**, pas une précaution de principe.
     */
    @Test
    fun un_titre_deja_trop_long_peut_toujours_etre_raccourci() {
        poser(noteChargee(titre = "A".repeat(NotesRepository.TITLE_MAX_LENGTH + 50)))

        champDuTitre().performTextClearance()

        assertThat(titres.last()).isEmpty()
    }

    // ---------------------------------------------------------- les issues de chargement

    /**
     * Pendant le chargement, un indicateur **nommé** — et aucun champ de saisie.
     *
     * ⚠️ L'assertion négative a son témoin juste après : la même note chargée montre bien ses champs.
     * Sans lui, elle passerait sur un écran qui n'en afficherait jamais.
     */
    @Test
    fun pendant_le_chargement_l_ecran_annonce_qu_il_charge_et_ne_montre_aucun_champ() {
        poser(EditorUiState(loading = true))

        regle.onNodeWithContentDescription(texte(R.string.common_loading)).assertIsDisplayed()
        assertThat(regle.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isEmpty()

        poser(noteChargee())
        assertThat(regle.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(2)
    }

    /**
     * 🔴 **Une note qu'on n'a pas pu ouvrir ne propose AUCUNE action de barre.**
     *
     * Épingler, mettre en favori, insérer un lien ou « Terminé » dans un contenu jamais déchiffré n'a
     * aucun sens — c'est un relevé CONFIRMÉ par une relecture externe du 2026-08-15, et cette ligne
     * de parité est la première à le **mesurer**.
     *
     * ⚠️ Et la ligne d'état ne dit surtout pas « Enregistré » : `loadError` compte comme un échec pour
     * elle, sans quoi l'écran affichait une coche rassurante **par-dessus** un corps qui dit
     * « impossible d'ouvrir ». Deux récits concurrents, et le faux était le rassurant.
     *
     * Le témoin est la note chargée, qui porte bien les quatre actions.
     */
    @Test
    fun une_note_illisible_n_offre_aucune_action_et_ne_s_annonce_pas_enregistree() {
        poser(EditorUiState(loading = false, note = note("a"), loadError = R.string.note_editor_error_load_generic))

        regle.onNodeWithText(texte(R.string.note_editor_error_load_generic)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.note_editor_saved)).assertDoesNotExist()
        ACTIONS_DE_BARRE.forEach { regle.onNodeWithContentDescription(texte(it)).assertDoesNotExist() }

        // Le témoin : les quatre actions existent bien sur une note ouverte, et l'état s'y affiche.
        poser(noteChargee())
        regle.onNodeWithText(texte(R.string.note_editor_saved)).assertIsDisplayed()
        ACTIONS_DE_BARRE.forEach { regle.onNodeWithContentDescription(texte(it)).assertIsDisplayed() }
    }

    /**
     * Les quatre issues de chargement portent **quatre messages distincts**, et pas un seul.
     *
     * 🔴 C'était le défaut de la phase 5 : toutes tombaient sur « demander le secret », y compris un
     * coffre **auto-détruit** — on invitait donc à saisir un code pour des notes qui n'existent plus.
     */
    @Test
    fun chaque_issue_de_chargement_a_son_propre_message() {
        poser(EditorUiState(loading = false, notFound = true))
        regle.onNodeWithText(texte(R.string.note_editor_error_not_found)).assertIsDisplayed()

        poser(
            EditorUiState(
                loading = false,
                note = note("a"),
                loadError = R.string.note_editor_error_vault_folder_missing,
            ),
        )
        regle.onNodeWithText(texte(R.string.note_editor_error_vault_folder_missing)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.note_editor_error_not_found)).assertDoesNotExist()

        poser(EditorUiState(loading = false, note = note("a"), lockedVault = dossier()))
        regle.onNodeWithText(texte(R.string.note_card_locked)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.note_editor_error_vault_relocked_during_edit)).assertDoesNotExist()

        // Le coffre s'est refermé PENDANT la frappe : le même écran, plus la phrase qui dit ce qui
        // vient d'être perdu. C'est le témoin de l'assertion négative ci-dessus.
        poser(
            EditorUiState(
                loading = false,
                note = note("a"),
                lockedVault = dossier(),
                lostToVaultLock = true,
            ),
        )
        regle.onNodeWithText(texte(R.string.note_editor_error_vault_relocked_during_edit)).assertIsDisplayed()
    }

    // ------------------------------------------------------ l'état de l'enregistrement

    /**
     * 🔴 **Rien du tout quand l'enregistrement a échoué** — ni « Enregistré », ni « Enregistrement… ».
     *
     * `saving` retombe à `false` sur un échec comme sur une réussite : la ligne affichait donc
     * « Enregistré », coche comprise, **au-dessus de la bannière rouge qui dit le contraire**.
     *
     * ⚠️ L'état qui discrimine est `saveFailed` **avec** `saving = false` — celui où les deux récits
     * se contredisent. Le témoin est le même état sans l'échec.
     */
    @Test
    fun un_echec_d_enregistrement_tait_la_ligne_d_etat_et_affiche_sa_banniere() {
        poser(noteChargee().copy(saveFailed = true))

        regle.onNodeWithText(texte(R.string.note_editor_saved)).assertDoesNotExist()
        regle.onNodeWithText(texte(R.string.note_editor_saving)).assertDoesNotExist()
        regle.onNodeWithText(texte(R.string.note_editor_error_save_failed)).assertIsDisplayed()

        // Le témoin : sans échec, la ligne d'état parle et la bannière disparaît.
        poser(noteChargee())
        regle.onNodeWithText(texte(R.string.note_editor_saved)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.note_editor_error_save_failed)).assertDoesNotExist()
    }

    /**
     * 🔴 **La raison REMPLACE le message générique**, elle ne s'y ajoute pas.
     *
     * Afficher les deux ferait lire « Échec de sauvegarde. Titre trop long » — la première moitié
     * n'apprend rien que la seconde ne dise mieux. Et `error_note_title_too_long` dit, elle, ce qu'il
     * faut faire pour débloquer un enregistrement qui échoue indéfiniment.
     */
    @Test
    fun la_raison_connue_d_un_echec_remplace_le_message_generique() {
        poser(noteChargee().copy(saveFailed = true, saveFailureReason = R.string.error_note_title_too_long))

        regle.onNodeWithText(texte(R.string.error_note_title_too_long)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.note_editor_error_save_failed)).assertDoesNotExist()
    }

    /** Pendant un enregistrement, la ligne le dit — et le nom du dossier reste affiché. */
    @Test
    fun pendant_un_enregistrement_la_ligne_le_dit_sans_effacer_le_nom_du_dossier() {
        poser(noteChargee().copy(saving = true))

        regle.onNodeWithText(texte(R.string.note_editor_saving)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.note_editor_saved)).assertDoesNotExist()
        regle.onNodeWithText(NOM_DU_DOSSIER).assertIsDisplayed()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertIsDisplayed()
    }

    // --------------------------------------------------------------- les sorties et la barre

    /**
     * **Les deux sorties de la barre font le MÊME geste**, et c'est voulu : vider puis quitter.
     *
     * La flèche et la coche « Terminé » ne sont pas redondantes pour autant — le publié a ajouté la
     * seconde sur un retour utilisateur daté, parce que *sans bouton visible, on ne sait pas qu'on
     * peut quitter sans risque* quand l'enregistrement est automatique.
     */
    @Test
    fun la_fleche_et_la_coche_terminee_remontent_toutes_deux_a_l_appelant() {
        poser(noteChargee())

        regle.onNodeWithContentDescription(texte(R.string.common_close)).performClick()
        assertThat(sorties).hasSize(1)

        regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_done)).performClick()
        assertThat(sorties).hasSize(2)
    }

    /**
     * ⚠️⚠️ **Le bouton micro est DÉSACTIVÉ pendant une dictée, et ça se mesure par
     * `assertIsNotEnabled`.**
     *
     * `IconButton(enabled = false)` **conserve** son action `OnClick` dans l'arbre de sémantique et
     * pose `Disabled` à côté : `assertDoesNotExist` serait vert sur un bouton toujours actif. C'est la
     * leçon §77, et c'est aussi ce qui fait que le balayage **voit** les actionnables désactivés — un
     * bouton grisé sans nom reste un bouton sans nom.
     */
    @Test
    fun le_bouton_micro_est_desactive_pendant_une_dictee_et_actif_sinon() {
        poser(noteChargee(), dicteeActive = true)

        regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_dictate)).assertIsNotEnabled()
        assertThat(dictees).isEmpty()

        // Le témoin : hors dictée, le même bouton répond.
        poser(noteChargee(), dicteeActive = false)
        regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_dictate)).performClick()
        assertThat(dictees).hasSize(1)
    }

    /** Le bouton de lien ouvre l'autocomplétion — le geste d'écriture le plus fréquent de l'écran. */
    @Test
    fun le_bouton_de_lien_demande_l_autocompletion() {
        poser(noteChargee())

        regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_insert_link)).performClick()

        assertThat(insertionsDeLien).hasSize(1)
    }

    // ------------------------------------------------------------- le menu de débordement

    /**
     * 🔴 **Le libellé du menu suit l'état, et la valeur remontée est l'INVERSE de l'état.**
     *
     * Un menu qui propose « Épingler » sur une note déjà épinglée annonce l'inverse de ce qu'il va
     * faire. Et c'est l'écran qui calcule `!note.pinned` — là où la note est connue — pour qu'un seul
     * lecteur de ce champ décide.
     *
     * ⚠️ L'assertion porte sur la **valeur** `false`, pas sur le seul fait d'avoir été appelé : un
     * écran qui remonterait toujours `true` désépinglerait une note en croyant l'épingler, et un test
     * qui compte les appels ne verrait rien.
     */
    @Test
    fun sur_une_note_epinglee_le_menu_propose_de_desepingler_et_remonte_faux() {
        poser(noteChargee(epinglee = true))

        regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_more)).performClick()
        regle.onNodeWithText(texte(R.string.home_unpin)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.note_editor_tooltip_pin)).assertDoesNotExist()
        regle.onNodeWithText(texte(R.string.home_unpin)).performClick()

        assertThat(epingles).containsExactly(false)
    }

    /** Le témoin du précédent, sur une note qui n'est pas épinglée. */
    @Test
    fun sur_une_note_non_epinglee_le_menu_propose_d_epingler_et_remonte_vrai() {
        poser(noteChargee(epinglee = false))

        regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_more)).performClick()
        regle.onNodeWithText(texte(R.string.note_editor_tooltip_pin)).performClick()

        assertThat(epingles).containsExactly(true)
    }

    /**
     * Les quatre entrées restantes du menu remontent chacune à **son** rappel.
     *
     * ⚠️ Mesurer les quatre dans un seul test est délibéré : ce qui peut se tromper ici est un
     * **croisement** — l'export qui déclenche le déplacement — et un test par entrée ne le verrait
     * pas, chacun ne regardant que sa propre liste. Les quatre listes sont donc vérifiées ensemble.
     */
    @Test
    fun les_quatre_autres_entrees_du_menu_remontent_chacune_a_son_rappel() {
        poser(noteChargee())

        listOf(
            R.string.note_editor_menu_move,
            R.string.note_editor_menu_export,
            R.string.note_editor_menu_copy_markdown,
            R.string.note_editor_menu_trash,
        ).forEach { entree ->
            regle.onNodeWithContentDescription(texte(R.string.note_editor_tooltip_more)).performClick()
            regle.onNodeWithText(texte(entree)).performClick()
            regle.waitForIdle()
        }

        assertThat(deplacements).hasSize(1)
        assertThat(exports).hasSize(1)
        assertThat(copies).hasSize(1)
        assertThat(corbeilles).hasSize(1)
    }

    // ------------------------------------------------------------------ le panneau de liens

    /**
     * **Une note sans lien ne paie aucune place à l'écran pour l'annoncer.**
     *
     * ⚠️ Le témoin suit : la même note avec des liens montre bien ses deux sections. Sans lui,
     * l'assertion passerait sur un panneau qui n'apparaîtrait jamais — ce qui est exactement ce que la
     * valeur initiale de `stateIn` produit tant que la base n'a pas répondu, et c'est là le seul
     * comportement acceptable pour une **absence** de donnée : ne rien affirmer.
     */
    @Test
    fun une_note_sans_lien_n_affiche_aucune_section_de_liens() {
        poser(noteChargee(), liens = PanneauDeLiens())

        regle.onNodeWithText(texte(R.string.note_editor_backlinks), substring = true).assertDoesNotExist()

        poser(noteChargee(), liens = liensComplets())
        regle.onNodeWithText(texte(R.string.note_editor_backlinks), substring = true).assertIsDisplayed()
    }

    /**
     * Un lien **résolu** ouvre sa cible ; un lien **fantôme** propose de créer la note, et le dit.
     *
     * 🔴 La distinction n'est pas cosmétique : un lien fantôme désigne une note que l'utilisateur a
     * annoncée et pas encore écrite. La cacher perdrait l'intention ; l'ouvrir comme les autres
     * mènerait à rien. Sa description **dit ce que son libellé ne dit pas** — que cette note n'existe
     * pas encore.
     */
    @Test
    fun un_lien_resolu_ouvre_sa_cible_et_un_lien_fantome_propose_de_creer_la_note() {
        poser(noteChargee(), liens = liensComplets())

        regle.onNodeWithText(TITRE_CIBLE).performClick()
        assertThat(notesOuvertes).containsExactly("cible")

        regle.onNode(
            hasContentDescription(
                regle.activity.getString(R.string.note_editor_backlink_dangling, TITRE_FANTOME),
            ),
        ).performClick()
        assertThat(fantomes).containsExactly(TITRE_FANTOME)
    }

    /** Une note qui **mentionne** celle-ci s'ouvre depuis le panneau. */
    @Test
    fun une_mention_ouvre_la_note_qui_cite_celle_ci() {
        poser(noteChargee(), liens = liensComplets())

        regle.onNodeWithText(TITRE_MENTION).performClick()

        assertThat(notesOuvertes).containsExactly("mention")
    }

    // ------------------------------------------------------------------------- outillage

    private fun champDuTitre() = regle.onNode(hasSetTextAction() and hasText(texte(R.string.note_editor_title)))

    private fun noteChargee(titre: String = TITRE_DE_LA_NOTE, epinglee: Boolean = false) = EditorUiState(
        loading = false,
        title = titre,
        content = TextFieldValue(CORPS_DE_LA_NOTE),
        note = note("a").copy(pinned = epinglee),
        folder = dossier(),
        originalTitle = titre,
        originalContent = CORPS_DE_LA_NOTE,
    )

    private fun liensComplets() = PanneauDeLiens(
        sortants = listOf(
            NoteLinkRow(
                sourceId = "a",
                targetId = "cible",
                targetTitle = TITRE_CIBLE,
                targetTitleNorm = TITRE_CIBLE.lowercase(),
                position = 0,
            ),
            NoteLinkRow(
                sourceId = "a",
                targetId = null,
                targetTitle = TITRE_FANTOME,
                targetTitleNorm = TITRE_FANTOME.lowercase(),
                position = 20,
            ),
        ),
        mentions = listOf(note("mention", TITRE_MENTION)),
    )

    private fun note(id: String, titre: String = TITRE_DE_LA_NOTE): Note = Note(
        id = id,
        title = titre,
        content = CORPS_DE_LA_NOTE,
        folderId = "dossier-de-test",
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

    /**
     * ⚠️ **Sans coffre, même pour l'état `lockedVault`** : l'écran ne lit de ce dossier que le fait
     * qu'il existe — la branche verrouillée n'affiche ni son nom ni son descripteur. Fabriquer un
     * `VaultDescriptor` de test ferait croire que l'écran en dépend.
     */
    private fun dossier(): Folder = Folder(
        id = "dossier-de-test",
        name = NOM_DU_DOSSIER,
        parentId = null,
        color = null,
        icon = null,
        createdAt = Instant.ofEpochMilli(HORODATAGE),
        updatedAt = Instant.ofEpochMilli(HORODATAGE),
        vault = null,
    )

    private companion object {
        val INDICATEUR_D_ACTIVITE = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

        /** Les quatre actions de barre d'une note ouverte, dans l'ordre où elles apparaissent. */
        val ACTIONS_DE_BARRE = listOf(
            R.string.note_editor_tooltip_done,
            R.string.note_editor_tooltip_dictate,
            R.string.note_editor_tooltip_insert_link,
            R.string.note_editor_tooltip_more,
        )

        const val TITRE_DE_LA_NOTE = "Les impots de 2024"
        const val CORPS_DE_LA_NOTE = "Le corps de la note, en clair."
        const val NOM_DU_DOSSIER = "Dossier de test"
        const val TITRE_CIBLE = "Une note qui existe"
        const val TITRE_FANTOME = "Une note a ecrire"
        const val TITRE_MENTION = "Une note qui cite celle ci"
        const val HORODATAGE = 1_700_000_000_000L
    }
}
