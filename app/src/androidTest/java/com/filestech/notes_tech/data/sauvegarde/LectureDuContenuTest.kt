package com.filestech.notes_tech.data.sauvegarde

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.data.sauvegarde.LectureDuContenu.ContenuIllisibleException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream

/**
 * **The content is read to the very end of its stream** — instrumented: `android.util.JsonReader`.
 *
 * 🔴 Why it matters: a GCM stream checks its tag only when its end is read. Bouncy Castle holds back
 * the last bytes, so most of the time the JSON's last brace comes out only with the check — but when
 * the content ends on a 16-byte boundary, its last block can come out first, and a JSON reader
 * stopping at the brace would never ask for the end: the tag would go unchecked. The round trip of
 * `SauvegardeChiffreeTest` passes either way, for that reason; this test does not.
 */
@RunWith(AndroidJUnit4::class)
class LectureDuContenuTest {

    private fun contenu(): ByteArray = ByteArrayOutputStream().also { sortie ->
        EcritureDuContenu(sortie, creeLe = 1L).use { contenu ->
            contenu.dossier(DossierSauvegarde("inbox", "Inbox", null, null, null, 1L, 2L, coffre = null))
        }
    }.toByteArray()

    /** Hands its bytes in one go, as a buffer does, and records whether its end was asked for. */
    private class FluxQuiNoteSaFin(octets: ByteArray) : FilterInputStream(ByteArrayInputStream(octets)) {
        var finDemandee = false

        override fun read(): Int = super.read().also { if (it < 0) finDemandee = true }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it < 0) finDemandee = true }
    }

    @Test
    fun le_contenu_est_lu_jusqu_a_la_fin_de_son_flux(): Unit = runBlocking {
        val flux = FluxQuiNoteSaFin(contenu())
        var dossiers = 0

        LectureDuContenu.lire(flux, surLesDossiers = { dossiers = it.size }, surUneNote = {})

        assertThat(dossiers).isEqualTo(1)
        assertThat(flux.finDemandee).isTrue()
    }

    @Test
    fun des_octets_apres_le_contenu_le_font_refuser(): Unit = runBlocking {
        val avecUnReste = contenu() + " {}".toByteArray()

        val echec = runCatching {
            LectureDuContenu.lire(ByteArrayInputStream(avecUnReste), surLesDossiers = {}, surUneNote = {})
        }.exceptionOrNull()

        assertThat(echec).isNotNull()
        assertThat(echec is ContenuIllisibleException || echec is java.io.IOException).isTrue()
    }

    /**
     * Noise from a wrong secret that happens to begin like JSON of another shape — `[`, a digit — is
     * an unreadable content like any other, an [java.io.IOException]: the screen asks for the code
     * again. `JsonReader` threw `IllegalStateException` for some of them (GPT-5.6 review).
     */
    @Test
    fun un_debut_d_une_autre_forme_est_un_contenu_illisible(): Unit = runBlocking {
        for (debut in listOf("[1,2]", "42", "\"x\"", "{\"version\":\"x\"}")) {
            val echec = runCatching {
                LectureDuContenu.lire(ByteArrayInputStream(debut.toByteArray()), surLesDossiers = {}, surUneNote = {})
            }.exceptionOrNull()
            assertThat(echec).isInstanceOf(java.io.IOException::class.java)
        }
    }

    /** A second `folders` would have the trial and the writing pass decide on two trees: refused. */
    @Test
    fun un_champ_de_la_racine_en_double_le_fait_refuser(): Unit = runBlocking {
        val texte = contenu().decodeToString()
        val dossiers = texte.substring(texte.indexOf("\"folders\""), texte.indexOf("\"notes\""))
        val double = texte.replace("\"notes\"", dossiers + "\"notes\"")
        var appels = 0

        val echec = runCatching {
            LectureDuContenu.lire(
                ByteArrayInputStream(double.toByteArray()),
                surLesDossiers = { appels++ },
                surUneNote = {},
            )
        }.exceptionOrNull()

        assertThat(echec).isInstanceOf(ContenuIllisibleException::class.java)
        assertThat(appels).isEqualTo(1)
    }
}
