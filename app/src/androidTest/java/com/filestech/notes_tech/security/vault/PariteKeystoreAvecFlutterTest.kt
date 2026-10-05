package com.filestech.notes_tech.security.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * **Le contrat AndroidKeyStore que le portage partage avec l'application publiée.**
 *
 * Ligne `keystore_bridge.dart` de `docs/05-PARITE.md`, critère écrit : *« repris depuis
 * `KeystoreBridge.kt`, sans MethodChannel »*.
 *
 * ## 🔴🔴 Ce qui se joue ici, et pourquoi aucun test existant ne le voyait
 *
 * `AndroidVaultKeystoreTest` prouve que le portage **se relit lui-même** : il scelle, il descelle,
 * deux scellés diffèrent, un scellé abîmé est signalé. Sept cas justes, et **aucun** ne dit quoi que
 * ce soit du seul risque qui compte à la bascule 3.0.0 : un coffre à code créé par la 2.0.x
 * doit s'ouvrir sous la 3.0.0, sur le même téléphone, avec la même clé matérielle.
 *
 * Une divergence de paramètre ne se voit pas — elle ne casse rien à l'exécution, elle produit une
 * **autre clé**. `createKey` la crée, tout fonctionne, et le coffre de l'utilisateur, lui, ne
 * s'ouvre plus jamais. Il n'y a pas de message d'erreur pour ça : il y a une note perdue.
 *
 * ## Le sens de lecture, qui est le point
 *
 * Ces cas **ne recopient rien du portage**. Chaque valeur est transcrite de
 * `notes_tech/android/app/src/main/kotlin/com/filestech/notes_tech/KeystoreBridge.kt` — spécification
 * de clé aux lignes 216-238, `wrap` à 290-299, `unwrap` à 301-307, alias documenté à la ligne 24 —
 * puis **comparée** à ce que le portage produit. Un test qui lirait `VaultParams` des deux côtés
 * serait circulaire : il passerait après un changement de paramètre, qui est exactement le défaut
 * qu'on cherche.
 *
 * ## ⚠️ Ce que ces cas NE prouvent pas
 *
 * Qu'un coffre du téléphone de quelqu'un s'ouvre. La clé Keystore est liée à l'**UID**, et la build
 * de portage porte un `applicationId` suffixé `.next` : elle ne PEUT pas voir les clés de
 * l'application publiée. Cf. `docs/06-ISOLATION-PENDANT-LE-CHANTIER.md`. Ce qui est mesuré ici est
 * la seule chose mesurable avant la bascule — que les deux codes, sur le même Keystore, produisent
 * et consomment **le même matériel**.
 */
@RunWith(AndroidJUnit4::class)
class PariteKeystoreAvecFlutterTest {

    private lateinit var store: KeyStore
    private lateinit var portage: AndroidVaultKeystore

    @Before
    fun setUp() {
        store = KeyStore.getInstance(KEYSTORE_PUBLIE).apply { load(null) }
        portage = AndroidVaultKeystore(InstrumentationRegistry.getInstrumentation().targetContext)
        alias().forEach { if (store.containsAlias(it)) store.deleteEntry(it) }
    }

    @After
    fun tearDown() {
        alias().forEach { if (store.containsAlias(it)) store.deleteEntry(it) }
    }

    // ── Les deux sens ────────────────────────────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Ce que la 2.0.x a scellé, la 3.0.0 doit l'ouvrir.**
     *
     * La clé est créée et le scellé produit **exactement comme le fait le pont publié** — même
     * spécification, même `Cipher`, aucune donnée additionnelle authentifiée. Puis c'est le portage
     * qui ouvre. C'est le sens de lecture qui décide si un coffre existant survit à la bascule.
     */
    @Test
    fun un_scelle_produit_comme_le_PONT_PUBLIE_le_produit_s_ouvre_par_le_portage() {
        creerLaCleCommeLePontPublie(ALIAS)
        val scelle = scellerCommeLePontPublie(ALIAS, CLAIR)

        val relu = portage.open(ALIAS, SealedByKeystore(scelle.first, scelle.second))

        assertThat(relu).isEqualTo(CLAIR)
    }

    /**
     * 🔴 Le symétrique, et il n'est pas redondant : une bascule n'est pas toujours définitive.
     *
     * Quelqu'un peut réinstaller la 2.0.x après avoir essayé la 3.0.0. Ce que le portage scelle doit
     * donc rester lisible par le code publié, qui descelle par un `GCMParameterSpec(128, nonce)` nu.
     */
    @Test
    fun un_scelle_du_portage_s_ouvre_comme_le_PONT_PUBLIE_l_ouvre() {
        portage.creerOuIgnorer(ALIAS)
        val scelle = portage.seal(ALIAS, CLAIR)

        val relu = desceller(ALIAS, scelle.ciphertext, scelle.nonce)

        assertThat(relu).isEqualTo(CLAIR)
    }

    /**
     * ⚠️⚠️ **Le contrôle négatif, sans lequel les deux cas ci-dessus ne prouvent rien.**
     *
     * Ils montrent qu'un descellement réussit. Encore faut-il qu'il puisse échouer : une
     * implémentation qui rendrait le clair sans regarder la clé les passerait tous les deux. Ici, un
     * scellé fait sous **une autre clé** doit être refusé.
     */
    @Test
    fun un_scelle_fait_sous_une_AUTRE_cle_est_refuse() {
        creerLaCleCommeLePontPublie(ALIAS)
        creerLaCleCommeLePontPublie(ALIAS_TEMOIN)
        val scelleAilleurs = scellerCommeLePontPublie(ALIAS_TEMOIN, CLAIR)

        var refuse = false
        try {
            portage.open(ALIAS, SealedByKeystore(scelleAilleurs.first, scelleAilleurs.second))
        } catch (e: Exception) {
            refuse = true
        }

        assertThat(refuse).isTrue()
    }

    // ── Les valeurs qui décident, comparées une par une ──────────────────────────────────────────

    /**
     * 🔴🔴 **L'alias, et c'est le paramètre le plus silencieux de tous.**
     *
     * S'il diverge, rien ne casse : le portage ne **trouve** simplement pas la clé du coffre, en
     * crée une neuve, et l'ancienne reste dans le Keystore à côté d'un coffre devenu inouvrable. Pas
     * d'exception, pas de message — une note perdue.
     *
     * ⚠️ La forme est transcrite de `KeystoreBridge.kt:24` : *« une clé AES-256-GCM par coffre PIN,
     * alias = `vault_pin_<folder_id>` »*.
     */
    @Test
    fun l_alias_est_exactement_celui_du_pont_publie() {
        val identifiant = "a1b2c3d4-e5f6-4789-abcd-ef0123456789"

        assertThat(VaultParams.pinKeystoreAlias(identifiant)).isEqualTo("vault_pin_$identifiant")
        assertThat(VaultParams.PIN_KEYSTORE_ALIAS_PREFIX).isEqualTo("vault_pin_")
    }

    /**
     * 🔴 **Les deux clés, comparées sur ce que le matériel en dit — pas sur des constantes.**
     *
     * Lire `VaultParams` d'un côté et une constante recopiée de l'autre ne compare que deux
     * littéraux. Ici, chaque clé est **créée pour de vrai**, puis relue par `KeyInfo` : c'est le
     * Keystore lui-même qui dit la taille, le mode et le remplissage. Une divergence de
     * spécification apparaît donc même si les deux fichiers se ressemblent.
     *
     * ⚠️ Le caractère randomisé n'est pas dans `KeyInfo` ; il est mesuré ailleurs, par
     * `AndroidVaultKeystoreTest.deux_scellements_du_meme_clair_different`.
     */
    @Test
    fun la_cle_du_portage_et_celle_du_pont_publie_ont_la_MEME_specification() {
        creerLaCleCommeLePontPublie(ALIAS)
        portage.creerOuIgnorer(ALIAS_TEMOIN)

        val duPont = infoDeLaCle(ALIAS)
        val duPortage = infoDeLaCle(ALIAS_TEMOIN)

        assertThat(duPortage.keySize).isEqualTo(duPont.keySize)
        assertThat(duPortage.blockModes.toList()).isEqualTo(duPont.blockModes.toList())
        assertThat(duPortage.encryptionPaddings.toList()).isEqualTo(duPont.encryptionPaddings.toList())
        assertThat(duPortage.isUserAuthenticationRequired)
            .isEqualTo(duPont.isUserAuthenticationRequired)

        // ⚠️ Et les valeurs elles-mêmes, transcrites du pont publié : sans elles, deux clés
        // également fausses se ressembleraient parfaitement.
        assertThat(duPortage.keySize).isEqualTo(256)
        assertThat(duPortage.blockModes.toList()).containsExactly(KeyProperties.BLOCK_MODE_GCM)
        assertThat(duPortage.encryptionPaddings.toList())
            .containsExactly(KeyProperties.ENCRYPTION_PADDING_NONE)
        assertThat(duPortage.isUserAuthenticationRequired).isFalse()

        // ⚠️ L'étiquette GCM se trompe le plus discrètement : 96 bits au lieu de 128 la tronque et
        // refuse tout ce que l'autre moitié a scellé.
        assertThat(VaultParams.TAG_BITS).isEqualTo(TAG_BITS_PUBLIE)
    }

    /**
     * ⚠️ **Le nonce vient du Keystore, des deux côtés, et fait 12 octets.**
     *
     * Le pont publié ne fournit jamais d'IV : la clé exige un chiffrement randomisé, donc le
     * Keystore en tire un. Une implémentation qui en fournirait un — même correct — lèverait à
     * l'exécution, et un nonce d'une autre longueur casserait le descellement du côté d'en face.
     */
    @Test
    fun le_nonce_fait_douze_octets_des_DEUX_cotes() {
        creerLaCleCommeLePontPublie(ALIAS)
        val duPont = scellerCommeLePontPublie(ALIAS, CLAIR)

        portage.creerOuIgnorer(ALIAS_TEMOIN)
        val duPortage = portage.seal(ALIAS_TEMOIN, CLAIR)

        assertThat(duPont.second.size).isEqualTo(12)
        assertThat(duPortage.nonce.size).isEqualTo(12)
    }

    // ── Le pont publié, transcrit ────────────────────────────────────────────────────────────────

    /**
     * `KeystoreBridge.createKey`, lignes 216-238, recopié sans rien lire du portage.
     *
     * ⚠️ Pas de StrongBox ici : le pont publié tente StrongBox puis retombe sur le TEE, et le S9 n'en
     * a pas. Le repli est le chemin réel sur cet appareil, et c'est celui qu'on veut exercer.
     */
    private fun creerLaCleCommeLePontPublie(alias: String) {
        val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PUBLIE)
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setUnlockedDeviceRequired(true) }
            .build()
        generateur.init(specification)
        generateur.generateKey()
    }

    /** `KeystoreBridge.wrap`, lignes 290-299. Rend le couple (chiffré, nonce). */
    private fun scellerCommeLePontPublie(alias: String, clair: ByteArray): Pair<ByteArray, ByteArray> {
        val cle = store.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Sans IV explicite : le Keystore en tire un frais. C'est le commentaire du pont publié.
        cipher.init(Cipher.ENCRYPT_MODE, cle)
        val chiffre = cipher.doFinal(clair)
        return chiffre to checkNotNull(cipher.iv) { "missing IV" }
    }

    /** `KeystoreBridge.unwrap`, lignes 301-307. */
    private fun desceller(alias: String, chiffre: ByteArray, nonce: ByteArray): ByteArray {
        val cle = store.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cle, GCMParameterSpec(TAG_BITS_PUBLIE, nonce))
        return cipher.doFinal(chiffre)
    }

    /** Ce que le Keystore dit d'une clé, une fois créée. */
    private fun infoDeLaCle(alias: String): KeyInfo {
        val cle = store.getKey(alias, null) as SecretKey
        val fabrique = SecretKeyFactory.getInstance(cle.algorithm, KEYSTORE_PUBLIE)
        return fabrique.getKeySpec(cle, KeyInfo::class.java) as KeyInfo
    }

    private fun alias() = listOf(ALIAS, ALIAS_TEMOIN)

    private companion object {
        /** Transcrits de `KeystoreBridge.kt` — jamais lus depuis `VaultParams`. */
        const val KEYSTORE_PUBLIE = "AndroidKeyStore"
        const val TAG_BITS_PUBLIE = 128

        const val ALIAS = "vault_pin_parite-test-0000"
        const val ALIAS_TEMOIN = "vault_pin_parite-test-temoin"
        val CLAIR = ByteArray(32) { (it * 13 + 1).toByte() }
    }
}
