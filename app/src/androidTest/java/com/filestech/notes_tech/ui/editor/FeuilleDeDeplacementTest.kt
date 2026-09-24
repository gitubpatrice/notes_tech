package com.filestech.notes_tech.ui.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.VaultDescriptor
import com.filestech.notes_tech.domain.model.VaultMode
import com.filestech.notes_tech.ui.seuleLaPoigneeEstSansNom
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * **Le choix d'une destination, et ce que l'écran en dit.**
 *
 * Ligne `move_to_folder_sheet.dart` de `docs/05-PARITE.md`.
 *
 * ## 🔴 Le défaut trouvé en portant cette ligne
 *
 * La feuille signalait un dossier coffre par `note_card_locked` — « 🔒 Note verrouillée ». Cette
 * chaîne décrit une **carte de note** ; posée sous le nom d'un **dossier**, sur l'écran où l'on
 * choisit où envoyer une note, elle fait annoncer « Travail. Note verrouillée » pour une destination
 * qui n'est pas une note.
 *
 * L'application publiée, elle, ne signale le coffre que par une **icône** — et son propre
 * commentaire dit pourquoi le signal existe : *« l'utilisateur doit voir où il envoie sa note : la
 * destination n'était pas distinguable d'un dossier ordinaire »* (relevé par une relecture externe).
 * Une icône est invisible à un lecteur d'écran ; le portage tient donc la promesse **mieux** que le
 * publié, à condition de dire la bonne chose.
 *
 * `move_to_folder_vault` dit désormais la **conséquence** — la note sera chiffrée — qui est la raison
 * d'être du signal.
 *
 * ## ⚠️ Ce que cette feuille ne fait pas, et qui n'est donc pas ici
 *
 * Elle ne décide rien : elle rend un identifiant. Demander le secret d'un coffre **fermé** choisi
 * comme destination, et confirmer la **sortie** d'un coffre, se jouent chez l'appelant — les porter
 * ici ferait d'un sélecteur de dossier l'endroit où l'on retire une protection. Le chiffrement à
 * l'écriture est mesuré ailleurs, par `NotesRepositoryTest`.
 */
@RunWith(AndroidJUnit4::class)
class FeuilleDeDeplacementTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private var choisi: String? = null

    private fun poser(dossiers: List<Folder>, dossierActuel: String?) {
        regle.setContent {
            NotesTechTheme {
                FeuilleDeDeplacement(
                    dossiers = dossiers,
                    dossierActuel = dossierActuel,
                    onChoisir = { choisi = it },
                    onDismiss = {},
                )
            }
        }
        regle.waitForIdle()
    }

    private fun texte(id: Int) = regle.activity.getString(id)

    // ── Ce que la feuille propose ────────────────────────────────────────────────────────────────

    /**
     * 🔴 **Le dossier courant n'est pas une destination.**
     *
     * L'y « déplacer » ne ferait rien — le dépôt rend `false` sans rien changer — et le proposer
     * ferait croire à un geste qui n'a pas lieu.
     *
     * ⚠️ Le témoin est le dossier **voisin**, qui doit être là : sans lui, une feuille qui
     * n'afficherait aucune destination passerait ce cas.
     */
    @Test
    fun le_dossier_courant_n_est_pas_propose() {
        poser(dossiers = listOf(TRAVAIL, COURSES), dossierActuel = TRAVAIL.id)

        regle.onNodeWithText(TRAVAIL.name).assertDoesNotExist()
        regle.onNodeWithText(COURSES.name).assertIsDisplayed()
    }

    /**
     * 🔴🔴 **Un dossier coffre dit qu'il chiffrera, et ne se prétend pas une note.**
     *
     * Le cas exige les deux : la présence de la bonne phrase **et** l'absence de l'ancienne. Sans le
     * second volet, remettre `note_card_locked` à côté du nouveau texte passerait inaperçu.
     */
    @Test
    fun un_dossier_coffre_annonce_le_CHIFFREMENT_et_pas_une_note_verrouillee() {
        poser(dossiers = listOf(COFFRE, COURSES), dossierActuel = null)

        regle.onNodeWithText(texte(R.string.move_to_folder_vault)).assertIsDisplayed()
        regle.onNodeWithText(texte(R.string.note_card_locked)).assertDoesNotExist()
    }

    /**
     * ⚠️ Le contrôle négatif du cas ci-dessus : un dossier **ordinaire** ne porte aucune mention.
     *
     * Sans lui, une feuille qui collerait la mention sous chaque ligne passerait le cas précédent.
     */
    @Test
    fun un_dossier_ordinaire_ne_porte_aucune_mention() {
        poser(dossiers = listOf(COURSES), dossierActuel = null)

        regle.onNodeWithText(texte(R.string.move_to_folder_vault)).assertDoesNotExist()
    }

    /** Aucune destination : la feuille le dit, plutôt que de montrer une liste vide. */
    @Test
    fun sans_destination_la_feuille_le_dit() {
        poser(dossiers = listOf(TRAVAIL), dossierActuel = TRAVAIL.id)

        regle.onNodeWithText(texte(R.string.move_to_folder_empty)).assertIsDisplayed()
    }

    // ── Ce que la feuille rend ───────────────────────────────────────────────────────────────────

    /**
     * 🔴 **C'est l'identifiant qui remonte, pas le nom ni le rang.**
     *
     * Deux dossiers peuvent porter le même nom ; le rang, lui, change dès qu'un dossier est filtré.
     * Ce cas choisit le **second** de la liste, précisément pour qu'un appelant qui rendrait
     * `dossiers[position]` sur la liste non filtrée se trompe.
     */
    @Test
    fun choisir_rend_l_identifiant_du_dossier_touche() {
        poser(dossiers = listOf(TRAVAIL, COFFRE, COURSES), dossierActuel = TRAVAIL.id)

        regle.onNodeWithText(COURSES.name).performClick()

        assertThat(choisi).isEqualTo(COURSES.id)
    }

    // ── Ce que l'écran dit de lui-même ────────────────────────────────────────────────────────────

    /**
     * ⚠️ Aucun élément actionnable sans nom, **sauf la poignée de material3**.
     *
     * L'icône de cadenas est décorative (`contentDescription = null`) : c'est le texte qui doit
     * porter l'information. Un balayage propre ici veut donc dire que chaque ligne est nommée par le
     * nom de son dossier — et non par une icône muette.
     *
     * ⚠️⚠️ **L'exception n'affaiblit pas l'instrument.** `BottomSheetDefaults.DragHandle` pose deux
     * nœuds jumeaux, dont un porte `OnLongClick` **seul** et n'est donc pas nommable depuis
     * l'appelant ; le raisonnement complet est dans `AutocompletionTest`. Elle est ancrée sur la
     * poignée **mesurée** — le seul nœud de cette feuille qui porte `Dismiss` — et l'assertion reste
     * un `containsExactly` : tout autre actionnable muet la fait tomber.
     *
     * ✅ Effet de bord utile : ce cas **mesure** que la poignée est bien là. C'est ce que la ligne
     * `sheet_handle.dart` affirmait sans le vérifier autrement que par la lecture des sept appels.
     */
    @Test
    fun aucun_element_actionnable_n_est_sans_nom_sauf_la_poignee_de_material3() {
        poser(dossiers = listOf(COFFRE, COURSES), dossierActuel = null)

        regle.seuleLaPoigneeEstSansNom()
    }

    private companion object {
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

        val TRAVAIL = dossier("f-travail", "Travail")
        val COURSES = dossier("f-courses", "Courses")
        val COFFRE = dossier("f-coffre", "Secrets", VaultDescriptor(VaultMode.PIN, failedAttempts = 0))
    }
}
