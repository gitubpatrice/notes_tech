package com.filestech.notes_tech.ui.share

import com.filestech.notes_tech.domain.importation.NoteImportee
import com.filestech.notes_tech.domain.importation.PartageRecu
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** A share waits in memory, is taken once, and expires (3.2.0, security review L3). */
class PartagesEnAttenteTest {

    private var maintenant = 1_000_000L
    private val attente = PartagesEnAttente().also { it.horloge = { maintenant } }
    private val recu = PartageRecu(listOf(NoteImportee("t", "c", emptyList(), null, null, null, false, false)), 0)

    @Test
    fun `a fresh share is taken once, and only once`() {
        attente.deposer(recu)
        assertThat(attente.partage.value).isEqualTo(recu)
        maintenant += PartagesEnAttente.DUREE_DE_VIE_MS
        assertThat(attente.prendre()).isEqualTo(recu)
        assertThat(attente.prendre()).isNull()
        assertThat(attente.partage.value).isNull()
    }

    @Test
    fun `an expired share is dropped when taken`() {
        attente.deposer(recu)
        maintenant += PartagesEnAttente.DUREE_DE_VIE_MS + 1
        assertThat(attente.prendre()).isNull()
        assertThat(attente.partage.value).isNull()
    }

    @Test
    fun `an abandoned share is gone, unwritten`() {
        attente.deposer(recu)
        attente.abandonner()
        assertThat(attente.partage.value).isNull()
        assertThat(attente.prendre()).isNull()
    }

    @Test
    fun `a newer share replaces one not yet taken, and its own time counts`() {
        attente.deposer(recu)
        maintenant += PartagesEnAttente.DUREE_DE_VIE_MS
        val nouveau = recu.copy(ignores = 3)
        attente.deposer(nouveau)
        maintenant += 1
        assertThat(attente.prendre()).isEqualTo(nouveau)
    }
}
