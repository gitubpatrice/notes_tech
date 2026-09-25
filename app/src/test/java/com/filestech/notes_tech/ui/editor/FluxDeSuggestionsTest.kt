package com.filestech.notes_tech.ui.editor

import app.cash.turbine.test
import com.filestech.notes_tech.domain.model.Note
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **Le contrat d'émission des suggestions, mesuré — il n'était qu'écrit.**
 *
 * Deux fragilités relevées par une relecture externe (GPT-5.2, 2026-08-17) après la correction de §84
 * n'avaient été **que documentées**, faute de pouvoir les exercer : `NoteEditorViewModel` demande une
 * base, un coffre et Hilt, et les tests d'écran **injectent** la réponse, donc ils court-circuitent
 * précisément ce qu'il fallait vérifier.
 *
 * 1. **le contrat `pour == saisie` porte sur la saisie BRUTE.** Le jour où quelqu'un élaguerait la
 *    requête avant de la réémettre, `repondALaSaisie` serait faux **pour toujours** : la feuille
 *    attendrait indéfiniment et une validation retenue ne partirait jamais. Figé par
 *    [la_reponse_porte_la_saisie_BRUTE] ;
 * 2. **l'ordre d'émission n'était couvert par rien.** Figé par les trois autres cas.
 *
 * ⚠️ *Une fragilité qu'on sait seulement écrire est une fragilité qu'on ne saura pas voir revenir.*
 * L'extraction de `fluxDeSuggestions` hors du ViewModel n'a pas d'autre but que celui-là.
 */
class FluxDeSuggestionsTest {

    /**
     * 🔴🔴 **Le contrat : la réponse porte la saisie TELLE QU'ELLE A ÉTÉ REÇUE.**
     *
     * L'espace final n'est pas décoratif dans ce test : c'est le cas le plus probable d'une
     * normalisation involontaire — quelqu'un ajoute un `trim()` « pour faire propre », et la feuille
     * reste en attente jusqu'à la fin des temps parce que `pour` ne vaut plus jamais la saisie.
     */
    @Test
    @DisplayName("la reponse porte la saisie BRUTE, espace final compris")
    fun la_reponse_porte_la_saisie_BRUTE() = runTest {
        val saisies = MutableStateFlow("Alpha ")

        saisies.fluxDeSuggestions(FREINAGE) { listOf(note("Alpha")) }.test {
            assertThat(awaitItem()).isEqualTo(SuggestionsDeLien(pour = null))

            val reponse = awaitItem()
            assertThat(reponse.pour).isEqualTo("Alpha ")
            assertThat(reponse.titres).hasSize(1)

            // Et c'est bien ce que la fonction d'état attend : sans l'égalité brute, on attendrait
            // encore. Les deux moitiés du contrat sont vérifiées ensemble, sinon chacune peut dériver.
            assertThat(etatDAutocompletion("Alpha ", reponse).enAttente).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * 🔴 **`pour = null` part AVANT l'attente**, de façon synchrone avec la frappe.
     *
     * C'est ce qui fait disparaître la liste précédente tout de suite. Sans cette émission, on
     * pourrait toucher une proposition calculée pour la requête d'avant — le défaut corrigé le
     * 2026-08-15, dont ce test est le filet.
     */
    @Test
    @DisplayName("une nouvelle saisie vide la liste AVANT d'attendre")
    fun la_liste_part_a_vide_avant_le_freinage() = runTest {
        val saisies = MutableStateFlow("Alpha")

        saisies.fluxDeSuggestions(FREINAGE) { listOf(note("Alpha")) }.test {
            // La première émission ne coûte aucun temps virtuel : elle précède le `delay`.
            assertThat(awaitItem()).isEqualTo(SuggestionsDeLien(pour = null))
            assertThat(currentTime).isEqualTo(0L)

            assertThat(awaitItem().pour).isEqualTo("Alpha")
            assertThat(currentTime).isEqualTo(FREINAGE)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * ⚠️ **Une saisie vide reçoit sa réponse SANS chercher, et sans attendre.**
     *
     * Le second point se mesure au temps virtuel ; le premier au fait que la recherche n'est jamais
     * appelée — une requête vide qui frapperait la base à chaque ouverture de feuille serait un coût
     * gratuit sur une base chiffrée.
     */
    @Test
    @DisplayName("une saisie vide repond tout de suite et ne cherche pas")
    fun une_saisie_vide_ne_cherche_pas() = runTest {
        val saisies = MutableStateFlow("")
        val appels = mutableListOf<String>()

        saisies.fluxDeSuggestions(FREINAGE) { requete ->
            appels += requete
            emptyList()
        }.test {
            assertThat(awaitItem()).isEqualTo(SuggestionsDeLien(pour = null))
            assertThat(awaitItem()).isEqualTo(SuggestionsDeLien(pour = ""))
            assertThat(currentTime).isEqualTo(0L)
            assertThat(appels).isEmpty()

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * 🔴🔴 **Une frappe pendant le freinage ANNULE la recherche en cours.**
     *
     * C'est tout l'objet de `transformLatest`, et c'est ce qui garantit qu'une seule requête atteint
     * la base par salve de frappe. L'assertion porte sur **la liste des appels**, pas sur leur
     * nombre : « une seule recherche » et « la recherche de la DERNIÈRE saisie » sont deux exigences,
     * et un compte ne distingue pas la seconde.
     */
    @Test
    @DisplayName("une frappe pendant le freinage annule la recherche en cours")
    fun une_frappe_annule_la_recherche_en_cours() = runTest {
        val saisies = MutableStateFlow("")
        val appels = mutableListOf<String>()
        val vues = mutableListOf<SuggestionsDeLien>()

        val collecte = launch(UnconfinedTestDispatcher(testScheduler)) {
            saisies.fluxDeSuggestions(FREINAGE) { requete ->
                appels += requete
                emptyList()
            }.toList(vues)
        }

        saisies.value = "Alp"
        advanceTimeBy(FREINAGE / 2)
        saisies.value = "Alpha"
        advanceTimeBy(FREINAGE + 1)

        assertThat(appels).containsExactly("Alpha")
        assertThat(vues.last()).isEqualTo(SuggestionsDeLien(pour = "Alpha"))

        collecte.cancel()
    }

    /**
     * 🔴 **A failed search answers its input, marked as failed — and the flow goes on.** Escaping, the
     * failure ended the flow in the editor's scope, which has no handler: measured on the S9, a vault
     * locked while the sheet was open brought the app down.
     */
    @Test
    @DisplayName("une recherche en echec repond, marquee comme telle, et le flux continue")
    fun une_recherche_en_echec_repond_et_le_flux_continue() = runTest {
        val saisies = MutableStateFlow("Codes")

        saisies.fluxDeSuggestions(FREINAGE) { requete ->
            check(requete != "Codes") { "coffre verrouille" }
            listOf(note(requete))
        }.test {
            assertThat(awaitItem()).isEqualTo(SuggestionsDeLien(pour = null))
            assertThat(awaitItem()).isEqualTo(SuggestionsDeLien(pour = "Codes", echec = true))

            saisies.value = "Adresses"
            assertThat(awaitItem()).isEqualTo(SuggestionsDeLien(pour = null))
            assertThat(awaitItem()).isEqualTo(SuggestionsDeLien(pour = "Adresses", titres = listOf(note("Adresses"))))

            cancelAndIgnoreRemainingEvents()
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

    private companion object {
        /** La valeur du portage, `FREINAGE_SUGGESTIONS_MILLIS` — recopiée parce qu'elle est privée. */
        const val FREINAGE = 120L
    }
}
