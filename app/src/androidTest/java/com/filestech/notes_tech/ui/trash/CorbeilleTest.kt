package com.filestech.notes_tech.ui.trash

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.EncryptedBody
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.home.NoteCard
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * **Ce que la corbeille annonce, et ce qu'elle montre selon son état.**
 *
 * Deuxième ligne d'écran de `docs/05-PARITE.md`. `TrashScreen` a été rendu **sans état** pour ce
 * fichier — c'est le même découpage que `HomeScreen` / `HomeRoute`, et il a payé aussi vite :
 * l'état de **chargement** n'existait pas, et l'écran annonçait « La corbeille est vide » à
 * quelqu'un dont elle ne l'est pas, le temps que la base réponde.
 *
 * ## 🔴 Les trois questions de la phase 8, appliquées à cet écran
 *
 * 1. *Que reçoit un lecteur d'écran ?* → [aucun_element_actionnable_de_la_corbeille_n_est_sans_nom],
 *    et surtout [une_carte_de_corbeille_ne_s_annonce_pas_activable] : la carte était **cliquable
 *    pour rien**. §74.
 * 2. *Quels états ne sait-on pas atteindre à la main ?* → le chargement, qui dure une fraction de
 *    seconde sur un téléphone et qu'aucun pilotage manuel ne fige. §75.
 * 3. *Combien de tests ont été ignorés ?* → compté par les codes `-3` / `-4` de l'instrumentation,
 *    jamais lu dans le « OK (N tests) ». `04-PIEGES.md` §72.
 */
@RunWith(AndroidJUnit4::class)
class CorbeilleTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val retours = mutableListOf<Unit>()
    private val restaurees = mutableListOf<String>()
    private val detruites = mutableListOf<String>()
    private val vidanges = mutableListOf<Unit>()

    private fun texte(id: Int): String = regle.activity.getString(id)

    /**
     * ⚠️ **`setContent` ne s'appelle qu'UNE fois par activité** : les changements d'état passent par
     * ce `MutableState`, ce qui reproduit aussi ce que fait la production — une recomposition, pas
     * une reconstruction d'écran.
     */
    private val etatCourant = mutableStateOf(TrashUiState())

    private var pose = false

    private fun poser(etat: TrashUiState) {
        if (pose) {
            regle.runOnIdle { etatCourant.value = etat }
            regle.waitForIdle()
            return
        }
        etatCourant.value = etat
        pose = true
        regle.setContent {
            NotesTechTheme {
                TrashScreen(
                    state = etatCourant.value,
                    onBack = { retours += Unit },
                    onRestore = { restaurees += it },
                    onDeletePermanently = { detruites += it },
                    onEmptyTrash = { vidanges += Unit },
                )
            }
        }
        regle.waitForIdle()
    }

    /**
     * 🔴🔴 **L'état qui manquait — et il ne se voyait pas, il MENTAIT.**
     *
     * `stateIn` rend obligatoirement une valeur initiale : une liste **vide** avant que Room ait
     * répondu. L'écran en concluait « La corbeille est vide », affiché **et annoncé**, à quelqu'un
     * dont la corbeille contient des notes. `trash_screen.dart` distingue les deux depuis toujours —
     * son `items` vaut `null` tant que la lecture n'a pas rendu, et il montre alors un indicateur.
     *
     * ⚠️ Ce cas ne s'atteint pas en pilotant l'application : sur un téléphone la base répond en
     * quelques millisecondes, et ce qu'on voit passer ressemble à un scintillement. Il n'est
     * observable que parce que l'écran est devenu sans état.
     *
     * ⚠️ L'indicateur d'activité est vérifié **présent**, pas seulement le message vérifié absent :
     * « ne dit pas vide » et « dit qu'il travaille » sont deux exigences, et un écran blanc satisfait
     * la première.
     */
    @Test
    fun pendant_le_chargement_l_ecran_ne_pretend_pas_que_la_corbeille_est_vide() {
        poser(TrashUiState(notes = emptyList(), loading = true))

        regle.onNodeWithText(texte(R.string.trash_empty_title)).assertDoesNotExist()
        regle.onNodeWithText(texte(R.string.trash_empty_subtitle)).assertDoesNotExist()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertIsDisplayed()

        // Le témoin : une fois la réponse arrivée, le message vide paraît bien et l'indicateur part.
        // Sans lui, le test ci-dessus passerait tout aussi bien si la chaîne n'existait nulle part
        // dans cet écran.
        poser(TrashUiState(notes = emptyList(), loading = false))
        regle.onNodeWithText(texte(R.string.trash_empty_title)).assertIsDisplayed()
        regle.onNode(INDICATEUR_D_ACTIVITE).assertDoesNotExist()
    }

    /**
     * 🔴🔴 **Le test qui mesure vraiment la garde `!state.loading`, et le précédent ne le faisait pas.**
     *
     * Relevé par une relecture externe (GPT-5.2, 2026-08-17) : vérifier l'absence du bouton de
     * vidange sur un état `loading = true` **et une liste vide** ne prouve rien du tout — le bouton
     * dépend aussi de `notes.isNotEmpty()`, donc il est absent quelle que soit la garde. L'assertion
     * passait avec `!state.loading` **et sans**.
     *
     * Le seul état qui discrimine est celui-ci : **des notes ET un chargement en cours**. Il
     * correspond exactement au défaut décrit — une action destructrice qui surgit sous le doigt au
     * moment où la base répond.
     *
     * ⚠️ C'est la même faute que celle du témoin de carte deux tests plus haut, sous une autre forme :
     * *une assertion négative sur un état où le vrai et le faux donnent le même résultat.*
     */
    @Test
    fun le_bouton_de_vidange_reste_cache_pendant_le_chargement_meme_avec_des_notes() {
        val vidange = texte(R.string.trash_empty_all)
        val notes = listOf(note("a", TITRE))

        poser(TrashUiState(notes = notes, loading = true))
        regle.onNodeWithContentDescription(vidange).assertDoesNotExist()
        // Les cartes non plus : l'écran ne montre pas une liste dont il ne sait pas si elle est
        // complète. C'est ce qui distingue « je charge » de « voici tout ce qu'il y a ».
        regle.onNode(hasContentDescription(TITRE, substring = true)).assertDoesNotExist()

        poser(TrashUiState(notes = notes, loading = false))
        regle.onNodeWithContentDescription(vidange).assertIsDisplayed()
        regle.onNode(hasContentDescription(TITRE, substring = true)).assertIsDisplayed()
    }

    /**
     * 🔴 **Une carte de corbeille ne s'ouvre pas — elle ne doit donc pas s'annoncer activable.**
     *
     * Le portage passait `onClick = { }` à [NoteCard]. Une lambda vide n'est pas « pas de clic » :
     * `clickable` pose alors une action `OnClick` dans l'arbre de sémantique, et un lecteur d'écran
     * annonce « double-touchez pour activer » sur une carte inerte. `trash_screen.dart` rend sa
     * tuile en `ListTile` **sans `onTap`**. §74.
     *
     * ⚠️ La carte est visée par sa **description**, qui est le seul nœud fusionné qu'elle expose —
     * `NoteCard` porte un `semantics(mergeDescendants = true)` pour qu'un balayage lise « titre,
     * date, dossier » d'un coup au lieu d'égrener quatre éléments.
     */
    @Test
    fun une_carte_de_corbeille_ne_s_annonce_pas_activable() {
        poser(TrashUiState(notes = listOf(note("a", TITRE)), loading = false))

        regle.onNode(hasContentDescription(TITRE, substring = true)).assertHasNoClickAction()
    }

    /**
     * ⚠️⚠️ **Le témoin du test précédent, et il est indispensable.**
     *
     * `assertHasNoClickAction` passe aussi bien sur une carte correctement inerte que sur une carte
     * que la recherche n'a pas trouvée du tout, ou sur un `NoteCard` devenu incapable de porter un
     * clic. La **même** carte, posée avec un `onClick` non nul, doit donc être trouvée **et**
     * cliquable par exactement la même expression.
     *
     * C'est la leçon du `grep` ancré par `$` du 2026-08-17 : une assertion négative ne vaut rien
     * sans un positif connu mesuré par le même instrument.
     */
    @Test
    fun la_meme_carte_posee_avec_un_clic_est_bien_cliquable() {
        val clics = mutableListOf<Unit>()
        regle.setContent {
            NotesTechTheme {
                NoteCard(note = note("a", TITRE), onClick = { clics += Unit })
            }
        }
        regle.waitForIdle()

        val carte = regle.onNode(hasContentDescription(TITRE, substring = true))
        carte.assertHasClickAction()

        // ⚠️ **Le rôle aussi.** Sans lui, TalkBack dit la description puis « double-touchez pour
        // activer », sans jamais nommer ce que c'est. Relevé par les deux relectures externes du
        // 2026-08-17, et posé par le `role` du `clickable` — donc sur ce nœud-ci, pas un autre.
        assertThat(carte.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
            .isEqualTo(Role.Button)

        carte.performClick()
        assertThat(clics).hasSize(1)
    }

    /**
     * 🔴 **Les étiquettes sont annoncées, et elles ne l'étaient pas.**
     *
     * Un nœud fusionné qui porte une `contentDescription` explicite **remplace** la lecture de ses
     * enfants : mesuré sur le S9, le nœud de la carte ne porte **aucune** propriété `Text`. Tout ce
     * qui n'est pas dans la chaîne construite n'existe donc pas pour un lecteur d'écran — et
     * `note.tags` n'y était pas. `#urgent` s'affichait à qui voit, et se taisait pour qui écoute.
     * Relevé par une relecture externe (Gemini, 2026-08-17) ; le défaut **préexistait** au correctif
     * de §74.
     */
    @Test
    fun les_etiquettes_d_une_note_en_clair_sont_annoncees() {
        poser(TrashUiState(notes = listOf(note("a", TITRE, listOf("urgent", "impots"))), loading = false))

        regle.onNode(hasContentDescription("#urgent #impots", substring = true)).assertIsDisplayed()
    }

    /**
     * Le balayage mécanique de l'écran, avec la liste **remplie** : c'est l'état qui porte le plus
     * d'actionnables — retour, vidange, et deux boutons par note.
     *
     * Cf. `ui/BalayageDAccessibilite.kt` et son témoin.
     */
    @Test
    fun aucun_element_actionnable_de_la_corbeille_n_est_sans_nom() {
        poser(TrashUiState(notes = listOf(note("a", TITRE), note("b", "Une autre")), loading = false))

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    /**
     * 🔴 **Une note de coffre reste scellée dans la corbeille.** La corbeille ne déchiffre rien : la
     * carte doit dire « Note verrouillée » et **pas** le titre en clair qu'elle porte encore en
     * base — celui d'une note chiffrée avant la 2.0.0 peut y avoir survécu (cf. le correctif
     * `listPlaintextInFolder` de `05-PARITE.md`).
     *
     * ⚠️ Le titre en clair est mis dans le vecteur **exprès** : un test sur une note au titre vide
     * ne distinguerait pas « masqué » de « rien à masquer ».
     */
    @Test
    fun une_note_de_coffre_ne_montre_pas_son_titre_dans_la_corbeille() {
        val scellee = note("a", TITRE, listOf("medical", "divorce"))
            .copy(encrypted = EncryptedBody(ByteArray(32)))
        poser(TrashUiState(notes = listOf(scellee), loading = false))

        regle.onNodeWithText(texte(R.string.note_card_locked)).assertIsDisplayed()
        regle.onNodeWithText(TITRE).assertDoesNotExist()
        regle.onNode(hasContentDescription(TITRE, substring = true)).assertDoesNotExist()

        // 🔴🔴 **Les étiquettes non plus, et c'est le vrai enjeu de ce test.** « Note verrouillée,
        // #medical, #divorce » n'a rien protégé. Le vecteur en porte donc exprès deux, et le
        // correctif qui a fait entrer les étiquettes dans la description a dû reproduire la garde
        // `!verrouillee` de la branche visuelle — sans quoi il aurait ouvert une fuite en fermant
        // un défaut d'accessibilité.
        regle.onNode(hasContentDescription("#medical", substring = true)).assertDoesNotExist()
        regle.onNode(hasContentDescription("#divorce", substring = true)).assertDoesNotExist()
    }

    /**
     * Restaurer remonte **l'identifiant de la note touchée**, pas celui d'une autre ligne.
     *
     * ⚠️ Deux notes, et c'est ce qui compte : avec une seule, un écran qui remonterait toujours la
     * première serait indiscernable d'un écran correct.
     */
    @Test
    fun restaurer_remonte_l_identifiant_de_la_note_touchee() {
        poser(TrashUiState(notes = listOf(note("a", "Première"), note("b", "Seconde")), loading = false))

        // ⚠️ Le compte est vérifié **avant** de viser par indice : un `[1]` sur une liste dont la
        // structure a changé désignerait silencieusement autre chose, et le test resterait vert en
        // mesurant le mauvais bouton.
        val boutons = regle.onAllNodesWithText(texte(R.string.common_restore))
        boutons.assertCountEquals(2)
        boutons[1].performClick()

        assertThat(restaurees).containsExactly("b")
    }

    /**
     * 🔴 **La destruction définitive passe par une confirmation, et ANNULER ne détruit rien.**
     *
     * Le second point est celui qui vaut d'être mesuré : un dialogue dont le bouton d'annulation
     * appellerait quand même l'action serait un dialogue décoratif.
     *
     * ⚠️⚠️ **`hasClickAction()` fait partie du sélecteur, ce n'est pas une ceinture de plus.** Le
     * dialogue de vidange porte son libellé **deux fois** — son titre **et** son bouton de
     * confirmation sont la même chaîne `trash_empty_all`. Sans cette clause, le sélecteur désigne deux
     * nœuds et `onNode` échoue. Relevé par une relecture externe (Gemini, 2026-08-17) sur la version
     * précédente de ce test, qui visait par indice.
     *
     * ⚠️⚠️ **Le bouton de confirmation est visé DANS le dialogue**, par `hasAnyAncestor(isDialog())`,
     * et non par l'indice `[1]` d'une recherche sur le libellé. Les deux fonctionnent aujourd'hui —
     * la liste et le dialogue portent le même texte — mais l'indice repose sur l'ordre de parcours de
     * l'arbre, qui n'est garanti par rien ici : le jour où une carte de plus s'ajoute au vecteur, le
     * test cliquerait le bouton d'une **note** en croyant confirmer un dialogue, et resterait vert.
     */
    @Test
    fun la_destruction_definitive_demande_confirmation_et_annuler_ne_detruit_rien() {
        poser(TrashUiState(notes = listOf(note("a", TITRE)), loading = false))
        val confirmation = hasText(texte(R.string.trash_delete_forever)) and
            hasAnyAncestor(isDialog()) and hasClickAction()

        regle.onNodeWithText(texte(R.string.trash_delete_forever)).performClick()
        regle.waitForIdle()
        regle.onNodeWithText(texte(R.string.trash_delete_forever_title)).assertIsDisplayed()

        regle.onNodeWithText(texte(R.string.common_cancel)).performClick()
        regle.waitForIdle()
        assertThat(detruites).isEmpty()
        regle.onNodeWithText(texte(R.string.trash_delete_forever_title)).assertDoesNotExist()
        regle.onNode(confirmation).assertDoesNotExist()

        // Puis le chemin qui aboutit : sans lui, le test ne distinguerait pas « annuler protège » de
        // « le bouton de confirmation ne marche pas ».
        regle.onNodeWithText(texte(R.string.trash_delete_forever)).performClick()
        regle.waitForIdle()
        regle.onNode(confirmation).performClick()

        assertThat(detruites).containsExactly("a")
    }

    /** Vider la corbeille : même exigence, et le libellé du dialogue le dit avant d'agir. */
    @Test
    fun vider_la_corbeille_demande_confirmation() {
        poser(TrashUiState(notes = listOf(note("a", TITRE)), loading = false))
        val confirmation = hasText(texte(R.string.trash_empty_all)) and
            hasAnyAncestor(isDialog()) and hasClickAction()

        regle.onNodeWithContentDescription(texte(R.string.trash_empty_all)).performClick()
        regle.waitForIdle()
        regle.onNodeWithText(texte(R.string.trash_empty_all_confirm)).assertIsDisplayed()

        regle.onNodeWithText(texte(R.string.common_cancel)).performClick()
        regle.waitForIdle()
        assertThat(vidanges).isEmpty()

        regle.onNodeWithContentDescription(texte(R.string.trash_empty_all)).performClick()
        regle.waitForIdle()
        regle.onNode(confirmation).performClick()

        assertThat(vidanges).hasSize(1)
    }

    /**
     * La rétention est annoncée avec **le nombre de jours réel**, pas un gabarit.
     *
     * ⚠️ `RETENTION_DAYS` est une constante d'affichage dérivée de `TRASH_RETENTION_MILLIS` : si les
     * deux divergent, l'écran annonce une durée que la purge ne respecte pas. Ce test fige la
     * chaîne ; la concordance des deux valeurs est vérifiée côté JVM.
     */
    @Test
    fun la_duree_de_retention_est_annoncee_avec_sa_valeur_reelle() {
        val attendu = regle.activity.getString(R.string.trash_retention_notice, TrashViewModel.RETENTION_DAYS)
        poser(TrashUiState(notes = emptyList(), loading = false))

        regle.onNodeWithText(attendu).assertIsDisplayed()
    }

    /** Le bouton de fermeture remonte, et il s'annonce — il est le seul moyen de sortir. */
    @Test
    fun le_bouton_de_fermeture_remonte_a_l_appelant() {
        poser(TrashUiState(notes = emptyList(), loading = false))

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
        trashedAt = Instant.ofEpochMilli(HORODATAGE),
        createdAt = Instant.ofEpochMilli(HORODATAGE),
        updatedAt = Instant.ofEpochMilli(HORODATAGE),
        encrypted = null,
        encVersion = 1,
    )

    private companion object {
        /**
         * L'indicateur d'activité de material3 n'expose ni texte ni description : il se reconnaît à
         * sa `ProgressBarRangeInfo`, que `CircularProgressIndicator` pose en mode indéterminé.
         */
        val INDICATEUR_D_ACTIVITE = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

        const val TITRE = "Le titre de la note"
        const val HORODATAGE = 1_700_000_000_000L
    }
}
