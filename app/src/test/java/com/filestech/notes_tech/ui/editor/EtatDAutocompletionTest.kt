package com.filestech.notes_tech.ui.editor

import com.filestech.notes_tech.domain.model.Note
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **L'invariant du défaut §84, mesuré là où il est calculé.**
 *
 * `FeuilleDAutocompletion` est un `ModalBottomSheet` : un test d'écran y pose **un** état et vérifie ce
 * que la feuille en fait. Il ne balaie pas les combinaisons, et c'est ici qu'elles se disent — comme
 * pour la recherche (§76), dont ce fichier est la transposition.
 *
 * ⚠️ Le cas qui compte est le **troisième** : une réponse qui ne répond pas à la saisie courante ne
 * doit ni proposer de créer, ni annoncer qu'il n'y a rien, ni laisser une validation créer un homonyme.
 */
class EtatDAutocompletionTest {

    @Test
    @DisplayName("une reponse a jour sans correspondance propose de creer")
    fun reponse_a_jour_sans_correspondance() {
        val etat = etatDAutocompletion("Beta", SuggestionsDeLien(pour = "Beta", titres = listOf(note("Alpha"))))

        assertThat(etat.enAttente).isFalse()
        assertThat(etat.proposerLaCreation).isTrue()
        assertThat(etat.annoncerAucunResultat).isFalse()
        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Creer("Beta"))
    }

    /**
     * 🔴🔴 **Le défaut lui-même.** La réponse répond à `Alph`, la saisie est déjà `Alpha` : on ne sait
     * pas encore si « Alpha » existe. L'ancien code n'avait aucun moyen de le dire — sa liste vide
     * valait « il n'y a rien » — donc il proposait **de créer**, et sa validation créait.
     */
    @Test
    @DisplayName("une reponse en retard n'affirme rien et RETIENT la validation")
    fun reponse_en_retard() {
        val etat = etatDAutocompletion("Alpha", SuggestionsDeLien(pour = "Alph", titres = emptyList()))

        assertThat(etat.enAttente).isTrue()
        assertThat(etat.proposerLaCreation).isFalse()
        assertThat(etat.annoncerAucunResultat).isFalse()
        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Attendre)
    }

    /**
     * 🔴 **Et c'est bien le doublon qui est évité** : la même saisie, une fois la réponse arrivée,
     * **lie** la note existante au lieu d'en créer une seconde du même nom.
     *
     * C'est la divergence délibérée avec l'application publiée, dont `_onSubmit` crée toujours. Sans le
     * cas précédent, elle serait sans effet dans les 120 ms qui suivent une frappe — c'est-à-dire au
     * moment précis où l'on appuie sur « Entrée ».
     */
    @Test
    @DisplayName("la reponse arrivee LIE la note existante au lieu d'en creer un homonyme")
    fun reponse_arrivee_avec_correspondance() {
        val etat = etatDAutocompletion("alpha", SuggestionsDeLien(pour = "alpha", titres = listOf(note("Alpha"))))

        assertThat(etat.proposerLaCreation).isFalse()
        // ⚠️ Le titre lié est celui de la **note**, pas celui qui a été tapé : l'appariement ignore la
        // casse, et `[[alpha]]` vers une note « Alpha » ferait un lien qui s'affiche autrement.
        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Lier("Alpha"))
    }

    /** L'appariement ignore aussi les diacritiques — c'est `TitleNormalizer` qui en décide. */
    @Test
    @DisplayName("l'appariement ignore les diacritiques")
    fun appariement_insensible_aux_diacritiques() {
        val etat = etatDAutocompletion("impots", SuggestionsDeLien(pour = "impots", titres = listOf(note("Impôts"))))

        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Lier("Impôts"))
    }

    /**
     * ⚠️ **Une saisie vide reçoit une vraie réponse, et n'attend pas.**
     *
     * `pour` est nullable pour que `null` veuille dire « aucune réponse » — mais la chaîne **vide** est
     * une réponse légitime, celle qu'on donne sans chercher. Sans cette distinction, la feuille
     * afficherait un indicateur d'activité sur un champ vierge.
     */
    @Test
    @DisplayName("une saisie vide annonce l'absence de resultat, et n'attend rien")
    fun saisie_vide() {
        val etat = etatDAutocompletion("", SuggestionsDeLien(pour = "", titres = emptyList()))

        assertThat(etat.enAttente).isFalse()
        assertThat(etat.proposerLaCreation).isFalse()
        assertThat(etat.annoncerAucunResultat).isTrue()
        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Rien)
    }

    /**
     * ⚠️ **L'ouverture de la feuille non plus n'attend rien** : `pour = null` sur une saisie vide.
     *
     * C'est l'état de la toute première image, avant que le ViewModel n'ait émis quoi que ce soit. Un
     * indicateur d'activité y serait faux, et un « Aucun résultat » est ce que la version publiée
     * affiche au même instant.
     */
    @Test
    @DisplayName("a l'ouverture, aucune reponse et aucune saisie : rien n'est en attente")
    fun ouverture_de_la_feuille() {
        val etat = etatDAutocompletion("", SuggestionsDeLien())

        assertThat(etat.enAttente).isFalse()
        assertThat(etat.annoncerAucunResultat).isTrue()
    }

    /**
     * ⚠️ **La comparaison porte sur la saisie BRUTE.** Un espace en fin de frappe — fréquent — ne doit
     * pas faire croire la réponse périmée pour toujours : le ViewModel reçoit la saisie telle quelle,
     * donc il répond `"Alpha "`, et c'est à `"Alpha "` qu'il faut comparer.
     */
    @Test
    @DisplayName("un espace de fin ne fait pas croire la reponse perimee")
    fun saisie_avec_espace_final() {
        val etat = etatDAutocompletion("Alpha ", SuggestionsDeLien(pour = "Alpha ", titres = emptyList()))

        assertThat(etat.enAttente).isFalse()
        assertThat(etat.requete).isEqualTo("Alpha")
        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Creer("Alpha"))
    }

    /**
     * 🔴 **A failed search offers nothing, not even creation, and Enter does nothing** (GPT-5.6 review,
     * 2026-09-25). Answered as an empty list, it offered to create the note and Enter created it: in
     * an open vault holding "Codes" and a note it cannot read, a second "Codes". The sheet says why.
     */
    @Test
    @DisplayName("une recherche en echec ne propose rien, pas meme de creer, et Entree ne fait rien")
    fun recherche_en_echec() {
        val etat = etatDAutocompletion("Codes", SuggestionsDeLien(pour = "Codes", echec = true))

        assertThat(etat.annoncerLEchec).isTrue()
        assertThat(etat.proposerLaCreation).isFalse()
        assertThat(etat.annoncerAucunResultat).isFalse()
        assertThat(etat.enAttente).isFalse()
        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Rien)
    }

    /** A failure answers its own input only: typed on, the sheet waits for the next answer. */
    @Test
    @DisplayName("un echec pour une saisie depassee est une attente, pas un echec")
    fun echec_pour_une_saisie_depassee() {
        val etat = etatDAutocompletion("Codes 2", SuggestionsDeLien(pour = "Codes", echec = true))

        assertThat(etat.annoncerLEchec).isFalse()
        assertThat(etat.enAttente).isTrue()
        assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Attendre)
    }

    /**
     * Le balayage d'**exclusivité** : sur toutes les combinaisons, jamais deux affirmations
     * contradictoires à l'écran en même temps.
     *
     * ⚠️ Ce sont les trois branches d'affichage de la feuille, et elles doivent rester **mutuellement
     * exclusives** : l'écran s'appuie sur cette exclusivité pour les ordonner. Un état qui en porterait
     * deux ferait dépendre l'affichage de l'ordre des `when`, c'est-à-dire d'un détail d'écriture.
     * Four since 2026-09-25: a failed search is a branch of its own.
     */
    @Test
    @DisplayName("balayage : attente, creation, « aucun resultat » et echec sont mutuellement exclusifs")
    fun exclusivite_des_trois_branches() {
        val saisies = listOf("", " ", "Alpha", "Alpha ", "Beta")
        val reponses = listOf(
            SuggestionsDeLien(),
            SuggestionsDeLien(pour = ""),
            SuggestionsDeLien(pour = "Alpha"),
            SuggestionsDeLien(pour = "Alpha", titres = listOf(note("Alpha"))),
            SuggestionsDeLien(pour = "Beta", titres = listOf(note("Alpha"))),
            SuggestionsDeLien(pour = "Alpha", echec = true),
        )

        for (saisie in saisies) {
            for (reponse in reponses) {
                val etat = etatDAutocompletion(saisie, reponse)
                val affirmations = listOf(
                    etat.enAttente,
                    etat.proposerLaCreation,
                    etat.annoncerAucunResultat,
                    etat.annoncerLEchec,
                )

                assertThat(affirmations.count { it }).isAtMost(1)
                // Et une validation ne peut jamais créer alors qu'on attend encore : c'est le doublon.
                if (etat.enAttente) {
                    assertThat(decisionDeValidation(etat)).isEqualTo(DecisionDeValidation.Attendre)
                }
            }
        }
    }

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
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        encrypted = null,
        encVersion = 1,
    )
}
