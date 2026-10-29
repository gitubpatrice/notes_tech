package com.filestech.notes_tech.security.sauvegarde

import com.filestech.notes_tech.security.sauvegarde.SecretDeSauvegarde.Refus
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/** The secret of a backup (D-029): the code drawn by default, and what a chosen phrase must be. */
class SecretDeSauvegardeTest {

    @Test
    fun `a code is six groups of four signs of Crockford's base 32, drawn anew each time`() {
        val codes = (1..50).map { SecretDeSauvegarde.nouveauCode(SecureRandom()) }

        codes.forEach { assertThat(it).matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){5}") }
        assertThat(codes.toSet()).hasSize(50)
    }

    /** Read off a piece of paper: case, spaces, dashes and the look-alikes do not count. */
    @Test
    fun `a code is read back however it is typed`() {
        val code = "K7QM-T2XB-0R1A-ZZ9P-HHJK-MN4V"
        val canonique = "K7QMT2XB0R1AZZ9PHHJKMN4V"

        assertThat(SecretDeSauvegarde.codeCanonique(code)).isEqualTo(canonique)
        assertThat(SecretDeSauvegarde.codeCanonique("k7qm t2xb OrIa zz9p hhjk mn4v")).isEqualTo(canonique)
        assertThat(SecretDeSauvegarde.codeCanonique("K7QM T2XB 0RLA ZZ9P HHJK MN4V")).isEqualTo(canonique)
        assertThat(SecretDeSauvegarde.octets("k7qm-t2xb-0r1a-zz9p-hhjk-mn4v")).isEqualTo(canonique.toByteArray())
    }

    @Test
    fun `what is not a code is a phrase, in NFC`() {
        assertThat(SecretDeSauvegarde.codeCanonique("K7QM-T2XB-0R1A-ZZ9P-HHJK")).isNull()
        assertThat(SecretDeSauvegarde.codeCanonique("K7QM-T2XB-0R1A-ZZ9P-HHJK-MN4U")).isNull()

        // "é" typed precomposed or as "e" + a combining acute: one phrase, one key. Written as escapes:
        // a literal would be whatever the editor made of it.
        val decompose = "un été ensoleillé"
        val compose = "un été ensoleillé"
        assertThat(decompose).isNotEqualTo(compose)
        assertThat(SecretDeSauvegarde.octets(decompose)).isEqualTo(compose.toByteArray())
    }

    @Test
    fun `a chosen phrase is refused when short or obvious, not otherwise`() {
        assertThat(SecretDeSauvegarde.refusDeLaPhrase("onze signes")).isEqualTo(Refus.TROP_COURTE)
        assertThat(SecretDeSauvegarde.refusDeLaPhrase("aaaaaaaaaaaa")).isEqualTo(Refus.EVIDENTE)
        assertThat(SecretDeSauvegarde.refusDeLaPhrase("abcabcabcabc")).isEqualTo(Refus.EVIDENTE)
        assertThat(SecretDeSauvegarde.refusDeLaPhrase("123456789012")).isEqualTo(Refus.EVIDENTE)
        assertThat(SecretDeSauvegarde.refusDeLaPhrase("lkjihgfedcba")).isEqualTo(Refus.EVIDENTE)
        assertThat(SecretDeSauvegarde.refusDeLaPhrase("MotDePasse123")).isEqualTo(Refus.EVIDENTE)

        assertThat(SecretDeSauvegarde.refusDeLaPhrase("le chat dort sur la valise")).isNull()
        assertThat(SecretDeSauvegarde.refusDeLaPhrase("abc123def456")).isNull()
    }
}
