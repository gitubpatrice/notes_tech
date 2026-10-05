package com.filestech.notes_tech.security.vault

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The UTF-8 copy of a vault's passphrase or PIN is wiped once the derivation is done, whether it
 * returned or threw (`VaultCrypto.withUtf8Bytes`, GPT-5.6 review, 2026-09-25). The String itself
 * cannot be wiped; this copy can. The app lock already did it (`AppLockPin.tagFor`); the vault did not.
 */
class OctetsDuSecretTest {

    @Test
    @DisplayName("la copie UTF-8 du secret est effacee apres la derivation")
    fun la_copie_est_effacee_apres_la_derivation() {
        var vue: ByteArray? = null

        val longueur = VaultCrypto.withUtf8Bytes("phrase été") { octets ->
            vue = octets
            assertThat(octets.toString(Charsets.UTF_8)).isEqualTo("phrase été")
            octets.size
        }

        assertThat(longueur).isEqualTo(12)
        assertThat(vue!!.all { it == 0.toByte() }).isTrue()
    }

    /** Argon2id asks for 64 MiB, which an old phone can refuse: the copy is wiped all the same. */
    @Test
    @DisplayName("la copie UTF-8 du secret est effacee meme si la derivation echoue")
    fun la_copie_est_effacee_meme_si_la_derivation_echoue() {
        var vue: ByteArray? = null

        assertThrows<OutOfMemoryError> {
            VaultCrypto.withUtf8Bytes("1234") { octets ->
                vue = octets
                throw OutOfMemoryError("Argon2id: 64 MiB refused")
            }
        }

        assertThat(vue!!.all { it == 0.toByte() }).isTrue()
    }
}
