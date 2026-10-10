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
 * What waits for a vault's secret after a long press on a note (3.1.0), and how it resumes.
 *
 * Both external reviews of 2026-10-10 noted that nothing tested the RESUME: the instrumented tests
 * call the gesture a second time by hand. These run the state holder the screen uses, alone: an
 * accepted gesture must run once its vault is open — once, on the right note — and never be dropped
 * because another vault's sheet closed.
 */
@DisplayName("Gestes sur une note en attente d'un coffre")
class GestesDeNoteEnCoursTest {

    private val coffreA = dossier("A", coffre = true)
    private val coffreB = dossier("B", coffre = true)
    private val ordinaire = dossier("O", coffre = false)
    private val dossiers = listOf(coffreA, coffreB, ordinaire)

    @Test
    fun `a long press on a note of a closed vault asks for it, and opens the sheet once it is open`() {
        val etat = GestesDeNoteEnCours()
        val note = note("n1", coffreA, scellee = true)

        assertThat(etat.appuiLong(note, dossiers, coffresOuverts = emptySet())).isEqualTo(coffreA)
        assertThat(etat.noteEnMenu).isNull()

        etat.reprendreApres(coffreA) { error("no gesture waits") }
        assertThat(etat.noteEnMenu).isEqualTo(note)
    }

    @Test
    fun `a long press on an ordinary note, or on a note of an open vault, opens the sheet at once`() {
        val etat = GestesDeNoteEnCours()
        assertThat(etat.appuiLong(note("n1", ordinaire), dossiers, emptySet())).isNull()
        assertThat(etat.noteEnMenu?.id).isEqualTo("n1")

        assertThat(etat.appuiLong(note("n2", coffreA, scellee = true), dossiers, setOf("A"))).isNull()
        assertThat(etat.noteEnMenu?.id).isEqualTo("n2")
    }

    @Test
    fun `a gesture refused because the vault closed runs once it is reopened, and once only`() {
        val etat = GestesDeNoteEnCours()
        val geste = GesteSurUneNote.SupprimerDefinitivement(note("n1", coffreA, scellee = true))
        val executes = mutableListOf<GesteSurUneNote>()

        etat.attendreLeCoffre(HomeEvent.GesteEnAttenteDuCoffre(coffreA, geste))
        etat.reprendreApres(coffreA) { executes += it }
        etat.reprendreApres(coffreA) { executes += it }

        assertThat(executes).containsExactly(geste)
    }

    /** GPT-5.6 review: clearing everything before comparing dropped what waited for another vault. */
    @Test
    fun `what waits for vault A survives the unlock of vault B`() {
        val etat = GestesDeNoteEnCours()
        val note = note("n1", coffreA, scellee = true)
        val geste = GesteSurUneNote.MettreALaCorbeille(note)
        val executes = mutableListOf<GesteSurUneNote>()
        etat.appuiLong(note, dossiers, emptySet())
        etat.attendreLeCoffre(HomeEvent.GesteEnAttenteDuCoffre(coffreA, geste))

        etat.reprendreApres(coffreB) { executes += it }
        assertThat(executes).isEmpty()
        assertThat(etat.noteEnMenu).isNull()

        etat.reprendreApres(coffreA) { executes += it }
        assertThat(executes).containsExactly(geste)
        assertThat(etat.noteEnMenu).isEqualTo(note)
    }

    @Test
    fun `giving up the secret gives up what waited for it`() {
        val etat = GestesDeNoteEnCours()
        val note = note("n1", coffreA, scellee = true)
        etat.appuiLong(note, dossiers, emptySet())
        etat.attendreLeCoffre(HomeEvent.GesteEnAttenteDuCoffre(coffreA, GesteSurUneNote.MettreALaCorbeille(note)))

        etat.abandonner()
        etat.reprendreApres(coffreA) { error("the gesture was given up") }

        assertThat(etat.noteEnMenu).isNull()
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
    }
}
