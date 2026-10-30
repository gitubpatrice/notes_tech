package com.filestech.notes_tech.security.sauvegarde

import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde.Refus
import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde.SauvegardeRefuseeException
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The `.ntbackup` file, version 1 (D-029): what it writes, and everything it refuses — most of it
 * BEFORE any key is derived, nothing being authenticated at that point.
 *
 * The key is given directly here: Argon2id at 64 MiB is the vaults' own, tested with them.
 */
class FichierDeSauvegardeTest {

    private val cle = ByteArray(32) { (it * 7 + 1).toByte() }
    private val autreCle = ByteArray(32) { (it * 5 + 2).toByte() }

    private fun fichier(contenu: ByteArray, cleDuFichier: ByteArray = cle): ByteArray {
        val sortie = ByteArrayOutputStream()
        FichierDeSauvegarde.ecrire(sortie, cleDuFichier, FichierDeSauvegarde.nouvelEnTete()).use { it.write(contenu) }
        return sortie.toByteArray()
    }

    /** Reads [octets] to the end, as both passes of a restore do. */
    private fun lire(octets: ByteArray, cleDeLecture: ByteArray = cle): ByteArray {
        val entree = FichierDeSauvegarde.borne(ByteArrayInputStream(octets))
        val (enTete, brut) = FichierDeSauvegarde.lireLEnTete(entree)
        return FichierDeSauvegarde.ouvrir(entree, cleDeLecture, enTete, brut).use(InputStream::readBytes)
    }

    @Test
    fun `a file reads back what was written, through a header of 48 bytes`() {
        val contenu = ByteArray(300_000) { (it % 251).toByte() }

        val octets = fichier(contenu)

        assertThat(octets.size).isEqualTo(FichierDeSauvegarde.TAILLE_DE_L_EN_TETE + contenu.size + 16)
        assertThat(octets.copyOf(8)).isEqualTo("NTBACKUP".toByteArray())
        assertThat(lire(octets)).isEqualTo(contenu)
    }

    @Test
    fun `two files of the same content share neither salt nor nonce`() {
        val a = fichier("x".toByteArray())
        val b = fichier("x".toByteArray())

        assertThat(a.copyOfRange(20, 48)).isNotEqualTo(b.copyOfRange(20, 48))
    }

    /** Wrong secret and damaged file look the same: the tag does not hold, at the END of the stream. */
    @Test
    fun `a wrong key, a changed byte or a cut file fails at the end of the content`() {
        val octets = fichier(ByteArray(10_000) { 1 })
        val abime = octets.copyOf().also { it[5_000] = (it[5_000] + 1).toByte() }
        val sel = octets.copyOf().also { it[25] = (it[25] + 1).toByte() }
        val coupe = octets.copyOf(octets.size - 1)

        assertThrows<IOException> { lire(octets, autreCle) }
        assertThrows<IOException> { lire(abime) }
        // The salt is in the AAD: changed, the header no longer matches what was sealed with it.
        assertThrows<IOException> { lire(sel) }
        assertThrows<IOException> { lire(coupe) }
    }

    /** 🔴 R-10: nothing is derived from a header the version 1 does not write. */
    @Test
    fun `a header with other parameters is refused before any derivation`() {
        val octets = fichier("x".toByteArray())

        fun refus(position: Int, valeur: Byte): Refus {
            val forge = octets.copyOf().also { it[position] = valeur }
            return assertThrows<SauvegardeRefuseeException> {
                FichierDeSauvegarde.lireLEnTete(ByteArrayInputStream(forge))
            }.refus
        }

        assertThat(refus(0, 'X'.code.toByte())).isEqualTo(Refus.PAS_UNE_SAUVEGARDE)
        assertThat(refus(9, 2)).isEqualTo(Refus.VERSION_INCONNUE)
        assertThat(refus(10, 2)).isEqualTo(Refus.PARAMETRES_INCONNUS)
        // t = 4, m = 64 MiB × 256, p = 2: each alone is refused.
        assertThat(refus(14, 4)).isEqualTo(Refus.PARAMETRES_INCONNUS)
        assertThat(refus(15, 1)).isEqualTo(Refus.PARAMETRES_INCONNUS)
        assertThat(refus(19, 2)).isEqualTo(Refus.PARAMETRES_INCONNUS)
        assertThat(
            assertThrows<SauvegardeRefuseeException> {
                FichierDeSauvegarde.lireLEnTete(ByteArrayInputStream(octets.copyOf(47)))
            }.refus,
        ).isEqualTo(Refus.TRONQUE)
    }

    /** R-9: counted on what is read, whatever size a provider announces. */
    @Test
    fun `past 64 MiB really read, the file is refused`() {
        val infini = object : InputStream() {
            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        val borne = FichierDeSauvegarde.borne(infini)
        val tampon = ByteArray(1024 * 1024)

        val refus = assertThrows<SauvegardeRefuseeException> {
            repeat(65) { borne.read(tampon) }
        }.refus

        assertThat(refus).isEqualTo(Refus.TROP_GROS)
    }
}
