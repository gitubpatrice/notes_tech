package com.filestech.notes_tech.ui.voice

import com.filestech.notes_tech.data.voice.VoiceCapture
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **La borne de deux minutes, et le fait de la DIRE.**
 *
 * `VoiceCapture` arrête la capture à `DUREE_MAX_SECONDES` là où l'application publiée n'a **aucune**
 * borne. Elle s'appliquait en silence : le WAV partait à la transcription, le texte s'insérait, et le
 * message était le même que pour un arrêt au doigt. On dictait trois minutes, il en manquait une, et
 * rien ne permettait de l'apprendre (`04-PIEGES.md` §96).
 *
 * 🔴 **Les deux règles mesurées ici étaient toutes deux hors d'atteinte.** La classification vivait en
 * une ligne dans une fonction privée et suspendue exigeant un `AudioRecord` réel ; le format du
 * compteur n'existait pas. Ce sont les deux endroits où une erreur d'un cran ne se voit pas.
 */
class BorneDeDureeTest {

    /**
     * ⚠️ `when` **exhaustif sur l'énumération** : une troisième fin de capture fera échouer la
     * compilation de ce fichier tant que personne n'aura décidé de ce qu'elle dit à l'utilisateur.
     */
    private fun etiquette(fin: VoiceCapture.FinDeCapture): String = when (fin) {
        VoiceCapture.FinDeCapture.GESTE -> "geste"
        VoiceCapture.FinDeCapture.BORNE_DE_DUREE -> "borne"
    }

    private fun fin(octets: Long): String = etiquette(VoiceCapture.finDeCapture(octets))

    @Test
    @DisplayName("une capture ordinaire est classee comme un GESTE")
    fun une_capture_ordinaire_est_un_geste() {
        assertThat(fin(OCTETS_UNE_SECONDE)).isEqualTo("geste")
        assertThat(fin(OCTETS_MAX - OCTETS_UNE_SECONDE)).isEqualTo("geste")
    }

    /**
     * 🔴 **Le cas limite est le cœur du sujet.** Exactement à la borne, la boucle sort **par la
     * borne** : c'est sa condition, `octetsEcrits < OCTETS_MAX`. Classer cet octet-là comme un geste
     * de l'utilisateur redonnerait le silence qu'on répare.
     */
    @Test
    @DisplayName("EXACTEMENT a la borne, c'est la BORNE — pas un geste")
    fun exactement_a_la_borne_c_est_la_borne() {
        assertThat(fin(OCTETS_MAX)).isEqualTo("borne")
        assertThat(fin(OCTETS_MAX - 1)).isEqualTo("geste")
    }

    /**
     * ⚠️ **Le dépassement doit compter aussi.** `micro.read` rend ce qu'il a, pas ce qu'on aurait
     * voulu : le dernier tampon fait presque toujours franchir la borne de quelques milliers
     * d'octets. Un `==` au lieu d'un `>=` laisserait passer **tous** les cas réels tout en gardant le
     * cas exact ci-dessus au vert.
     */
    @Test
    @DisplayName("un dernier tampon qui DEPASSE la borne compte comme la borne")
    fun un_depassement_compte_comme_la_borne() {
        assertThat(fin(OCTETS_MAX + 1)).isEqualTo("borne")
        assertThat(fin(OCTETS_MAX + OCTETS_UNE_SECONDE)).isEqualTo("borne")
    }

    // -----------------------------------------------------------------------
    // Le compteur affiché
    // -----------------------------------------------------------------------

    /**
     * ⚠️ **La borne se lit « 2:00 » à l'écran comme dans le message.** Les deux passent par cette
     * fonction et par la même constante : écrire « 2 minutes » dans la traduction aurait créé un
     * second endroit où la borne est dite, et le jour où elle change, l'un des deux mentirait.
     */
    @Test
    @DisplayName("la borne s'ecrit « 2:00 », a l'ecran comme dans le message")
    fun la_borne_s_ecrit_deux_minutes() {
        assertThat(dureeMmSs(VoiceCapture.DUREE_MAX_SECONDES)).isEqualTo("2:00")
    }

    /** ⚠️ Les secondes sont sur **deux** chiffres, les minutes sur autant qu'il en faut. */
    @Test
    @DisplayName("le compteur remplit les secondes a deux chiffres")
    fun le_compteur_remplit_les_secondes() {
        val rendus = listOf(0, 5, 59, 60, 61, 125).map(::dureeMmSs)

        assertThat(rendus).containsExactly("0:00", "0:05", "0:59", "1:00", "1:01", "2:05").inOrder()
    }

    private companion object {
        /** 16 kHz × 2 octets, mono — le débit que `WavPcm16` impose. */
        const val OCTETS_UNE_SECONDE = 32_000L
        const val OCTETS_MAX = OCTETS_UNE_SECONDE * VoiceCapture.DUREE_MAX_SECONDES
    }
}
