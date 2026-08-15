package com.filestech.notes_tech.domain.voice

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * L'en-tête WAV, **octet par octet**.
 *
 * ⚠️ Un en-tête faux ne se voit pas à l'écran : le fichier existe, il pèse le bon poids, et c'est le
 * moteur de transcription qui rend un texte vide — ou l'appareil du destinataire qui refuse le son
 * six mois plus tard. C'est exactement le motif des chaînes d'export : *le format est un contrat
 * avec un autre logiciel*, et un contrat se vérifie à la valeur, pas à l'allure.
 */
@DisplayName("WavPcm16 — l'en-tête est petit-boutiste et décrit bien 16 kHz mono 16 bits")
class WavPcm16Test {

    @Test
    @DisplayName("les quatre marqueurs de bloc sont aux positions du format")
    fun marqueurs() {
        val entete = WavPcm16.entete(donneesOctets = 0)

        assertThat(entete).hasLength(44)
        assertThat(texte(entete, 0, 4)).isEqualTo("RIFF")
        assertThat(texte(entete, 8, 4)).isEqualTo("WAVE")
        assertThat(texte(entete, 12, 4)).isEqualTo("fmt ")
        assertThat(texte(entete, 36, 4)).isEqualTo("data")
    }

    @Test
    @DisplayName("le bloc fmt décrit du PCM non compressé, 1 canal, 16 kHz, 16 bits")
    fun descriptionDuFormat() {
        val entete = WavPcm16.entete(donneesOctets = 0)

        assertThat(entier32(entete, 16)).isEqualTo(16) // longueur du bloc fmt
        assertThat(entier16(entete, 20)).isEqualTo(1) // 1 = PCM
        assertThat(entier16(entete, 22)).isEqualTo(1) // canaux
        assertThat(entier32(entete, 24)).isEqualTo(16_000) // échantillons par seconde
        assertThat(entier32(entete, 28)).isEqualTo(32_000) // octets par seconde = 16000 × 1 × 2
        assertThat(entier16(entete, 32)).isEqualTo(2) // alignement d'un bloc
        assertThat(entier16(entete, 34)).isEqualTo(16) // bits par échantillon
    }

    /**
     * 🔴 Les deux tailles, et le piège des huit octets.
     *
     * La taille annoncée par `RIFF` **exclut ses propres huit premiers octets** ; celle de `data`
     * compte les données seules. Les confondre donne un fichier que la plupart des lecteurs
     * acceptent quand même — en tronquant la fin, ou en lisant huit octets de trop.
     */
    @Test
    @DisplayName("les deux tailles suivent la donnée, avec le décalage de huit octets de RIFF")
    fun tailles() {
        val entete = WavPcm16.entete(donneesOctets = 32_000)

        assertThat(entier32(entete, 40)).isEqualTo(32_000) // taille des données
        assertThat(entier32(entete, 4)).isEqualTo(32_000 + 44 - 8) // taille du bloc RIFF
    }

    /** Une seconde d'audio pèse exactement 32 000 octets — c'est ce qui rend la borne calculable. */
    @Test
    @DisplayName("une seconde de son fait 32 000 octets")
    fun uneSeconde() {
        val uneSeconde = WavPcm16.FREQUENCE_HZ.toLong() * WavPcm16.CANAUX * (WavPcm16.BITS_PAR_ECHANTILLON / 8)

        assertThat(uneSeconde).isEqualTo(32_000)
    }

    /** ⚠️ Une taille négative n'est pas un cas limite : c'est un compteur qui a débordé. */
    @Test
    @DisplayName("une taille négative est refusée plutôt qu'écrite")
    fun tailleNegativeRefusee() {
        assertThrows<IllegalArgumentException> { WavPcm16.entete(donneesOctets = -1) }
    }

    // ── Le niveau sonore ─────────────────────────────────────────────────────

    @Test
    @DisplayName("le silence donne zéro, le maximum donne un")
    fun bornesDuNiveau() {
        val silence = ByteArray(64)
        assertThat(WavPcm16.niveau(silence, silence.size)).isEqualTo(0f)

        val fort = echantillons(List(32) { Short.MAX_VALUE.toInt() })
        assertThat(WavPcm16.niveau(fort, fort.size)).isWithin(0.001f).of(1f)
    }

    /**
     * ⚠️ Le niveau ne dépend pas du SIGNE : une sinusoïde passe par des valeurs négatives, et un
     * calcul qui les ignorerait afficherait un niveau deux fois trop faible.
     */
    @Test
    @DisplayName("une valeur négative pèse autant que la positive")
    fun signeIndifferent() {
        val positif = echantillons(List(16) { 8_000 })
        val negatif = echantillons(List(16) { -8_000 })

        assertThat(WavPcm16.niveau(negatif, negatif.size))
            .isWithin(0.0001f)
            .of(WavPcm16.niveau(positif, positif.size))
    }

    /**
     * 🔴 Seule la partie **réellement lue** compte.
     *
     * Le tampon du micro est presque toujours plus grand que ce qu'il rapporte. Mesurer tout le
     * tampon reviendrait à moyenner du bruit avec des zéros — un niveau qui s'effondre dès que la
     * lecture rend moins que la taille demandée, c'est-à-dire à chaque fin de capture.
     */
    @Test
    @DisplayName("les octets au-delà de la longueur lue sont ignorés")
    fun seuleLaPartieLueCompte() {
        val tampon = ByteArray(64)
        val fort = echantillons(List(8) { Short.MAX_VALUE.toInt() })
        fort.copyInto(tampon)

        assertThat(WavPcm16.niveau(tampon, fort.size)).isWithin(0.001f).of(1f)
        // Sur tout le tampon, les zéros de la fin écrasent la moyenne.
        assertThat(WavPcm16.niveau(tampon, tampon.size)).isLessThan(0.6f)
    }

    /** ⚠️ Un octet orphelin ne forme pas un échantillon : il est ignoré, pas complété par un zéro. */
    @Test
    @DisplayName("une longueur impaire ignore l'octet orphelin")
    fun longueurImpaire() {
        val deux = echantillons(listOf(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()))

        assertThat(WavPcm16.niveau(deux, 3)).isWithin(0.001f).of(WavPcm16.niveau(deux, 2))
    }

    @Test
    @DisplayName("une longueur nulle ou plus grande que le tampon ne fait pas échouer")
    fun longueursAberrantes() {
        val tampon = echantillons(List(4) { 1_000 })

        assertThat(WavPcm16.niveau(tampon, 0)).isEqualTo(0f)
        assertThat(WavPcm16.niveau(tampon, -5)).isEqualTo(0f)
        assertThat(WavPcm16.niveau(tampon, 9_999)).isEqualTo(WavPcm16.niveau(tampon, tampon.size))
    }

    private fun echantillons(valeurs: List<Int>): ByteArray {
        val octets = ByteArray(valeurs.size * 2)
        valeurs.forEachIndexed { i, v ->
            octets[i * 2] = (v and 0xFF).toByte()
            octets[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return octets
    }

    private fun texte(octets: ByteArray, position: Int, longueur: Int) =
        String(octets, position, longueur, Charsets.US_ASCII)

    private fun entier32(octets: ByteArray, position: Int): Long {
        var valeur = 0L
        for (i in 0 until 4) valeur = valeur or ((octets[position + i].toLong() and 0xFF) shl (8 * i))
        return valeur
    }

    private fun entier16(octets: ByteArray, position: Int): Int {
        var valeur = 0
        for (i in 0 until 2) valeur = valeur or ((octets[position + i].toInt() and 0xFF) shl (8 * i))
        return valeur
    }
}
