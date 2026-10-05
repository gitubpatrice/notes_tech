package com.filestech.notes_tech.security.kek

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * **La couche ① de l'acquisition de la KEK — la moitié LECTRICE du chemin de migration.**
 *
 * 🔴🔴 **Cette classe n'avait aucun test.** La passerelle 2.0.4 écrit un scellé, cette classe le
 * lit, et c'est par ce couple que passent les notes d'un utilisateur qui met à jour. La moitié
 * écrivante est mesurée dans `notes_tech` ; celle-ci ne l'était pas, alors que c'est elle qui décide
 * si la base s'ouvre ou non.
 *
 * ## Ce que ces cas cherchent à faire échouer
 *
 * 1. **Le contrat entre les deux applications.** Le premier cas ne passe **pas** par `store()` : il
 *    scelle exactement comme `KeystoreBridge.sealDatabaseKek` de la 2.0.4 — même alias, même
 *    `Base64.NO_WRAP`, 32 octets bruts — puis demande à cette classe de relire. Passer par `store()`
 *    mesurerait que la classe se relit elle-même, ce qui est vrai de n'importe quelle paire
 *    symétrique et ne dit rien du chemin réel.
 * 2. **Le piège documenté de la passerelle** : sceller les **64 caractères hexadécimaux** au lieu
 *    des 32 octets. Le scellé serait cryptographiquement valide et la clé fausse. C'est le seul
 *    défaut de la migration qui ne se rattrape pas — d'où un cas qui l'exerce.
 * 3. **« Présent mais inutilisable » ≠ « absent ».** Trois états s'y confondaient avant le correctif
 *    du 2026-08-13, relevé en CRITIQUE par deux relectures indépendantes, et **aucun n'avait de
 *    test**. Un couple incomplet lu comme une absence mène à `generateAndPersist` : une base
 *    existante deviendrait illisible.
 *
 * ## ⚠️ Isolation, et ce qui n'est PAS exercé
 *
 * Les préférences sont détournées vers un fichier de test par un [ContextWrapper] : sans cela, la
 * suite écraserait le scellé réel de la build sous test et rendrait sa propre base illisible.
 *
 * ⚠️⚠️ **L'alias Keystore, ici, n'est pas isolé** — la classe accepte un alias depuis le 2026-09-26
 * (`PasserelleCleDisparueTest` s'en sert, lui, pour supprimer une clé). Les cas
 * ci-dessous se servent de l'alias réel **sans jamais le détruire** : `store()` réutilise la clé existante
 * (`existingKey() ?: createKey()`). `replaceKeyAndStore`, qui supprime l'alias, n'est
 * **volontairement pas** exercé ici : il effacerait la clé de la build sous test. *Le dire vaut
 * mieux que de laisser croire que tout est couvert.*
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSealedKekSourceTest {

    private val reel: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefs: SharedPreferences
    private lateinit var source: KeystoreSealedKekSource

    /** Toute demande de préférences est détournée vers un fichier de test. Voir le KDoc de classe. */
    private class ContexteIsole(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(PREFS_DE_TEST, mode)
    }

    @Before
    fun preparer() {
        prefs = reel.getSharedPreferences(PREFS_DE_TEST, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        source = KeystoreSealedKekSource(ContexteIsole(reel))
    }

    @After
    fun nettoyer() {
        prefs.edit().clear().commit()
    }

    // ───────────────────────────────────────────────────────────────────────────────────────────
    // 🔴 Le chemin de migration lui-même
    // ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Le cas qui compte : un scellé écrit comme la 2.0.4 l'écrit, relu par la 3.0.0.**
     *
     * Il ne passe **pas** par `store()`. Il reproduit la passerelle : `Base64.NO_WRAP`, mêmes clés
     * de préférences, 32 octets bruts scellés sous l'alias partagé. C'est la seule forme de ce test
     * qui mesure le contrat entre deux applications plutôt que la symétrie d'une seule.
     */
    @Test
    fun un_scelle_ecrit_comme_par_la_passerelle_est_relu_octet_pour_octet() {
        val kek = kekTemoin()

        ecrireCommeLaPasserelle(kek)

        val relue = source.load()
        assertThat(relue).isNotNull()
        assertThat(relue).isEqualTo(kek)
    }

    /**
     * 🔴 **Le piège n°1 de la procédure : sceller la chaîne hexadécimale.**
     *
     * `flutter_secure_storage` contient les 64 caractères hexadécimaux de la KEK. Les sceller tels
     * quels produit un clair de 64 octets — cryptographiquement valide, et **faux**. Si cette classe
     * l'acceptait, la base s'ouvrirait avec une mauvaise clé, ou pire : rien ne signalerait l'erreur
     * et aucune bascule vers la couche ② n'aurait lieu.
     */
    @Test
    fun un_clair_de_mauvaise_taille_est_REFUSE_et_non_accepte() {
        val hexadecimal = kekTemoin().joinToString("") { "%02x".format(it) }.toByteArray()
        assertThat(hexadecimal).hasLength(64)

        ecrireCommeLaPasserelle(hexadecimal)

        assertThrows(KekFailure.MalformedKey::class.java) { source.load() }
    }

    /** L'aller-retour de la classe avec elle-même — utile, mais bien plus faible que le premier cas. */
    @Test
    fun store_puis_load_rend_la_meme_cle() {
        val kek = kekTemoin()

        source.store(kek.copyOf())

        assertThat(source.load()).isEqualTo(kek)
    }

    // ───────────────────────────────────────────────────────────────────────────────────────────
    // 🔴 « Présent mais inutilisable » n'est PAS « absent »
    // ───────────────────────────────────────────────────────────────────────────────────────────

    /** ⚠️ La seule absence véritable : rien du tout. Elle doit rendre `null`, sans lever. */
    @Test
    fun sans_aucune_valeur_load_rend_null() {
        assertThat(source.load()).isNull()
    }

    /**
     * 🔴🔴 **Un couple incomplet lu comme une absence rendrait une base existante illisible.**
     *
     * `null` se lit « aucune clé n'existe », donc `KekRepository` en génère une neuve et la base
     * chiffrée par l'ancienne devient définitivement inouvrable. Le défaut a existé ici, relevé en
     * CRITIQUE par deux relectures indépendantes le 2026-08-13 — et **aucun test ne le retenait**.
     */
    @Test
    fun un_couple_incomplet_n_est_PAS_une_absence() {
        val kek = kekTemoin()
        ecrireCommeLaPasserelle(kek)

        for (orpheline in listOf(KeystoreSealedKekSource.KEY_NONCE, KeystoreSealedKekSource.KEY_BLOB)) {
            prefs.edit().remove(orpheline).commit()

            assertThrows(KekFailure.SourceUnavailable::class.java) { source.load() }

            ecrireCommeLaPasserelle(kek)
        }
    }

    /** ⚠️ Une entrée d'un type inattendu non plus : elle ne prouve pas l'absence de clé. */
    @Test
    fun un_scelle_d_un_type_inattendu_n_est_PAS_une_absence() {
        ecrireCommeLaPasserelle(kekTemoin())
        prefs.edit().remove(KeystoreSealedKekSource.KEY_BLOB).putInt(KeystoreSealedKekSource.KEY_BLOB, 7).commit()

        assertThrows(KekFailure.SourceUnavailable::class.java) { source.load() }
    }

    /**
     * 🔴 **Une valeur illisible ne doit JAMAIS se lire comme une absence** — c'est l'invariant, et
     * c'est le seul qui compte ici.
     *
     * ⚠️⚠️ **Ce cas attendait `MalformedKey` et la mesure a dit `SourceUnavailable`.** Le test avait
     * tort, pas le code. `decodeBase64` appelle `Base64.decode(value, Base64.DEFAULT)`, dont le
     * décodeur d'Android est **tolérant** : il ne lève pas sur cette entrée, il rend des octets, et
     * l'échec tombe un cran plus loin, sur le déchiffrement GCM. Le chemin `MalformedKey` de
     * `decodeBase64` est donc bien plus étroit qu'il n'y paraît à la lecture.
     *
     * Et c'est la **bonne** issue : `SourceUnavailable` fait essayer la source suivante — la couche
     * ② — au lieu de conclure. L'assertion porte donc sur ce qui protège les notes, et non sur le
     * nom d'une classe d'exception : ne pas rendre `null`, et rester une `KekFailure`.
     */
    @Test
    fun une_valeur_illisible_n_est_PAS_une_absence() {
        prefs.edit()
            .putString(KeystoreSealedKekSource.KEY_BLOB, "((( pas du base64 )))")
            .putString(KeystoreSealedKekSource.KEY_NONCE, "((( pas du base64 )))")
            .commit()

        assertThrows(KekFailure::class.java) { source.load() }
    }

    /**
     * ⚠️⚠️ **Mesure d'une affirmation de la documentation, et elle est FAUSSE.**
     *
     * `docs/10-PASSERELLE-2.0.4.md` écrit que `Base64.DEFAULT` — qui insère des retours à la ligne —
     * ferait échouer « le décodage strict côté Kotlin ». Or ce décodeur appelle
     * `Base64.decode(value, Base64.DEFAULT)`, et le décodeur d'Android **ignore les blancs**.
     *
     * Ce cas mesure ce qui se passe réellement. Il ne conclut pas que `NO_WRAP` est facultatif à
     * l'écriture — c'est la bonne hygiène, et le contrat reste écrit ainsi — mais il empêche de
     * croire qu'un garde existe là où il n'y en a pas. *Une garde supposée est pire qu'une garde
     * absente : on cesse de la chercher ailleurs.*
     */
    @Test
    fun un_scelle_encode_avec_des_retours_a_la_ligne_est_TOLERE_par_le_decodeur() {
        val kek = kekTemoin()
        val (chiffre, nonce) = sceller(kek)
        val avecRetours = Base64.encodeToString(chiffre, Base64.DEFAULT)
        assertThat(avecRetours).contains("\n")

        prefs.edit()
            .putString(KeystoreSealedKekSource.KEY_BLOB, avecRetours)
            .putString(KeystoreSealedKekSource.KEY_NONCE, Base64.encodeToString(nonce, Base64.DEFAULT))
            .commit()

        assertThat(source.load()).isEqualTo(kek)
    }

    // ───────────────────────────────────────────────────────────────────────────────────────────

    /** ⚠️ Ni tout à zéro ni une suite triviale : un tampon jamais rempli passerait les deux. */
    private fun kekTemoin(): ByteArray =
        ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { ((it * 37 + 11) and 0xFF).toByte() }

    /**
     * Scelle [clair] **exactement comme `KeystoreBridge.sealDatabaseKek` de la 2.0.4**, et l'écrit
     * dans les préférences de test.
     *
     * ⚠️ Réutilise la clé existante quand il y en a une : détruire l'alias ferait perdre le scellé
     * réel de la build sous test. Voir le KDoc de classe.
     */
    private fun ecrireCommeLaPasserelle(clair: ByteArray) {
        val (chiffre, nonce) = sceller(clair)
        prefs.edit()
            .putString(KeystoreSealedKekSource.KEY_BLOB, Base64.encodeToString(chiffre, Base64.NO_WRAP))
            .putString(KeystoreSealedKekSource.KEY_NONCE, Base64.encodeToString(nonce, Base64.NO_WRAP))
            .commit()
    }

    private fun sceller(clair: ByteArray): Pair<ByteArray, ByteArray> {
        val cle = cleDeScellage()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, cle) }
        return cipher.doFinal(clair) to cipher.iv
    }

    /**
     * La clé de l'alias partagé, créée au besoin **par la classe sous test elle-même** : un
     * `store()` d'amorçage garantit que l'alias existe avec les mêmes paramètres que la production,
     * sans dupliquer ici une `KeyGenParameterSpec` qui pourrait diverger.
     */
    private fun cleDeScellage(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KeystoreSealedKekSource.KEY_ALIAS)) {
            source.store(kekTemoin())
            prefs.edit().clear().commit()
            ks.load(null)
        }
        return (ks.getEntry(KeystoreSealedKekSource.KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private companion object {
        /**
         * ⚠️ **Pas `notes_tech.kek`.** Écrire dans le vrai fichier écraserait le scellé de la build
         * sous test et rendrait sa base illisible — la suite détruirait ce qu'elle vérifie.
         */
        const val PREFS_DE_TEST = "notes_tech.kek.test"
    }
}
