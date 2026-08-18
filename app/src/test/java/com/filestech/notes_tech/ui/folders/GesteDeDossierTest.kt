package com.filestech.notes_tech.ui.folders

import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.VaultDescriptor
import com.filestech.notes_tech.domain.model.VaultMode
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **Les deux gestes qui déchiffrent un dossier prennent-ils la MÊME décision ?**
 *
 * Retirer la protection d'un coffre et le supprimer en gardant ses notes déchiffrent tous les deux
 * son contenu, donc exigent tous les deux une session ouverte. Ils étaient écrits séparément dans
 * `HomeRoute`, et un seul mémorisait son intention avant d'ouvrir la feuille de déverrouillage : la
 * suppression s'évaporait après que l'utilisateur ait tapé son secret.
 *
 * ⚠️⚠️ **Ce que ce fichier mesure, et ce qu'il ne mesure pas.** Il mesure que la **décision** est la
 * même pour tous les gestes — c'est le jumeau supprimé. Il ne mesure pas que `HomeRoute` passe bien
 * par elle : cette partie-là tient au fait qu'il n'existe plus qu'un seul chemin (`lancerLeGeste`) et
 * que le `when` qui exécute est exhaustif. Le dire vaut mieux que de laisser croire le contraire.
 *
 * 🔴 **Le filet contre la re-divergence est [etiquette]**, et pas les cas eux-mêmes : un troisième
 * geste ajouté à `GesteDeDossier` fera **échouer la compilation de ce fichier** tant que personne
 * n'aura décidé de sa reprise. C'est la seule forme qui survive à un remaniement futur.
 */
class GesteDeDossierTest {

    /**
     * ⚠️ `when` **exhaustif sur l'interface scellée** : c'est lui, et non les tests, qui empêche
     * qu'un troisième geste naisse sans qu'on décide de son déverrouillage.
     */
    private fun etiquette(geste: GesteDeDossier): String = when (geste) {
        is GesteDeDossier.RetirerLaProtection -> "retirer la protection"
        is GesteDeDossier.SupprimerEnGardantLesNotes -> "supprimer en gardant les notes"
    }

    private fun tousLesGestes(dossier: Folder): List<GesteDeDossier> = listOf(
        GesteDeDossier.RetirerLaProtection(dossier),
        GesteDeDossier.SupprimerEnGardantLesNotes(dossier),
    )

    @Test
    @DisplayName("un coffre FERME exige le deverrouillage, pour TOUS les gestes")
    fun un_coffre_ferme_exige_le_deverrouillage_pour_tous_les_gestes() {
        val exigeants = tousLesGestes(coffre())
            .filter { deverrouillageRequis(it, coffresOuverts = emptySet()) }
            .map(::etiquette)

        assertThat(exigeants)
            .containsExactly("retirer la protection", "supprimer en gardant les notes")
    }

    @Test
    @DisplayName("un coffre OUVERT n'exige rien, pour tous les gestes")
    fun un_coffre_ouvert_n_exige_rien() {
        val exigeants = tousLesGestes(coffre())
            .filter { deverrouillageRequis(it, coffresOuverts = setOf(ID)) }
            .map(::etiquette)

        assertThat(exigeants).isEmpty()
    }

    /**
     * ⚠️ **Un dossier ordinaire ne demande rien, même quand aucun coffre n'est ouvert.** La question
     * n'est pas « ce dossier est-il ouvert ? » mais « a-t-il quelque chose à ouvrir ? ». Les
     * confondre ferait demander un secret pour un dossier qui n'en a pas — et il n'y aurait aucune
     * feuille capable de le donner.
     */
    @Test
    @DisplayName("un dossier ORDINAIRE n'exige rien, meme sans aucun coffre ouvert")
    fun un_dossier_ordinaire_n_exige_rien() {
        val exigeants = tousLesGestes(dossierOrdinaire())
            .filter { deverrouillageRequis(it, coffresOuverts = emptySet()) }

        assertThat(exigeants).isEmpty()
    }

    /**
     * 🔴 **L'appartenance porte sur CE dossier, pas sur « un coffre quelconque est ouvert ».** Un
     * booléen global aurait laissé passer un geste destructeur sur un coffre fermé dès qu'un autre
     * était ouvert — et il y en a presque toujours un pendant qu'on manipule des dossiers.
     */
    @Test
    @DisplayName("un AUTRE coffre ouvert ne suffit pas")
    fun un_autre_coffre_ouvert_ne_suffit_pas() {
        val exigeants = tousLesGestes(coffre())
            .filter { deverrouillageRequis(it, coffresOuverts = setOf("un-autre-coffre")) }
            .map(::etiquette)

        assertThat(exigeants)
            .containsExactly("retirer la protection", "supprimer en gardant les notes")
    }

    /** Le dossier voyage **avec** le geste : c'est lui qu'on compare à celui que la feuille a ouvert. */
    @Test
    @DisplayName("chaque geste porte son dossier")
    fun chaque_geste_porte_son_dossier() {
        val cible = coffre()

        assertThat(tousLesGestes(cible).map { it.dossier.id }).containsExactly(ID, ID)
    }

    private fun dossierOrdinaire(vault: VaultDescriptor? = null) = Folder(
        id = ID,
        name = "Secrets",
        parentId = null,
        color = null,
        icon = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        vault = vault,
    )

    private fun coffre() = dossierOrdinaire(VaultDescriptor(mode = VaultMode.PIN, failedAttempts = 0))

    private companion object {
        const val ID = "secrets"
    }
}
