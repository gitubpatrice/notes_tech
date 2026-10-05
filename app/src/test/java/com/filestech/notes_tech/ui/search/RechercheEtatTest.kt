package com.filestech.notes_tech.ui.search

import com.filestech.notes_tech.domain.model.Note
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **L'invariant du défaut §76, mesuré là où il est calculé.**
 *
 * `SearchRepository` et `FoldersRepository` sont des classes concrètes bâties sur un
 * `DatabaseProvider` : le ViewModel entier n'est pas exerçable hors appareil. La transformation qui
 * fabrique l'état, elle, est pure — et c'est elle qui décide si l'écran dit « aucun résultat » ou
 * « je cherche ».
 *
 * ⚠️⚠️ **Sans ce fichier, `RechercheTest` serait le seul, et il pose `searching` à la main.** Il
 * prouve donc ce que l'écran fait d'un état, jamais que quelque chose produit cet état. C'est
 * exactement la forme de test vacant relevée la veille : *vrai sur ce qu'il mesure, muet sur ce qui
 * compte.*
 */
class RechercheEtatTest {

    @Test
    @DisplayName("une issue qui repond a la saisie courante ne cherche plus")
    fun issue_a_jour() {
        val etat = etatDeRecherche("impots", Issue(pour = "impots", resultats = listOf(note("a"))), NOMS)

        assertThat(etat.searching).isFalse()
        assertThat(etat.failed).isFalse()
        assertThat(etat.results).hasSize(1)
        assertThat(etat.query).isEqualTo("impots")
        assertThat(etat.folderNamesById).isEqualTo(NOMS)
    }

    /**
     * 🔴🔴 **Le défaut lui-même.** L'issue répond à `impots`, la saisie est déjà `impots 2024` : rien
     * ne permet encore de dire s'il y a des résultats. L'ancien code rendait `searching = false` par
     * omission, et l'écran en concluait « Aucun résultat. Essayez un autre mot-clé ».
     */
    @Test
    @DisplayName("une issue qui repond a une saisie PRECEDENTE cherche encore")
    fun issue_en_retard() {
        val etat = etatDeRecherche("impots 2024", Issue(pour = "impots", resultats = emptyList()), NOMS)

        assertThat(etat.searching).isTrue()
    }

    /**
     * ⚠️ L'état d'ouverture, et celui d'après une rotation : **aucune réponse pour aucune requête**.
     * `null` ne peut être égal à aucune saisie, pas même vide — ce qui est la sémantique voulue et la
     * raison pour laquelle `pour` est nullable plutôt que vide par défaut.
     */
    @Test
    @DisplayName("une requete restauree sans aucune reponse s'ouvre en recherche")
    fun requete_restauree() {
        assertThat(etatDeRecherche("impots", Issue(), NOMS).searching).isTrue()
    }

    /**
     * ⚠️ Et le pendant : une saisie **vide** ne cherche rien, même sans réponse en main. Sans cette
     * garde, l'écran d'accueil porterait un indicateur d'activité perpétuel.
     */
    @Test
    @DisplayName("une saisie vide ne cherche jamais, meme sans reponse")
    fun saisie_vide() {
        assertThat(etatDeRecherche("", Issue(), NOMS).searching).isFalse()
        assertThat(etatDeRecherche("   ", Issue(), NOMS).searching).isFalse()
    }

    /**
     * 🔴 **L'échec d'une requête abandonnée ne survit pas à la frappe suivante.** Il ne dit rien de
     * la requête courante, et l'afficher sous elle accuserait une panne passée d'un problème présent.
     */
    @Test
    @DisplayName("un echec qui porte une AUTRE saisie n'est pas retenu")
    fun echec_perime() {
        val etat = etatDeRecherche("impots 2024", Issue(pour = "impots", echec = true), NOMS)

        assertThat(etat.failed).isFalse()
        assertThat(etat.searching).isTrue()
    }

    /** Le témoin du précédent : le même échec, pour la saisie courante, est bien retenu. */
    @Test
    @DisplayName("un echec qui porte la saisie courante est retenu")
    fun echec_courant() {
        val etat = etatDeRecherche("impots", Issue(pour = "impots", echec = true), NOMS)

        assertThat(etat.failed).isTrue()
        assertThat(etat.searching).isFalse()
    }

    /**
     * ⚠️⚠️ **L'exclusivité, énoncée comme telle et sur tous les cas d'un balayage.**
     *
     * `failed` et `searching` ne doivent jamais valoir `true` ensemble : tant qu'on ne sait pas, on ne
     * peut pas savoir non plus que ça a échoué. L'écran s'appuie sur cette exclusivité pour ordonner
     * ses branches, donc elle vaut d'être figée ici plutôt que d'être déduite de deux tests.
     */
    @Test
    @DisplayName("failed et searching ne sont JAMAIS vrais ensemble")
    fun exclusivite() {
        val saisies = listOf("", "  ", "impots", "impots 2024")
        val issues = listOf(
            Issue(),
            Issue(pour = ""),
            Issue(pour = "impots"),
            Issue(pour = "impots", echec = true),
            Issue(pour = "impots 2024", echec = true),
            Issue(pour = "impots 2024", resultats = listOf(note("a"))),
        )

        for (saisie in saisies) {
            for (issue in issues) {
                val etat = etatDeRecherche(saisie, issue, NOMS)
                assertThat(etat.failed && etat.searching).isFalse()
            }
        }
    }

    private fun note(id: String): Note = Note(
        id = id,
        title = "Un titre",
        content = "corps",
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
        val NOMS = mapOf("dossier" to "Dossier de test")
        const val HORODATAGE = 1_700_000_000_000L
    }
}
