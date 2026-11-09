package com.filestech.notes_tech.ui.home

import com.filestech.notes_tech.domain.model.EncryptedBody
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.VaultDescriptor
import com.filestech.notes_tech.domain.model.VaultMode
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What waits for a vault's secret in the selection of a list (3.3.0; one note's long press in 3.1.0),
 * and how it resumes.
 *
 * Both external reviews of 2026-10-10 noted that nothing tested the RESUME: the instrumented tests
 * call the gesture a second time by hand. These run the state holder the screen uses, alone: an
 * accepted gesture must run once its vault is open — once, on the right notes — and never be dropped
 * because another vault's sheet closed.
 */
@DisplayName("Gestes de la sélection en attente d'un coffre")
class GestesDeNoteEnCoursTest {

    private val coffreA = dossier("A", coffre = true)
    private val coffreB = dossier("B", coffre = true)
    private val ordinaire = dossier("O", coffre = false)
    private val dossiers = listOf(coffreA, coffreB, ordinaire)

    @Test
    fun `selecting a note of a closed vault asks for it, and selects the note once it is open`() {
        val etat = GestesDeNoteEnCours()
        val note = note("n1", coffreA, scellee = true)
        val basculees = mutableListOf<String>()
        val selectionnees = mutableListOf<String>()

        assertThat(etat.basculer(note, selectionnee = false, dossiers, emptySet(), basculees::add)).isEqualTo(coffreA)
        assertThat(basculees).isEmpty()

        etat.reprendreApres(coffreA, selectionner = selectionnees::add, executer = AUCUN_GESTE, creer = AUCUNE_CREATION)
        assertThat(selectionnees).containsExactly("n1")
    }

    @Test
    fun `an ordinary note, or a note of an open vault, is selected at once`() {
        val etat = GestesDeNoteEnCours()
        val basculees = mutableListOf<String>()

        assertThat(etat.basculer(note("n1", ordinaire), false, dossiers, emptySet(), basculees::add)).isNull()
        assertThat(
            etat.basculer(note("n2", coffreA, scellee = true), false, dossiers, setOf("A"), basculees::add),
        ).isNull()

        assertThat(basculees).containsExactly("n1", "n2").inOrder()
    }

    /** Deselecting asks for nothing: a vault that closed while its note was selected must not hold it there. */
    @Test
    fun `deselecting a note of a closed vault never asks for its secret`() {
        val etat = GestesDeNoteEnCours()
        val basculees = mutableListOf<String>()

        assertThat(
            etat.basculer(note("n1", coffreA, scellee = true), true, dossiers, emptySet(), basculees::add),
        ).isNull()

        assertThat(basculees).containsExactly("n1")
    }

    @Test
    fun `a gesture refused because a vault is closed runs once it is reopened, and once only`() {
        val etat = GestesDeNoteEnCours()
        val geste = GesteSurDesNotes.SupprimerDefinitivement(listOf("n1", "n2"))
        val executes = mutableListOf<GesteSurDesNotes>()

        etat.attendreLeCoffre(HomeEvent.GesteEnAttenteDuCoffre(coffreA, geste))
        etat.reprendreApres(coffreA, AUCUNE_SELECTION, executer = { executes += it }, creer = AUCUNE_CREATION)
        etat.reprendreApres(coffreA, AUCUNE_SELECTION, executer = { executes += it }, creer = AUCUNE_CREATION)

        assertThat(executes).containsExactly(geste)
    }

    /** GPT-5.6 review: clearing everything before comparing dropped what waited for another vault. */
    @Test
    fun `what waits for vault A survives the unlock of vault B`() {
        val etat = GestesDeNoteEnCours()
        val note = note("n1", coffreA, scellee = true)
        val geste = GesteSurDesNotes.MettreALaCorbeille(listOf(note.id))
        val executes = mutableListOf<GesteSurDesNotes>()
        val selectionnees = mutableListOf<String>()
        etat.basculer(note, false, dossiers, emptySet()) { error("not selected before its vault opens") }
        etat.attendreLeCoffre(HomeEvent.GesteEnAttenteDuCoffre(coffreA, geste))

        etat.reprendreApres(coffreB, selectionnees::add, executer = { executes += it }, creer = AUCUNE_CREATION)
        assertThat(executes).isEmpty()
        assertThat(selectionnees).isEmpty()

        etat.reprendreApres(coffreA, selectionnees::add, executer = { executes += it }, creer = AUCUNE_CREATION)
        assertThat(executes).containsExactly(geste)
        assertThat(selectionnees).containsExactly(note.id)
    }

    /**
     * 3-axes audit: a confirmed deletion waiting for vault A must not survive A's unlock sheet being
     * replaced by vault B's — it would run later, at an unlock made for another reason.
     */
    @Test
    fun `what waits for vault A is given up when the unlock sheet moves to vault B`() {
        val etat = GestesDeNoteEnCours()
        val note = note("n1", coffreA, scellee = true)
        etat.basculer(note, false, dossiers, emptySet()) { error("not selected before its vault opens") }
        etat.attendreLeCoffre(
            HomeEvent.GesteEnAttenteDuCoffre(coffreA, GesteSurDesNotes.SupprimerDefinitivement(listOf(note.id))),
        )

        etat.garderSeulementPour(coffreA.id)
        etat.garderSeulementPour(coffreB.id)
        etat.reprendreApres(
            coffreA,
            selectionner = { error("the selection outlived its sheet") },
            executer = { error("the deletion outlived its sheet") },
            creer = AUCUNE_CREATION,
        )
    }

    @Test
    fun `the sheet of the awaited vault keeps what waits for it`() {
        val etat = GestesDeNoteEnCours()
        val geste = GesteSurDesNotes.Deplacer(listOf("n1"), dossierId = coffreA.id)
        val executes = mutableListOf<GesteSurDesNotes>()
        etat.attendreLeCoffre(HomeEvent.GesteEnAttenteDuCoffre(coffreA, geste))

        etat.garderSeulementPour(coffreA.id)
        etat.reprendreApres(coffreA, AUCUNE_SELECTION, executer = { executes += it }, creer = AUCUNE_CREATION)

        assertThat(executes).containsExactly(geste)
    }

    @Test
    fun `giving up the secret gives up what waited for it`() {
        val etat = GestesDeNoteEnCours()
        val note = note("n1", coffreA, scellee = true)
        etat.basculer(note, false, dossiers, emptySet()) { error("not selected before its vault opens") }
        etat.attendreLeCoffre(
            HomeEvent.GesteEnAttenteDuCoffre(coffreA, GesteSurDesNotes.MettreALaCorbeille(listOf(note.id))),
        )

        etat.abandonner()
        etat.reprendreApres(
            coffreA,
            selectionner = { error("the selection was given up") },
            executer = { error("the gesture was given up") },
            creer = AUCUNE_CREATION,
        )
    }

    /** The exit question holds the move to run with the answer, and is forgotten whichever the answer. */
    @Test
    fun `the exit question keeps its move and its count, and forgetting it clears both`() {
        val etat = GestesDeNoteEnCours()
        val geste = GesteSurDesNotes.Deplacer(listOf("n1", "n2"), dossierId = ordinaire.id)

        etat.demanderLaSortie(HomeEvent.SortieDeCoffreAConfirmer(geste, notesScellees = 2))
        assertThat(etat.sortie).isEqualTo(geste)
        assertThat(etat.sortieNombre).isEqualTo(2)

        etat.oublierLaSortie()
        assertThat(etat.sortie).isNull()
        assertThat(etat.sortieNombre).isEqualTo(0)
    }

    /** "+" in a closed vault (3.1.0): the note is created once the vault is open — once, and only for it. */
    @Test
    fun `a note asked for in a closed vault is created once it is open, once only`() {
        val etat = GestesDeNoteEnCours()
        var creations = 0
        etat.attendreLaCreation(coffreA)

        etat.reprendreApres(coffreB, AUCUNE_SELECTION, executer = {}, creer = { creations++ })
        assertThat(creations).isEqualTo(0)
        etat.reprendreApres(coffreA, AUCUNE_SELECTION, executer = {}, creer = { creations++ })
        etat.reprendreApres(coffreA, AUCUNE_SELECTION, executer = {}, creer = { creations++ })

        assertThat(creations).isEqualTo(1)
    }

    @Test
    fun `a creation waiting for a vault dies with its sheet, or when the secret is given up`() {
        val etat = GestesDeNoteEnCours()
        etat.attendreLaCreation(coffreA)
        etat.garderSeulementPour(coffreB.id)
        etat.reprendreApres(coffreA, AUCUNE_SELECTION, executer = {
        }, creer = { error("the creation outlived its sheet") })

        etat.attendreLaCreation(coffreA)
        etat.abandonner()
        etat.reprendreApres(coffreA, AUCUNE_SELECTION, executer = {}, creer = { error("the creation was given up") })
    }

    private fun dossier(id: String, coffre: Boolean) = Folder(
        id = id,
        name = "Dossier $id",
        parentId = null,
        color = null,
        icon = null,
        createdAt = INSTANT,
        updatedAt = INSTANT,
        vault = if (coffre) VaultDescriptor(VaultMode.PASSPHRASE, failedAttempts = 0) else null,
    )

    private fun note(id: String, dossier: Folder, scellee: Boolean = false) = Note(
        id = id,
        title = if (scellee) "" else "Titre $id",
        content = if (scellee) "" else "corps",
        folderId = dossier.id,
        tags = emptyList(),
        pinned = false,
        favorite = false,
        archived = false,
        trashedAt = null,
        createdAt = INSTANT,
        updatedAt = INSTANT,
        encrypted = if (scellee) EncryptedBody(ByteArray(32)) else null,
        encVersion = if (scellee) 2 else 1,
    )

    private companion object {
        val INSTANT: Instant = Instant.ofEpochMilli(1_760_000_000_000L)
        val AUCUNE_CREATION: () -> Unit = { error("no creation waits") }
        val AUCUN_GESTE: (GesteSurDesNotes) -> Unit = { error("no gesture waits") }
        val AUCUNE_SELECTION: (String) -> Unit = { error("no selection waits") }
    }
}
