package com.filestech.notes_tech.security.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.core.crypto.SecretBytes
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le scellement lié à l'appareil, contre le vrai `AndroidKeyStore`.
 *
 * Rien de ce qui suit n'est vérifiable sur la JVM : ni la résidence de la clé dans le matériel
 * sécurisé, ni le nonce que le Keystore tire lui-même, ni le comportement d'une étiquette qui ne
 * valide pas. C'est exactement pourquoi la couche est derrière une interface — le reste du service
 * s'exerce sans appareil, et seul ce fichier en dépend.
 *
 * ⚠️ **Les alias sont supprimés en fin de test**, y compris en cas d'échec. Un alias laissé
 * derrière ferait passer la création suivante pour un `false` — « existait déjà » — et le test
 * suivant vérifierait autre chose que ce qu'il annonce.
 */
@RunWith(AndroidJUnit4::class)
class AndroidVaultKeystoreTest {

    private val keystore = AndroidVaultKeystore(InstrumentationRegistry.getInstrumentation().targetContext)
    private val alias = VaultParams.pinKeystoreAlias("test-${javaClass.simpleName}")

    @After
    fun tearDown() {
        keystore.deleteKey(alias)
    }

    @Test
    fun une_cle_creee_scelle_et_descelle_ce_quon_lui_donne() {
        assertThat(keystore.creerOuIgnorer(alias)).isTrue()
        val clair = SecretBytes.randomBytes(VaultParams.FOLDER_KEY_BYTES)

        val scelle = keystore.seal(alias, clair)

        assertThat(scelle.nonce).hasLength(VaultParams.NONCE_BYTES)
        assertThat(scelle.ciphertext).hasLength(clair.size + VaultParams.TAG_BYTES)
        assertThat(keystore.open(alias, scelle)).isEqualTo(clair)
    }

    @Test
    fun deux_scellements_du_meme_clair_different() {
        // `setRandomizedEncryptionRequired(true)` interdit de fournir un nonce, donc le Keystore en
        // tire un neuf à chaque fois. Si ce test échouait, le même couple (clé, nonce) servirait
        // deux fois — ce qui casse GCM au point de laisser retrouver la clé d'authentification.
        keystore.creerOuIgnorer(alias)
        val clair = SecretBytes.randomBytes(VaultParams.FOLDER_KEY_BYTES)

        val premier = keystore.seal(alias, clair)
        val second = keystore.seal(alias, clair)

        assertThat(premier.nonce).isNotEqualTo(second.nonce)
        assertThat(premier.ciphertext).isNotEqualTo(second.ciphertext)
    }

    @Test
    fun creer_deux_fois_le_meme_alias_ne_remplace_pas_la_cle() {
        assertThat(keystore.creerOuIgnorer(alias)).isTrue()
        val scelle = keystore.seal(alias, CLAIR_CONNU)

        assertThat(keystore.createKey(alias)).isFalse()

        // Si le second appel avait régénéré la clé, ce déscellement échouerait — et un coffre
        // aurait été rendu inouvrable par une simple création idempotente.
        assertThat(keystore.open(alias, scelle)).isEqualTo(CLAIR_CONNU)
    }

    @Test
    fun un_scelle_abime_est_signale_comme_malforme_et_non_comme_mauvais_code() {
        // 🔴 La distinction décide de détruire ou non les notes de quelqu'un. Le code de
        // l'utilisateur n'intervient PAS à cette couche : une étiquette qui ne valide pas dit que
        // la donnée est abîmée, jamais que le code est faux.
        keystore.creerOuIgnorer(alias)
        val scelle = keystore.seal(alias, CLAIR_CONNU)
        val abime = SealedByKeystore(
            ciphertext = scelle.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            nonce = scelle.nonce,
        )

        assertThrows(MalformedVaultDataException::class.java) { keystore.open(alias, abime) }
    }

    /**
     * An absent key, on OPENING, is named: removing the screen lock deletes a vault key (API 34,
     * measured 2026-09-25), and the "unavailable, retry" it used to earn changed nothing.
     * Still neither damaged data nor a wrong PIN — the service counts nothing and wipes nothing.
     * Needs no key of its own, so it runs on an emulator too.
     */
    @Test
    fun une_cle_absente_est_nommee_a_l_ouverture() {
        keystore.deleteKey(alias)

        assertThrows(KeystoreKeyMissingException::class.java) {
            keystore.open(alias, SealedByKeystore(ByteArray(VaultParams.TAG_BYTES), ByteArray(VaultParams.NONCE_BYTES)))
        }
    }

    /**
     * ⚠️ And NOT on sealing, on purpose: sealing follows the key's creation, so an absent key there says
     * nothing about a vault the user already has — "Android deleted this vault's key" would be false.
     */
    @Test
    fun une_cle_absente_au_scellement_reste_une_indisponibilite() {
        keystore.deleteKey(alias)

        assertThrows(KeystoreUnavailableException::class.java) { keystore.seal(alias, CLAIR_CONNU) }
    }

    @Test
    fun supprimer_est_idempotent_et_observable() {
        keystore.creerOuIgnorer(alias)
        assertThat(keystore.hasKey(alias)).isTrue()

        keystore.deleteKey(alias)
        keystore.deleteKey(alias)

        assertThat(keystore.hasKey(alias)).isFalse()
    }

    /**
     * La clé doit être retenue par du matériel sécurisé, sinon [AndroidVaultKeystore] la supprime et
     * refuse.
     *
     * ⚠️ **Ce test ne peut échouer que sur un appareil sans TEE** — un émulateur, un système
     * modifié. Sur le S9 comme sur tout Android moderne, il passe parce que le matériel est là.
     * C'est donc un contrôle de l'appareil autant que du code, et c'est assumé : il documente que le
     * mode à code n'est proposé que là où il tient sa promesse.
     */
    @Test
    fun la_cle_creee_est_retenue_par_le_materiel_securise() {
        assertThat(keystore.createKey(alias)).isTrue()
        assertThat(keystore.hasKey(alias)).isTrue()
    }

    private companion object {
        val CLAIR_CONNU = ByteArray(VaultParams.FOLDER_KEY_BYTES) { (it * 3 + 1).toByte() }
    }
}

/**
 * Creates the key, or skips the test where the Keystore is software only — an emulator: the vault
 * refuses such a key on purpose (`KeystoreSoftwareOnlyException`, as 2.0.9 does), so nothing that
 * needs it can run there, and failing would only bury real failures in known ones (§156).
 *
 * ⚠️ **`AndroidVaultKeystoreTest.la_cle_creee_est_retenue_par_le_materiel_securise` does not use it,
 * and must keep failing there.** It is the witness: were the port to take a phone's TEE for a
 * software Keystore, every vault test would skip on that phone instead of failing — that one would not.
 */
internal fun AndroidVaultKeystore.creerOuIgnorer(alias: String): Boolean = try {
    createKey(alias)
} catch (e: KeystoreSoftwareOnlyException) {
    throw AssumptionViolatedException("software-only Keystore: the vault refuses its keys", e)
}
