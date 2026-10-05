package com.filestech.notes_tech.data.local

import com.filestech.notes_tech.core.crypto.SecretBytes
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Le format de clé est **le** fait dont dépend l'ouverture de la base héritée.
 *
 * SQLCipher ne reconnaît une clé brute qu'à trois conditions simultanées : longueur exactement
 * `taille_clé * 2 + 3`, préfixe `x'`, corps hexadécimal. Manquer l'une des trois fait basculer
 * silencieusement en mode passphrase — la clé passe par PBKDF2 et la base ne s'ouvre pas.
 *
 * Ces tests figent les trois conditions. Le test instrumenté
 * `LegacyDatabaseOpeningTest.la_cle_brute_hexadecimale_est_le_seul_format_qui_ouvre_la_base`
 * prouve, lui, que SQLCipher les interprète bien comme prévu.
 */
class SqlCipherRawKeyTest {

    private val kek = ByteArray(32) { (it * 11 + 3).toByte() }

    @Test
    fun `l'encodage fait exactement 67 octets`() {
        assertThat(SqlCipherRawKey.encode(kek)).hasLength(67)
        assertThat(SqlCipherRawKey.ENCODED_SIZE_BYTES).isEqualTo(67)
    }

    @Test
    fun `l'encodage a la forme x'hex' en ASCII`() {
        val encoded = String(SqlCipherRawKey.encode(kek), Charsets.US_ASCII)

        assertThat(encoded).startsWith("x'")
        assertThat(encoded).endsWith("'")
        // Minuscules : c'est ce que produit `toRadixString(16)` côté Dart, donc ce que la version
        // Flutter a écrit. SQLCipher accepte les deux casses, mais on reste identique à l'origine.
        assertThat(encoded.substring(2, 66)).matches("[0-9a-f]{64}")
    }

    @Test
    fun `l'encodage restitue exactement les octets de la clef`() {
        val encoded = String(SqlCipherRawKey.encode(kek), Charsets.US_ASCII)
        val hex = encoded.substring(2, encoded.length - 1)

        assertThat(SecretBytes.fromHex(hex)).isEqualTo(kek)
    }

    @Test
    fun `l'encodage ne modifie pas la clef qu'on lui passe`() {
        val original = kek.copyOf()
        SqlCipherRawKey.encode(kek)

        // L'effacement de la KEK reste la responsabilité de son propriétaire : si `encode` la
        // vidait, l'appelant se retrouverait avec une clé nulle sans rien voir.
        assertThat(kek).isEqualTo(original)
    }

    @Test
    fun `une clef de taille invalide est refusee sans divulguer sa valeur`() {
        val tropCourte = ByteArray(31)

        val erreur = assertThrows<IllegalArgumentException> { SqlCipherRawKey.encode(tropCourte) }

        assertThat(erreur).hasMessageThat().contains("31")
        // Le message décrit la taille, jamais le contenu : une exception qui recrache la clé la
        // fait entrer dans les traces de plantage.
        assertThat(erreur).hasMessageThat().doesNotContain("00")
    }

    @Test
    fun `la lecture d'une KEK hexadecimale persistee fait l'aller-retour`() {
        val hex = SecretBytes.toHex(kek)

        assertThat(SqlCipherRawKey.decodeHexKek(hex)).isEqualTo(kek)
        // La version Flutter valide par `^[0-9a-fA-F]+$`, donc les majuscules existent en base.
        assertThat(SqlCipherRawKey.decodeHexKek(hex.uppercase())).isEqualTo(kek)
    }

    @Test
    fun `une KEK persistee de mauvaise longueur est refusee`() {
        assertThrows<IllegalArgumentException> { SqlCipherRawKey.decodeHexKek("ab") }
        assertThrows<IllegalArgumentException> { SqlCipherRawKey.decodeHexKek("") }
        assertThrows<IllegalArgumentException> {
            SqlCipherRawKey.decodeHexKek(SecretBytes.toHex(ByteArray(33)))
        }
    }

    @Test
    fun `une KEK persistee non hexadecimale est refusee`() {
        // 64 caractères, bonne longueur, mais `zz` n'est pas de l'hexadécimal. Sans contrôle
        // d'encodage, ce cas produirait des octets faux plutôt qu'une erreur — et une base qui
        // refuse de s'ouvrir sans dire pourquoi.
        val faux = "zz" + "a".repeat(62)

        assertThrows<IllegalArgumentException> { SqlCipherRawKey.decodeHexKek(faux) }
    }
}
