package com.filestech.notes_tech.ui.home

import com.filestech.notes_tech.data.prefs.HomeStartPreference
import com.filestech.notes_tech.data.prefs.HomeStartPreference.ALL_NOTES
import com.filestech.notes_tech.data.prefs.HomeStartPreference.INBOX
import com.filestech.notes_tech.domain.model.Folder
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** D-046 (3.5.0, issue #59): which list the home screen opens on. */
@DisplayName("Dossier de l'accueil au démarrage")
class DossierAuDemarrageTest {

    @Test
    fun `a new start opens where the setting says`() {
        assertThat(nouveauDemarrage(ALL_NOTES)).isNull()
        assertThat(nouveauDemarrage(INBOX)).isEqualTo(Folder.INBOX_ID)
    }

    /**
     * The case that tells "the key is there, holding null" from "no key": "All notes" picked on purpose
     * must survive a process death even when the setting says Inbox. Reading `get(key) ?: setting` would
     * reopen on the Inbox — this is what the test catches.
     */
    @Test
    fun `a saved All notes wins over the setting`() {
        assertThat(restaure(dossier = null, reglage = INBOX)).isNull()
    }

    @Test
    fun `a saved folder wins over the setting, whichever it is`() {
        HomeStartPreference.entries.forEach { reglage ->
            assertThat(restaure(dossier = "carnet", reglage = reglage)).isEqualTo("carnet")
        }
    }

    private fun nouveauDemarrage(reglage: HomeStartPreference): String? =
        dossierAuDemarrage(enregistre = false, dossierEnregistre = null, reglage = reglage)

    private fun restaure(dossier: String?, reglage: HomeStartPreference): String? =
        dossierAuDemarrage(enregistre = true, dossierEnregistre = dossier, reglage = reglage)
}
