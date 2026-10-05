package com.filestech.notes_tech.ui.folders

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.VaultDescriptor
import com.filestech.notes_tech.domain.model.VaultMode
import com.filestech.notes_tech.ui.CHAMP_DE_SAISIE
import com.filestech.notes_tech.ui.actionnablesSansNom
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
 * **Ce que le tiroir des dossiers annonce, et ce que ses boutons font vraiment.**
 *
 * Lignes `folders_drawer.dart` et `folder_dialogs.dart` de `docs/05-PARITE.md`.
 *
 * ⚠️ **Une prémisse est tombée avant d'écrire la première ligne** : je pensais que le tiroir appelait
 * les feuilles de coffre, et que la question du tour serait « que fait-il des issues qu'elles
 * remontent ? ». C'est faux — dans le portage, `HomeRoute` et `NoteEditorScreen` les appellent, pas
 * lui. L'application publiée, elle, les ouvre bien depuis son tiroir. Divergence d'architecture
 * assumée, sans effet sur ce qui est annoncé.
 *
 * ## Les quatre questions de la phase 8
 *
 * 1. *Que reçoit un lecteur d'écran ?* → les trois balayages, **et surtout** la question propre à ce
 *    tiroir : un `IconButton` posé dans le slot `badge` d'un `NavigationDrawerItem` — donc un
 *    cliquable **dans** un cliquable qui fusionne ses descendants — reste-t-il atteignable ?
 * 2. *Quels états ne sait-on pas atteindre à la main ?* → le tiroir est déjà sans état ; son état de
 *    **chargement**, lui, ne dure que quelques images.
 * 3. *Combien de tests ont été ignorés ?* → compté des deux côtés.
 * 4. *Que voulait le publié ?* → un menu à entrées mutuellement exclusives, et un appui long en plus
 *    du bouton.
 */
@RunWith(AndroidJUnit4::class)
class TiroirDesDossiersTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val etatCourant = mutableStateOf(FoldersUiState())
    private val dossierCourant = mutableStateOf<String?>(null)
    private var pose = false

    private val selections = mutableListOf<String?>()
    private val menus = mutableListOf<Folder>()
    private val renommagesDeBoite = mutableListOf<Folder>()
    private val corbeilles = mutableListOf<Unit>()
    private val creations = mutableListOf<Unit>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    private fun poser(etat: FoldersUiState = pleinDeDossiers()) {
        if (pose) {
            regle.runOnIdle { etatCourant.value = etat }
            regle.waitForIdle()
            return
        }
        etatCourant.value = etat
        pose = true
        regle.setContent {
            NotesTechTheme {
                FoldersDrawer(
                    state = etatCourant.value,
                    currentFolderId = dossierCourant.value,
                    onSelect = { selections += it },
                    onOpenTrash = { corbeilles += Unit },
                    onCreateFolder = { creations += Unit },
                    onFolderMenu = { menus += it },
                    onRenameInbox = { renommagesDeBoite += it },
                )
            }
        }
        regle.waitForIdle()
    }

    // ── Question 1 : ce qu'un lecteur d'écran reçoit, et ce que les boutons font ─────────────────

    /**
     * 🔴🔴 **La question de ce tiroir : le `⋮` ouvre-t-il le MENU, ou le dossier ?**
     *
     * Le bouton est posé dans le slot `badge` d'un `NavigationDrawerItem`, c'est-à-dire **un
     * cliquable à l'intérieur d'un cliquable qui fusionne ses descendants**. C'est la forme même du
     * défaut §74 : si le bouton est absorbé, `onNodeWithContentDescription` rend la **rangée** — dont
     * le nom contient bien « Options du dossier » puisque la fusion remonte les descriptions — et un
     * appui à son centre sélectionne le dossier. Le test passerait sur `assertHasClickAction`, et
     * l'utilisateur n'atteindrait jamais le menu.
     *
     * D'où une assertion de **comportement** et non de présence : c'est le seul niveau où les deux
     * cas se distinguent.
     */
    @Test
    fun le_bouton_d_options_d_un_dossier_ouvre_le_menu_et_ne_selectionne_PAS_le_dossier() {
        poser()

        regle.onAllNodesWithContentDescription(texte(R.string.drawer_folder_options))[0].performClick()
        regle.waitForIdle()

        assertThat(menus.map { it.id }).containsExactly("travail")
        assertThat(selections).isEmpty()
    }

    /**
     * 🔴 Le jumeau du précédent, sur la boîte de réception — dont le bouton de renommage a la même
     * forme, et existe parce que le portage n'a **aucun appui long** là où le publié en a un.
     */
    @Test
    fun le_bouton_de_renommage_de_la_boite_ouvre_le_renommage_et_ne_la_selectionne_PAS() {
        poser()

        regle.onNodeWithContentDescription(texte(R.string.common_rename)).performClick()
        regle.waitForIdle()

        assertThat(renommagesDeBoite.map { it.id }).containsExactly(Folder.INBOX_ID)
        assertThat(selections).isEmpty()
    }

    /** Le témoin des deux précédents : un appui sur la **ligne** sélectionne bien le dossier. */
    @Test
    fun un_appui_sur_la_ligne_selectionne_le_dossier() {
        poser()

        regle.onNodeWithText("Travail").performClick()
        regle.waitForIdle()

        assertThat(selections).containsExactly("travail")
        assertThat(menus).isEmpty()
    }

    /**
     * ⚠️⚠️ **Le décompte AVANT le balayage** : un balayage qui n'a rien trouvé à balayer est vert lui
     * aussi (§83). Le nombre est **mesuré**, pas deviné, et il tombera si une entrée disparaît — ce
     * qui est le second service que rend ce test.
     */
    @Test
    fun aucun_actionnable_du_tiroir_n_est_sans_nom() {
        poser()

        assertThat(regle.onAllNodes(hasClickAction()).fetchSemanticsNodes()).hasSize(ACTIONNABLES)
        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    @Test
    fun aucune_action_du_tiroir_n_est_perdue_a_la_fusion() {
        poser()

        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    /**
     * ⚠️⚠️ **Fil-piège, et NON un balayage : le tiroir n'a aucune zone de saisie.**
     *
     * Renommer et créer passent par un dialogue, qui a la sienne et qui est mesuré plus bas. Brancher
     * `champsDeSaisieSansNom` ici rendrait une liste vide **pour la mauvaise raison**. Ce test dit ce
     * qui est vrai et se vérifie, comme `CorbeilleTest` : aucun nœud éditable.
     */
    @Test
    fun le_tiroir_ne_porte_aucune_zone_de_saisie() {
        poser()

        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).isEmpty()
    }

    /** Un coffre se reconnaît sans lire — mais il doit aussi **se dire**, et son nom suffit. */
    @Test
    fun un_dossier_coffre_reste_atteignable_et_nomme() {
        poser()

        regle.onNodeWithText("Secrets").assertIsDisplayed().performClick()
        regle.waitForIdle()

        assertThat(selections).containsExactly("secrets")
    }

    // ── Question 2 : l'état qu'aucun doigt ne tient ──────────────────────────────────────────────

    /**
     * 🔴 **Le tiroir en cours de chargement, et ce qu'il affirme sans le savoir.**
     *
     * `FoldersDrawerViewModel.state` démarre sur `FoldersUiState()`, donc `folders` vide, donc
     * `inbox == null`. Or le tiroir traite ce `null` comme « la boîte manque d'une base abîmée » :
     * il affiche le **nom traduit de repli** au lieu du nom réel, et **retire le bouton de
     * renommage**. Pendant les premières images, un utilisateur qui a renommé sa boîte voit donc
     * l'ancien nom d'usine.
     *
     * C'est le motif §75/§76 — *une valeur initiale de `stateIn` indiscernable d'une donnée* —, et
     * l'application publiée a exactement la même faiblesse (`snap.data ?? const <Folder>[]`, avec un
     * dossier de repli fabriqué). **Ce test le fige plutôt qu'il ne le corrige** : il dit ce que le
     * tiroir montre à ce moment-là, pour que ça ne change pas par accident.
     *
     * ⚠️ Ce qui est mesuré ici est donc un **constat**, pas une garantie. Cf. `04-PIEGES.md` §91.
     */
    @Test
    fun avant_la_reponse_de_la_base_le_tiroir_montre_une_boite_de_repli_sans_bouton() {
        poser(FoldersUiState())

        regle.onNodeWithText(texte(R.string.home_folder_inbox)).assertIsDisplayed()
        regle.onAllNodesWithContentDescription(texte(R.string.common_rename)).assertCountEquals(0)
        regle.onAllNodesWithText(texte(R.string.drawer_header_folders)).assertCountEquals(0)

        // Le témoin : dès que la base répond, le nom réel et le bouton apparaissent.
        poser()
        regle.onNodeWithText("Boîte à moi").assertIsDisplayed()
        regle.onNodeWithContentDescription(texte(R.string.common_rename)).assertIsDisplayed()
    }

    // ── Question 4 : le menu, tel que le publié le veut ──────────────────────────────────────────

    /**
     * Les entrées de coffre **s'excluent**. Trois états, trois menus — et chacun est vérifié par ce
     * qu'il contient **et** par ce qu'il ne contient pas : afficher « verrouiller maintenant » sur un
     * dossier qui n'est pas un coffre proposerait un geste qui échoue.
     */
    @Test
    fun le_menu_d_un_dossier_ordinaire_propose_la_conversion_et_pas_le_reste() {
        poserLeMenu(dossier("travail", "Travail"), deverrouille = false)

        regle.onNodeWithText(texte(R.string.drawer_convert_to_vault)).assertIsDisplayed()
        regle.onAllNodesWithText(texte(R.string.drawer_lock_now)).assertCountEquals(0)
        regle.onAllNodesWithText(texte(R.string.drawer_remove_vault_protection)).assertCountEquals(0)
        regle.onNodeWithText(texte(R.string.common_rename)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.common_delete)).performClick()

        // ⚠️ Le menu **rend une intention**, il n'agit pas : c'est l'appelant qui ouvre ensuite le
        // dialogue de confirmation. Sans cette assertion, la collecte serait morte et les trois cas
        // de menu ne vérifieraient que de l'affichage.
        assertThat(actionsChoisies).containsExactly(FolderAction.DELETE)
    }

    @Test
    fun le_menu_d_un_coffre_OUVERT_propose_de_le_refermer_et_de_retirer_la_protection() {
        poserLeMenu(coffre("secrets", "Secrets"), deverrouille = true)

        regle.onNodeWithText(texte(R.string.drawer_lock_now)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.drawer_remove_vault_protection)).assertIsDisplayed()
        regle.onAllNodesWithText(texte(R.string.drawer_convert_to_vault)).assertCountEquals(0)
    }

    @Test
    fun le_menu_d_un_coffre_FERME_ne_propose_pas_de_le_refermer() {
        poserLeMenu(coffre("secrets", "Secrets"), deverrouille = false)

        regle.onAllNodesWithText(texte(R.string.drawer_lock_now)).assertCountEquals(0)
        regle.onNodeWithText(texte(R.string.drawer_remove_vault_protection)).assertIsDisplayed()
    }

    /**
     * 🔴 Sur une feuille, `actionnablesSansNom` signale **toujours** le nœud muet que
     * `BottomSheetDefaults.DragHandle` pose à côté du nœud nommé. Exception ancrée sur le nœud qui
     * porte `Dismiss`, `containsExactly` conservé — même idiome qu'`AutocompletionTest` et
     * `FeuillesDeCoffreTest`.
     */
    @Test
    fun aucun_actionnable_du_menu_n_est_sans_nom_hormis_la_poignee() {
        poserLeMenu(coffre("secrets", "Secrets"), deverrouille = true)

        regle.seuleLaPoigneeEstSansNom()
        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    // ── Le dialogue de nom ───────────────────────────────────────────────────────────────────────

    /**
     * 🔴 **Le filet de §80 sur le seul champ de cette ligne de parité.**
     *
     * ⚠️ Le champ est **rempli avant d'être balayé** : à vide, son libellé flottant est encore posé
     * dedans et le balayage passerait quelle que soit la faute.
     */
    @Test
    fun le_champ_de_nom_garde_son_nom_une_fois_rempli() {
        poserLeDialogueDeNom(initial = "")

        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).hasSize(1)
        regle.onNode(CHAMP_DE_SAISIE).performTextInput("Recettes")
        regle.waitForIdle()

        assertThat(regle.champsDeSaisieSansNom()).isEmpty()
        assertThat(regle.actionnablesSansNom()).isEmpty()
        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    /**
     * ✅ **Un nom vide est refusé AVANT d'être envoyé.** Le dépôt le refuserait de toute façon, mais
     * après coup et par un message ; le bouton l'anticipe.
     *
     * ⚠️ Le témoin est dans le même test : dès qu'un caractère non blanc est saisi, il s'active.
     * Sans lui, l'assertion passerait sur un bouton désactivé pour toujours.
     */
    @Test
    fun le_dialogue_de_nom_refuse_un_nom_blanc_et_accepte_le_reste() {
        poserLeDialogueDeNom(initial = "   ")

        regle.onNodeWithText(texte(R.string.common_validate)).assertIsNotEnabled()

        regle.onNode(CHAMP_DE_SAISIE).performTextInput("Recettes")
        regle.waitForIdle()
        regle.onNodeWithText(texte(R.string.common_validate)).assertIsEnabled().performClick()

        assertThat(nomsConfirmes).hasSize(1)
        assertThat(nomsConfirmes.single().trim()).isEqualTo("Recettes")
    }

    // ── Les deux dialogues destructeurs ─────────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Les deux choix sont ENTIÈREMENT visibles, et c'est l'objet du test.**
     *
     * Ils étaient empilés dans l'emplacement de boutons d'un `AlertDialog`, dont la hauteur est
     * bornée : le dernier était **coupé net**, 72 px au lieu de 144. Et c'était « Supprimer
     * définitivement » — *l'action irréversible était celle qu'on ne voyait pas*. Le correctif du
     * 2026-08-15 les a mis dans le corps ; rien ne le mesurait.
     *
     * ⚠️ `assertIsDisplayed()` ne suffirait pas : il est vrai d'un nœud **partiellement** à l'écran,
     * c'est-à-dire précisément de celui qui était coupé. Ce qui distingue les deux situations, c'est
     * que les deux boutons ont la **même hauteur** — l'un tronqué, l'autre pas, ne l'auraient pas.
     */
    @Test
    fun les_deux_choix_de_suppression_sont_entierement_visibles() {
        poserLaSuppression(dossier("travail", "Travail"))

        val deplacer = regle.onNodeWithText(texte(R.string.folder_delete_move_to_inbox))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val definitif = regle.onNodeWithText(texte(R.string.folder_delete_permanent))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertThat(definitif.height).isEqualTo(deplacer.height)
        // Et l'irréversible reste **en dernier** : jamais celle qu'on touche par réflexe.
        assertThat(definitif.top).isGreaterThan(deplacer.top)
    }

    /**
     * ⚠️ **Un coffre ne dit pas la même chose**, et c'est tout l'enjeu : déplacer ses notes les
     * DÉCHIFFRE. Le corps et le libellé du premier choix changent tous les deux.
     */
    @Test
    fun supprimer_un_COFFRE_annonce_le_dechiffrement() {
        poserLaSuppression(coffre("secrets", "Secrets"))

        regle.onNodeWithText(texte(R.string.folder_delete_move_to_inbox_vault)).assertIsDisplayed()
        regle.onAllNodesWithText(texte(R.string.folder_delete_move_to_inbox)).assertCountEquals(0)
        regle.onNodeWithText(regle.activity.getString(R.string.folder_delete_vault_choice_body, "Secrets"))
            .assertIsDisplayed()

        regle.onNodeWithText(texte(R.string.folder_delete_permanent)).performClick()
        assertThat(choixDeSuppression).containsExactly(FolderDeletionChoice.DELETE_EVERYTHING)
    }

    /** Le témoin du précédent : un dossier ordinaire ne parle pas de déchiffrement. */
    @Test
    fun supprimer_un_dossier_ORDINAIRE_ne_parle_pas_de_dechiffrement() {
        poserLaSuppression(dossier("travail", "Travail"))

        regle.onNodeWithText(regle.activity.getString(R.string.folder_delete_choice_body, "Travail"))
            .assertIsDisplayed()
        regle.onAllNodesWithText(texte(R.string.folder_delete_move_to_inbox_vault)).assertCountEquals(0)
    }

    @Test
    fun aucun_actionnable_des_dialogues_destructeurs_n_est_sans_nom() {
        poserLaSuppression(coffre("secrets", "Secrets"))

        assertThat(regle.actionnablesSansNom()).isEmpty()
        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).isEmpty()
    }

    /**
     * Le retrait de protection nomme le dossier et **dit que le geste est irréversible**. Sans le
     * nom, un utilisateur qui a plusieurs coffres ne sait pas lequel il déchiffre.
     */
    @Test
    fun le_retrait_de_protection_nomme_le_dossier_et_ses_actionnables() {
        val cible = coffre("secrets", "Secrets")
        regle.setContent {
            NotesTechTheme {
                ConfirmRemoveVaultProtectionDialog(
                    folder = cible,
                    onDismiss = {},
                    onConfirm = { retraitsConfirmes += Unit },
                )
            }
        }
        regle.waitForIdle()

        regle.onNodeWithText(regle.activity.getString(R.string.folder_remove_vault_body, "Secrets"))
            .assertIsDisplayed()
        assertThat(regle.actionnablesSansNom()).isEmpty()
        assertThat(regle.actionsPerduesALaFusion()).isEmpty()

        regle.onNodeWithText(texte(R.string.folder_remove_vault_confirm)).performClick()
        assertThat(retraitsConfirmes).hasSize(1)
    }

    // ── Outils ──────────────────────────────────────────────────────────────────────────────────

    private val actionsChoisies = mutableListOf<FolderAction>()
    private val choixDeSuppression = mutableListOf<FolderDeletionChoice>()
    private val retraitsConfirmes = mutableListOf<Unit>()

    private fun poserLaSuppression(folder: Folder) {
        regle.setContent {
            NotesTechTheme {
                ConfirmDeleteFolderDialog(
                    folder = folder,
                    onDismiss = {},
                    onChoice = { choixDeSuppression += it },
                )
            }
        }
        regle.waitForIdle()
    }
    private val nomsConfirmes = mutableListOf<String>()

    private fun poserLeMenu(folder: Folder, deverrouille: Boolean) {
        regle.setContent {
            NotesTechTheme {
                FolderActionSheet(
                    folder = folder,
                    unlocked = deverrouille,
                    onDismiss = {},
                    onAction = { actionsChoisies += it },
                )
            }
        }
        regle.waitForIdle()
    }

    private fun poserLeDialogueDeNom(initial: String) {
        regle.setContent {
            NotesTechTheme {
                FolderNameDialog(
                    title = texte(R.string.drawer_new_folder),
                    fieldLabel = texte(R.string.folder_create_field),
                    initial = initial,
                    onDismiss = {},
                    onConfirm = { nomsConfirmes += it },
                )
            }
        }
        regle.waitForIdle()
    }

    private fun pleinDeDossiers() = FoldersUiState(
        folders = listOf(
            dossier(Folder.INBOX_ID, "Boîte à moi"),
            dossier("travail", "Travail"),
            coffre("secrets", "Secrets"),
        ),
        unlockedFolderIds = emptySet(),
    )

    private fun dossier(id: String, nom: String, vault: VaultDescriptor? = null) = Folder(
        id = id,
        name = nom,
        parentId = null,
        color = null,
        icon = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        vault = vault,
    )

    private fun coffre(id: String, nom: String) =
        dossier(id, nom, VaultDescriptor(mode = VaultMode.PIN, failedAttempts = 0))

    private companion object {
        /**
         * Mesuré sur le S9 : deux entrées fixes (« Toutes les notes », la boîte), le bouton de
         * renommage de la boîte, deux dossiers et leurs deux `⋮`, la corbeille et « Nouveau dossier ».
         */
        const val ACTIONNABLES = 9
    }
}
