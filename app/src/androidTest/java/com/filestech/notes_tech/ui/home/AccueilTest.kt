package com.filestech.notes_tech.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.ui.CHAMP_DE_SAISIE
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
 * **Ce que l'accueil annonce, et ce qu'il montre selon son état.**
 *
 * Première ligne de `docs/05-PARITE.md` à passer de « le fichier existe » à « vérifié sur
 * appareil ». `HomeScreen` est sans état : il se pose tel quel, sans base, sans Hilt, avec un
 * [HomeUiState] fabriqué — donc les états rares (bannière de brouillons perdus, liste vide, échec de
 * chargement) sont atteignables, alors qu'ils ne le sont pas en pilotant l'application à la main.
 *
 * ## 🔴 Ce fichier est né d'un relevé `uiautomator`, et il a confirmé ce qu'il montrait
 *
 * Le relevé de l'accueil sur le S9 montrait le bouton de nouvelle note **cliquable, sans texte ni
 * description**, marqué `NAF="true"` par uiautomator lui-même. Vérifié ici par l'instrument qui fait
 * foi — l'arbre de **sémantique** Compose, celui que lit un lecteur d'écran : arbre fusionné **0
 * nœud** portant « Nouvelle note », arbre non fusionné **1**, sous un nœud `ClearAndSetSemantics`.
 * `ExtendedFloatingActionButton` de material3 1.4.0 efface la sémantique de son emplacement `text`.
 * Le bouton s'annonçait donc « bouton », sans nom. Cf. `04-PIEGES.md` §71.
 *
 * ⚠️ D'où le cas témoin [les_quatre_actions_de_la_barre_annoncent_leur_libelle] : sans lui, une
 * assertion qui échoue ne distinguerait pas « le bouton est muet » de « ma recherche ne trouve
 * rien ». Ici les deux ont parlé — le témoin passait pendant que le bouton échouait, ce qui rend le
 * constat décidable.
 */
@RunWith(AndroidJUnit4::class)
class AccueilTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val nouvellesNotesDemandees = mutableListOf<Unit>()
    private val banniereFermee = mutableListOf<Unit>()
    private val notesOuvertes = mutableListOf<Note>()
    private val trisChoisis = mutableListOf<NoteSortMode>()
    private val requetes = mutableListOf<String>()
    private val tiroirsOuverts = mutableListOf<Unit>()
    private val recherchesOuvertes = mutableListOf<Unit>()
    private val reglagesOuverts = mutableListOf<Unit>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    /**
     * ⚠️ **`setContent` ne s'appelle qu'UNE fois par activité** — le second appel lève
     * « has already set content ». Un test qui doit comparer deux états passe donc par cet état
     * observable, pas par une seconde pose : c'est aussi plus fidèle, puisque la production
     * recompose au lieu de reconstruire l'écran.
     */
    private val etatCourant = mutableStateOf(HomeUiState())

    private var pose = false

    private fun poser(etat: HomeUiState) {
        if (pose) {
            // ⚠️ La mutation passe par `runOnIdle` — relevé par une relecture externe (GPT-5.2,
            // 2026-08-17). Écrire dans un `MutableState` depuis le fil de test fonctionne souvent,
            // et c'est une cause classique d'intermittence quand la suite grandit : l'écriture
            // n'est pas ordonnée par rapport aux instantanés que Compose est en train de lire.
            regle.runOnIdle { etatCourant.value = etat }
            regle.waitForIdle()
            return
        }
        etatCourant.value = etat
        pose = true
        regle.setContent {
            NotesTechTheme {
                HomeScreen(
                    state = etatCourant.value,
                    onQueryChange = { requetes += it },
                    onSortSelected = { trisChoisis += it },
                    onOpenNote = { notesOuvertes += it },
                    onNewNote = { nouvellesNotesDemandees += Unit },
                    onOpenDrawer = { tiroirsOuverts += Unit },
                    onOpenSearch = { recherchesOuvertes += Unit },
                    onOpenSettings = { reglagesOuverts += Unit },
                    onOpenAbout = {},
                    onDismissVaultLostBanner = { banniereFermee += Unit },
                )
            }
        }
        regle.waitForIdle()
    }

    /**
     * 🔴 **Le nom accessible du bouton de nouvelle note.**
     *
     * ⚠️ Cherché par **description** et non par texte, et ce n'est pas un détail de style : le
     * libellé du slot `text` est effacé de l'arbre fusionné par material3 (voir la note de classe).
     * Un test écrit sur `onNodeWithText` mesurerait donc l'arbre **non fusionné**, où le défaut est
     * invisible — et resterait vert avec un bouton muet.
     *
     * Le clic est vérifié dans le même test : un nom sans action et une action sans nom sont deux
     * défauts distincts, et il n'y a pas de raison de n'en mesurer qu'un.
     */
    @Test
    fun le_bouton_de_nouvelle_note_annonce_son_libelle_une_seule_fois() {
        val libelle = texte(R.string.home_new_note)
        poser(HomeUiState(notes = listOf(note("a", "Une note")), loading = false))

        val bouton = regle.onNode(hasClickAction() and hasContentDescription(libelle))
        bouton.assertIsDisplayed()

        // 🔴 **Exactement une description, pas deux.** C'est ce qui interdit de renommer aussi
        // l'icône « pour faire bonne mesure » : les deux se concatèneraient sur ce nœud.
        val noeud = bouton.fetchSemanticsNode()
        assertThat(noeud.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly(libelle)

        // ⚠️⚠️ **Fil-piège sur le comportement de material3, pas une exigence de l'application.**
        //
        // Le slot `text` est aujourd'hui effacé de l'arbre fusionné (§71), donc ce nœud ne porte
        // aucun `Text`. Si une montée de version le fait réapparaître, cette ligne échouera : ce ne
        // sera **pas** un défaut du code, mais l'ordre de refaire la mesure d'annonce, puisque
        // « la description prévaut sur le texte » est l'usage d'Android et non une garantie que
        // l'une des deux relectures ait acceptée de signer.
        assertThat(noeud.config.getOrNull(SemanticsProperties.Text).orEmpty()).isEmpty()

        bouton.performClick()
        assertThat(nouvellesNotesDemandees).hasSize(1)
    }

    /**
     * 🔴🔴 **Le balayage du motif, plus utile que le cas particulier.**
     *
     * Le bouton flottant n'a pas été trouvé par une relecture mais par un relevé mécanique : un
     * nœud **cliquable** dont le nom accessible est vide. Rien n'assure que ce soit le seul, ni
     * qu'un composant material3 futur ne refasse pas le coup — `clearAndSetSemantics` n'est pas
     * visible depuis le code appelant.
     *
     * Ce test pose donc la question à tout l'écran : *quels nœuds actionnables n'ont ni description
     * ni texte ?* Il échoue en **nommant leurs coordonnées**, ce qui suffit à les situer.
     *
     * ⚠️ La liste est bornée à l'arbre **fusionné** — c'est le seul qui décrive ce qu'un lecteur
     * d'écran reçoit.
     *
     * ⚠️⚠️ **L'appui long compte autant que le clic.** La première version ne cherchait que
     * `hasClickAction()` ; les deux relectures externes ont relevé le même angle mort — un nœud qui
     * n'expose qu'`OnLongClick` est actionnable pour l'utilisateur et invisible à ce filtre. Le
     * dépôt en a justement un précédent : `05-PARITE.md` note qu'un appui long sur la boîte de
     * réception **manquait entièrement** au portage, et qu'aucune chaîne orpheline ne le signalait.
     *
     * ⚠️ Le filtre et son témoin ont été **extraits** le 2026-08-17 vers
     * `ui/BalayageDAccessibilite.kt` et `ui/BalayageDAccessibiliteTest.kt` : ils servent maintenant à
     * chaque écran, et les recopier serait les laisser diverger.
     */
    @Test
    fun aucun_element_actionnable_de_l_accueil_n_est_sans_nom() {
        poser(HomeUiState(notes = listOf(note("a", "Une note")), loading = false, vaultLostCount = 1))

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    /**
     * 🔴 **Le balayage du motif INVERSE : aucune action perdue à la fusion.**
     *
     * C'est le filet de régression de §74 — la carte de note portait son nom sur un nœud et son clic
     * sur un autre, donc s'annonçait comme du **texte**. Le balayage précédent ne pouvait pas le voir :
     * il cherche une action **sans nom**, celui-ci était un nom **sans action**.
     *
     * ⚠️ Bâti sur l'arbre **non fusionné**, seul endroit où l'action reste visible avant d'être perdue.
     * Cf. `ui/BalayageDAccessibilite.kt` et son témoin.
     */
    @Test
    fun aucune_action_de_l_accueil_n_est_perdue_a_la_fusion() {
        poser(HomeUiState(notes = listOf(note("a", "Une note")), loading = false, vaultLostCount = 1))

        assertThat(regle.actionsPerduesALaFusion()).isEmpty()
    }

    /**
     * 🔴🔴 **Le TROISIÈME balayage, et cet écran avait été validé sans lui.**
     *
     * Les deux précédents sont aveugles aux zones de saisie : `actionnablesSansNom` **exclut**
     * délibérément les nœuds portant un `EditableText`, `actionsPerduesALaFusion` ne regarde que les
     * actionnables. L'accueil a donc été coché le 2026-08-17 sans que son champ de recherche ait
     * jamais été mesuré — *une exclusion raisonnable dans un instrument est un angle mort dans tous
     * les écrans qu'il a validés.* Cf. `04-PIEGES.md` §80.
     *
     * ⚠️ **La requête est REMPLIE, et c'est tout l'objet du test.** Un champ vide affiche son
     * `placeholder`, qui le nomme ; le défaut §80 n'apparaît qu'une fois du texte saisi, quand le
     * `placeholder` disparaît de l'écran **et** de l'arbre. Sur `query = ""` cette assertion passerait
     * avec le défaut comme sans.
     */
    @Test
    fun aucun_champ_de_saisie_de_l_accueil_n_est_sans_nom() {
        poser(
            HomeUiState(
                notes = listOf(note("a", "Une note")),
                loading = false,
                query = "impots",
            ),
        )

        // ⚠️ **D'abord : y a-t-il quelque chose à balayer ?** Un balayage qui rend une liste vide
        // parce qu'il n'a rien trouvé est indiscernable d'un balayage qui n'a rien à signaler — §78.
        // L'accueil porte **un** champ, celui de la recherche.
        assertThat(regle.onAllNodes(CHAMP_DE_SAISIE).fetchSemanticsNodes()).hasSize(1)

        assertThat(regle.champsDeSaisieSansNom()).isEmpty()
    }

    /** Les textes portés par les nœuds cliquables — sert à relever ce qu'une ouverture de menu ajoute. */
    private fun textesCliquables(): Set<String> = regle.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .flatMap { noeud -> noeud.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        .filter { it.isNotBlank() }
        .toSet()

    /**
     * ⚠️ **Le cas témoin.** Ces quatre-là sont des icônes à `contentDescription` explicite, et le
     * relevé `uiautomator` les voyait toutes les quatre. Ils prouvent donc que la recherche par
     * description fonctionne dans ce harnais — sans quoi l'échec du test précédent serait
     * indécidable.
     */
    @Test
    fun les_quatre_actions_de_la_barre_annoncent_leur_libelle() {
        poser(HomeUiState(notes = emptyList(), loading = false))

        val actions = listOf(
            R.string.home_folders,
            R.string.home_sort_mode,
            R.string.search_title,
            // ⚠️ Le dernier bouton ouvre un menu : il s'annonce « Plus d'options » et non
            // « Réglages », qui n'est que la première de ses deux entrées. Cf. §73.
            R.string.common_more_options,
        )
        for (id in actions) {
            regle.onNodeWithContentDescription(texte(id)).assertIsDisplayed()
        }
    }

    /**
     * La bannière du parité-tableau : `vault_lost_drafts`.
     *
     * ⚠️ **C'est un état qu'on n'atteint pas en pilotant l'application** — il faut qu'un coffre se
     * soit verrouillé pendant un enregistrement. Le composable sans état le rend accessible en une
     * ligne, et c'est la raison d'être de ce découpage.
     */
    @Test
    fun la_banniere_de_brouillons_perdus_ne_parait_que_lorsqu_il_y_en_a() {
        // ⚠️ Le libellé est reconstruit depuis les ressources avec le MÊME nombre : écrire un
        // fragment en dur ferait passer ce test pour de mauvaises raisons dès que la langue de
        // l'appareil change, et la forme plurielle du français ne se devine pas.
        val attendu = regle.activity.resources
            .getQuantityString(R.plurals.home_vault_lost_banner, PERDUES, PERDUES)

        poser(HomeUiState(notes = emptyList(), loading = false, vaultLostCount = 0))
        regle.onNodeWithText(attendu).assertDoesNotExist()

        poser(HomeUiState(notes = emptyList(), loading = false, vaultLostCount = PERDUES))
        regle.onNodeWithText(attendu).assertIsDisplayed()
    }

    /** Fermer la bannière remonte bien à l'appelant : sans ça elle serait indéboulonnable. */
    @Test
    fun la_banniere_de_brouillons_perdus_se_ferme() {
        poser(HomeUiState(notes = emptyList(), loading = false, vaultLostCount = 1))

        regle.onNodeWithText(texte(R.string.common_ok)).performClick()

        assertThat(banniereFermee).hasSize(1)
    }

    /**
     * Les **quatre** états du corps de l'écran, dans le même test parce qu'ils sont **exclusifs** :
     * chargement, échec, liste vide, liste. Les séparer laisserait passer le cas où deux
     * s'afficheraient ensemble, qui est la faute que le `when` sert à interdire.
     */
    @Test
    fun le_corps_de_l_ecran_montre_un_seul_etat_a_la_fois() {
        val laNote = "Le titre de la note"

        poser(HomeUiState(notes = emptyList(), loading = true))
        regle.onNodeWithText(texte(R.string.home_load_error)).assertDoesNotExist()
        regle.onNodeWithText(texte(R.string.home_no_notes)).assertDoesNotExist()

        poser(HomeUiState(notes = emptyList(), loading = false, failed = true))
        regle.onNodeWithText(texte(R.string.home_load_error)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.home_no_notes)).assertDoesNotExist()

        poser(HomeUiState(notes = emptyList(), loading = false))
        regle.onNodeWithText(texte(R.string.home_no_notes)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.home_load_error)).assertDoesNotExist()

        poser(HomeUiState(notes = listOf(note("a", laNote)), loading = false))
        regle.onNodeWithText(laNote).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.home_no_notes)).assertDoesNotExist()
    }

    /**
     * ⚠️ **L'état vide d'une RECHERCHE ne propose pas de créer une note**, celui d'une liste vide si.
     * C'est un écart facile à perdre en refactorant `ListeVide`, et il compte : proposer « nouvelle
     * note » à qui vient de chercher répond à côté de la question posée.
     */
    @Test
    fun l_etat_vide_d_une_recherche_ne_propose_pas_de_creer_une_note() {
        poser(HomeUiState(notes = emptyList(), loading = false))
        regle.onNodeWithText(texte(R.string.home_start_writing)).assertIsDisplayed()

        poser(HomeUiState(notes = emptyList(), loading = false, query = "introuvable"))
        regle.onNodeWithText(texte(R.string.search_empty)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.search_try_other)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.home_start_writing)).assertDoesNotExist()
    }

    /**
     * 🔴🔴 **La carte doit s'annoncer ACTIVABLE, pas seulement porter un nom.**
     *
     * Trouvé le 2026-08-17 par le témoin de `CorbeilleTest`, qui échouait : le nœud fusionné de la
     * carte portait sa description **sans aucune action `OnClick`**, parce que la sémantique était
     * posée sur le `Surface` et le `clickable` sur la `Column` fille — et les actions d'un descendant
     * ne remontent pas à la fusion, contrairement au texte.
     *
     * ⚠️⚠️ **Le geste marchait quand même**, et c'est ce qui rendait le défaut invisible : un
     * double-appui de lecteur d'écran envoie un toucher au centre du nœud focalisé, qui atteint la
     * fille cliquable. Ce qui manquait n'était pas l'ouverture, c'était **l'annonce** qu'on pouvait
     * ouvrir. Aucun test de comportement ne pouvait le voir, `performClick` non plus — il injecte un
     * toucher aux coordonnées du nœud et n'exige aucune action de sémantique. §74.
     *
     * ⚠️ Le clic est déclenché **sur ce nœud-là**, celui que vise le lecteur d'écran, et pas sur un
     * texte intérieur : c'est ce qui lie l'annonce à l'effet.
     */
    @Test
    fun une_carte_de_l_accueil_s_annonce_activable_sur_le_noeud_qui_porte_son_nom() {
        val laNote = note("a", "Le titre de la note")
        poser(HomeUiState(notes = listOf(laNote), loading = false))

        val carte = regle.onNode(hasContentDescription(laNote.title, substring = true))
        carte.assertHasClickAction()
        carte.performClick()

        assertThat(notesOuvertes).containsExactly(laNote)
    }

    /** Ouvrir une note rend **la** note, pas un identifiant reconstruit ailleurs. */
    @Test
    fun toucher_une_carte_ouvre_la_note_correspondante() {
        val premiere = note("a", "Première")
        val seconde = note("b", "Seconde")
        poser(HomeUiState(notes = listOf(premiere, seconde), loading = false))

        regle.onNodeWithText("Seconde").performClick()

        assertThat(notesOuvertes).containsExactly(seconde)
    }

    /**
     * Le tri : **six modes, six libellés DISTINCTS**, et le mode choisi remonte.
     *
     * ⚠️⚠️ Le compte de libellés distincts est ce qui fige le défaut corrigé des deux côtés le
     * 2026-08-14 (`05-PARITE.md`) : le menu publié affichait six entrées dont quatre partageaient
     * deux libellés, et seule la position du bouton radio disait ce qu'on avait choisi. Un test qui
     * se contenterait de compter **six entrées** n'aurait rien vu — c'est l'unicité qui portait le
     * défaut.
     *
     * 🔧 `libelleDeTri` est privée dans l'écran, et ce test ne la contourne pas : les libellés sont
     * relevés comme **la différence** entre les textes cliquables avant et après l'ouverture du
     * menu. Mécanique, et indépendant du nom des ressources.
     */
    @Test
    fun le_menu_de_tri_offre_six_libelles_distincts_et_rend_le_mode_choisi() {
        poser(HomeUiState(notes = emptyList(), loading = false))
        val avant = textesCliquables()

        regle.onNodeWithContentDescription(texte(R.string.home_sort_mode)).performClick()
        regle.waitForIdle()

        val entrees = textesCliquables() - avant
        assertThat(entrees).hasSize(NoteSortMode.entries.size)

        regle.onNodeWithText(entrees.last()).performClick()
        assertThat(trisChoisis).hasSize(1)
    }

    /** La saisie de recherche remonte frappe par frappe, et le bouton d'effacement la vide. */
    @Test
    fun le_champ_de_recherche_remonte_la_saisie_et_s_efface() {
        poser(HomeUiState(notes = emptyList(), loading = false))

        regle.onNodeWithText(texte(R.string.home_search_hint)).performTextInput("abc")
        assertThat(requetes).isNotEmpty()

        poser(HomeUiState(notes = emptyList(), loading = false, query = "abc"))
        regle.onNodeWithContentDescription(texte(R.string.search_clear)).performClick()
        assertThat(requetes.last()).isEmpty()
    }

    /**
     * Les trois sorties de la barre : tiroir, recherche, réglages.
     *
     * 🔴 **C'est ce test qui a trouvé le bouton mal nommé** (§73). Sa première version cliquait
     * l'icône décrite « Réglages » et attendait `onOpenSettings` ; l'appel n'arrivait pas, parce que
     * cette icône ouvre un **menu**. Le défaut n'était pas dans le câblage mais dans le **nom** : ⋮
     * s'annonçait comme la première de ses deux entrées.
     *
     * ⚠️ Les réglages passent donc par deux gestes, et le test les fait tous les deux — c'est le
     * chemin réel de l'utilisateur.
     */
    @Test
    fun les_trois_sorties_de_la_barre_remontent_a_l_appelant() {
        poser(HomeUiState(notes = emptyList(), loading = false))

        regle.onNodeWithContentDescription(texte(R.string.home_folders)).performClick()
        regle.onNodeWithContentDescription(texte(R.string.search_title)).performClick()

        regle.onNodeWithContentDescription(texte(R.string.common_more_options)).performClick()
        regle.waitForIdle()
        regle.onNodeWithText(texte(R.string.settings_title)).performClick()

        assertThat(tiroirsOuverts).hasSize(1)
        assertThat(recherchesOuvertes).hasSize(1)
        assertThat(reglagesOuverts).hasSize(1)
    }

    /**
     * ⚠️ Le badge de dossier n'apparaît **que** hors filtre de dossier — `showFolderBadge`. Le
     * vérifier ici plutôt que dans un test de `HomeUiState` mesure ce que l'écran en fait, pas ce
     * que la propriété rend.
     *
     * 🔴 Les deux cas sont **opposés**, et c'est tout l'intérêt : un test dont les deux moitiés
     * affirment la même chose ne distingue pas un badge correctement caché d'un badge jamais
     * affiché. La première version de ce test faisait exactement cette faute.
     *
     * ⚠️⚠️ **Le badge est visé par exclusion du titre, pas par un COMPTE.** Une version intermédiaire
     * concluait par `assertCountEquals(1)` sur le nom du dossier, en s'appuyant sur le fait que la
     * barre de titre le porte quand un dossier est actif. Les deux relectures externes du 2026-08-17
     * ont relevé la même faille : ce compte vaut aussi **1** si le titre disparaît **et** que le
     * badge s'affiche à tort — deux défauts qui s'annulent dans le total. Le titre porte
     * `Modifier.semantics { heading() }`, ce qui suffit à les séparer.
     */
    @Test
    fun le_badge_de_dossier_disparait_quand_la_liste_est_deja_filtree_par_dossier() {
        val notes = listOf(note("a", "Une note", dossier = DOSSIER_ID))
        val noms = mapOf(DOSSIER_ID to NOM_DOSSIER)
        val badge = hasText(NOM_DOSSIER) and !isHeading()
        val titre = hasText(NOM_DOSSIER) and isHeading()

        // Sans dossier courant : la note peut venir de n'importe où, le badge le dit — et le titre
        // porte alors le nom de l'application, donc aucun nœud « titre » ne porte ce nom.
        poser(HomeUiState(notes = notes, loading = false, currentFolder = null, folderNamesById = noms))
        regle.onNode(badge).assertIsDisplayed()
        regle.onNode(titre).assertDoesNotExist()

        // Dans un dossier : le titre porte le nom, et le badge ne le répète pas.
        poser(HomeUiState(notes = notes, loading = false, currentFolder = dossier(), folderNamesById = noms))
        regle.onNode(titre).assertIsDisplayed()
        regle.onNode(badge).assertDoesNotExist()
    }

    private fun note(id: String, titre: String, dossier: String = DOSSIER_ID): Note = Note(
        id = id,
        title = titre,
        content = "corps",
        folderId = dossier,
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

    private fun dossier(): Folder = Folder(
        id = DOSSIER_ID,
        name = NOM_DOSSIER,
        parentId = null,
        color = null,
        icon = null,
        createdAt = Instant.ofEpochMilli(HORODATAGE),
        updatedAt = Instant.ofEpochMilli(HORODATAGE),
        vault = null,
    )

    private companion object {
        const val DOSSIER_ID = "dossier-de-test"
        const val NOM_DOSSIER = "Dossier de test"
        const val PERDUES = 2
        const val HORODATAGE = 1_700_000_000_000L
    }
}
