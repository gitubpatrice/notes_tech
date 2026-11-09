package com.filestech.notes_tech.ui.raccourcis

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The launcher shortcuts (D-041, 3.4.0): what an action asks, and how long the request waits. */
@DisplayName("Raccourcis de l'icône")
class RaccourcisTest {

    private var maintenant = 1_000L
    private val enAttente = RaccourcisEnAttente().also { it.horloge = { maintenant } }

    @Test
    fun `each shortcut's action asks its request, and any other action asks nothing`() {
        val nouvelleNote = RaccourciActivity.raccourciDe(RaccourciActivity.ACTION_NOUVELLE_NOTE)
        assertThat(nouvelleNote).isEqualTo(Raccourci.NOUVELLE_NOTE)
        assertThat(RaccourciActivity.raccourciDe(RaccourciActivity.ACTION_DICTEE)).isEqualTo(Raccourci.DICTEE)
        assertThat(RaccourciActivity.raccourciDe("android.intent.action.MAIN")).isNull()
        assertThat(RaccourciActivity.raccourciDe(null)).isNull()
    }

    @Test
    fun `a request is carried out once`() {
        enAttente.deposer(Raccourci.DICTEE)

        assertThat(enAttente.prendre()).isEqualTo(Raccourci.DICTEE)
        assertThat(enAttente.prendre()).isNull()
    }

    @Test
    fun `a request left waiting past its life does nothing at the next unlock`() {
        enAttente.deposer(Raccourci.NOUVELLE_NOTE)
        maintenant += RaccourcisEnAttente.DUREE_DE_VIE_MS + 1

        assertThat(enAttente.prendre()).isNull()
        // Taken all the same: nothing is left for a later unlock.
        assertThat(enAttente.contenu.value).isNull()
    }

    @Test
    fun `a newer request replaces one not yet carried out`() {
        enAttente.deposer(Raccourci.NOUVELLE_NOTE)
        enAttente.deposer(Raccourci.DICTEE)

        assertThat(enAttente.prendre()).isEqualTo(Raccourci.DICTEE)
    }
}
