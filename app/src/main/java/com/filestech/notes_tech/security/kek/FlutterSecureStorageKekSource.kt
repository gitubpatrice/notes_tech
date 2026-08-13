package com.filestech.notes_tech.security.kek

import android.content.Context
import android.util.Base64
import com.filestech.notes_tech.core.crypto.SecretBytes
import com.filestech.notes_tech.core.crypto.wipe
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Couche ② — lit la KEK **directement dans le stockage de `flutter_secure_storage`**.
 *
 * Pour l'utilisateur qui saute la release passerelle 2.0.4 et passe de la version Flutter à la
 * version Kotlin d'un coup. Cf. `docs/03-KEK-ACQUISITION.md` §2.
 *
 * ## Ce que ce fichier est vraiment
 *
 * La cryptographie d'une bibliothèque tierce, rejouée à l'envers. Ce n'est pas une conception, c'est
 * une **transcription** : chaque constante ci-dessous a été relevée dans les sources Java de
 * `flutter_secure_storage 10.3.1`, à l'emplacement cité, et non déduite d'une documentation.
 *
 * C'est aussi pourquoi cette couche est un **secours** et non le chemin nominal : rien ici ne
 * garantit qu'une version future de la bibliothèque garde ce format. La couche ① existe précisément
 * pour que ce code ne soit presque jamais exécuté.
 *
 * ## ⚠️ Le piège que la lecture des sources a évité
 *
 * L'enveloppe RSA-OAEP utilise **SHA-256 comme condensat principal et SHA-1 pour MGF1**
 * (`KeyCipherImplementationRSAOAEP.java:53`). Écrire `MGF1ParameterSpec.SHA256`, ce que tout le monde
 * ferait de mémoire, produit une `BadPaddingException` — et un `catch` distrait la transformerait en
 * « aucune clé », donc en génération d'une clé neuve, donc en perte de toutes les notes.
 *
 * ## Deux comportements à ne jamais confondre
 *
 * | Situation | Réponse |
 * |---|---|
 * | aucune valeur dans les préférences | `null` — cette source ne détient rien |
 * | valeur présente mais illisible, Keystore muet, algorithmes inattendus | [KekFailure.SourceUnavailable] |
 *
 * La seconde ligne est celle qui compte : **une lecture qui échoue ne prouve pas qu'il n'y a rien.**
 * C'est la faute qui a détruit des données dans Agenda Tech, citée dans [KekSource].
 *
 * ## Cette source n'écrit rien, jamais
 *
 * Elle n'implémente pas [WritableKekSource] et c'est délibéré : écrire dans le stockage d'une autre
 * bibliothèque signifierait reproduire aussi son chemin d'écriture, avec le risque de corrompre ce
 * que la version Flutter relira si l'utilisateur revient en arrière. On lit, on recopie dans la
 * couche ①, on ne touche pas à l'original.
 */
class FlutterSecureStorageKekSource(
    private val context: Context,
    private val keyAliasBase: String = PUBLISHED_APPLICATION_ID,
) : KekSource {

    override val name: String = "flutter_secure_storage"

    override fun load(): ByteArray? {
        val dataPrefs = context.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE)

        // Lecture atomique : deux `getString` successifs pourraient tomber de part et d'autre d'une
        // écriture concurrente de la version Flutter, si elle tourne encore.
        val snapshot = runCatching { dataPrefs.all }.getOrElse { cause ->
            throw KekFailure.SourceUnavailable(name, cause)
        }

        val encodedValue = snapshot[VALUE_KEY] as? String ?: return null

        requireExpectedAlgorithms(snapshot)

        val aesKey = unwrapStorageKey()
        return try {
            decryptHexEncodedKek(encodedValue, aesKey)
        } finally {
            aesKey.wipe()
        }
    }

    /**
     * Refuse de deviner quand la bibliothèque n'a pas utilisé les algorithmes attendus.
     *
     * La bibliothèque **enregistre elle-même** ce qu'elle a employé, dans deux préférences
     * (`StorageCipherFactory.java:13-15`). C'est une chance : plutôt que d'essayer une combinaison,
     * puis une autre, jusqu'à ce que l'une déchiffre, on lit ce qui a servi et on s'arrête si ce
     * n'est pas ce qu'on sait lire.
     *
     * Deux cas mènent ici, et aucun ne doit être traité comme « pas de clé » :
     *
     * - **marqueurs absents** — données écrites par une 9.x, chiffrées en `AES_CBC_PKCS7Padding` +
     *   `RSA_ECB_PKCS1Padding` (`StorageCipherFactory.java:30`). Ne devrait pas exister, Notes Tech
     *   étant sur la 10.x depuis la 0.9.10, mais l'appareil d'un utilisateur ne connaît pas nos
     *   suppositions ;
     * - **marqueurs différents** — version future, ou configuration modifiée.
     *
     * Dans les deux cas, la couche ③ prend le relais : elle refuse proprement et n'écrit rien.
     * Essayer de déchiffrer à l'aveugle donnerait des octets arbitraires qui passeraient peut-être
     * le contrôle de longueur, et ouvriraient une base avec une clé fausse.
     */
    private fun requireExpectedAlgorithms(snapshot: Map<String, *>) {
        val keyAlgorithm = snapshot[ALGORITHM_KEY_MARKER] as? String
        val storageAlgorithm = snapshot[ALGORITHM_STORAGE_MARKER] as? String
        if (keyAlgorithm != EXPECTED_KEY_ALGORITHM || storageAlgorithm != EXPECTED_STORAGE_ALGORITHM) {
            throw KekFailure.SourceUnavailable(
                name,
                IllegalStateException(
                    "combinaison d'algorithmes non prise en charge : cle=$keyAlgorithm stockage=$storageAlgorithm",
                ),
            )
        }
    }

    /**
     * Déballe la clé AES-128 de la bibliothèque, scellée par une clé RSA de l'`AndroidKeyStore`.
     *
     * ⚠️ **16 octets, pas 32** (`StorageCipherImplementationGCM.java:18`). La taille surprend sur un
     * composant qui se présente comme du stockage sécurisé, mais c'est ce qu'il fait, et la lire
     * autrement ne déchiffrerait rien.
     */
    private fun unwrapStorageKey(): ByteArray {
        val alias = "$keyAliasBase$KEY_ALIAS_SUFFIX"
        val keyPrefs = context.getSharedPreferences(KEY_STORAGE_PREFS, Context.MODE_PRIVATE)

        val wrapped = runCatching { keyPrefs.getString(WRAPPED_KEY, null) }
            .getOrElse { cause -> throw KekFailure.SourceUnavailable(name, cause) }
            ?: throw KekFailure.SourceUnavailable(
                name,
                IllegalStateException("valeur presente mais cle AES enveloppee absente"),
            )

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val privateKey = checkNotNull(keyStore.getKey(alias, null)) { "alias absent du Keystore" }

            val cipher = Cipher.getInstance(RSA_TRANSFORMATION, KEYSTORE_RSA_PROVIDER)
            cipher.init(Cipher.UNWRAP_MODE, privateKey, oaepParameters())
            val unwrapped = cipher.unwrap(Base64.decode(wrapped, Base64.DEFAULT), AES, Cipher.SECRET_KEY)
            unwrapped.encoded
        } catch (cause: Exception) {
            // ⚠️ Aucun `return null` ici, et c'est le point le plus important du fichier. Un Keystore
            // momentanement indisponible, un appareil verrouille, une cle invalidee : rien de tout
            // cela ne prouve l'absence de KEK, et le traiter comme une absence conduirait a en
            // generer une neuve — donc a condamner les notes que la vraie cle protegeait.
            throw KekFailure.SourceUnavailable(name, cause)
        }
    }

    /**
     * ⚠️ `MGF1ParameterSpec.SHA1` avec un condensat principal en SHA-256 — relevé dans
     * `KeyCipherImplementationRSAOAEP.java:53`, **pas écrit de mémoire**.
     *
     * L'asymétrie est celle de l'`AndroidKeyStore`, pas un choix de la bibliothèque. Elle ne se
     * devine pas, et s'en écarter rend le déballage impossible.
     */
    private fun oaepParameters(): OAEPParameterSpec =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    /**
     * Déchiffre l'enveloppe `IV (12) ‖ AES-GCM(ciphertext ‖ tag 128 bits)`
     * (`StorageCipherImplementationGCM.java:66-96`), puis décode les 64 caractères hexadécimaux que
     * Notes Tech y a écrits (`vault_service.dart:104`).
     */
    private fun decryptHexEncodedKek(encodedValue: String, aesKey: ByteArray): ByteArray {
        val clearText = decryptEnvelope(encodedValue, aesKey)
        return try {
            parseHexKek(String(clearText, Charsets.UTF_8))
        } finally {
            clearText.wipe()
        }
    }

    /**
     * Ouvre l'enveloppe `IV (12) ‖ AES-GCM(ciphertext ‖ tag 128 bits)`
     * (`StorageCipherImplementationGCM.java:66-96`).
     */
    private fun decryptEnvelope(encodedValue: String, aesKey: ByteArray): ByteArray {
        val envelope = try {
            Base64.decode(encodedValue, Base64.DEFAULT)
        } catch (cause: IllegalArgumentException) {
            throw KekFailure.MalformedKey(name, cause)
        }
        if (envelope.size <= IV_SIZE) throw KekFailure.MalformedKey(name, null)

        return try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, AES),
                GCMParameterSpec(GCM_TAG_BITS, envelope, 0, IV_SIZE),
            )
            cipher.doFinal(envelope, IV_SIZE, envelope.size - IV_SIZE)
        } catch (cause: Exception) {
            // Le tag GCM n'a pas validé, ou le Keystore a rendu une clé qui ne convient pas. On ne
            // sait pas distinguer « donnée corrompue » de « mauvaise clé » : indisponible, donc,
            // jamais « absente ».
            throw KekFailure.SourceUnavailable(name, cause)
        }
    }

    /**
     * Décode les 64 caractères hexadécimaux que Notes Tech écrit (`vault_service.dart:104`).
     *
     * ⚠️ Ici, et seulement ici, un échec vaut [KekFailure.MalformedKey] plutôt que
     * [KekFailure.SourceUnavailable] : le déchiffrement a réussi, donc la clé de déchiffrement était
     * la bonne. Ce qui cloche est la **donnée**, et aucune autre source ne la réparera.
     */
    private fun parseHexKek(hex: String): ByteArray {
        val kek = try {
            SecretBytes.fromHex(hex)
        } catch (cause: IllegalArgumentException) {
            throw KekFailure.MalformedKey(name, cause)
        }
        if (kek.size != KEK_SIZE_BYTES) {
            kek.wipe()
            throw KekFailure.MalformedKey(name, null)
        }
        return kek
    }

    companion object {
        /**
         * L'identifiant de l'application **publiée**, qui n'est pas forcément celui de la build en
         * cours.
         *
         * L'alias du Keystore est construit par `context.getPackageName()` côté bibliothèque
         * (`KeyCipherImplementationRSA18.java:41`). Or la build de portage porte le suffixe `.next`
         * tant que la phase 8 n'a pas eu lieu : lire `packageName` ici donnerait un alias qui
         * n'existe nulle part.
         *
         * Écrire l'identifiant en clair dit ce qu'on cherche : **le matériel que l'application
         * publiée a créé**. Au moment où ce code servira pour de bon, la build aura repris cet
         * identifiant et les deux coïncideront.
         *
         * ⚠️ Ce n'est pas ce qui rend la lecture possible : le Keystore est cloisonné par UID, et
         * une build isolée ne verra jamais le matériel de l'application réelle, quel que soit
         * l'alias demandé. Le paramètre existe pour que les tests puissent viser le leur.
         */
        const val PUBLISHED_APPLICATION_ID = "com.filestech.notes_tech"

        /** `FlutterSecureStorageConfig.java:15` */
        const val DATA_PREFS = "FlutterSecureStorage"

        /** `FlutterSecureStorageConfig.java:184` — sans espace de noms, pas de suffixe. */
        const val KEY_STORAGE_PREFS = "FlutterSecureKeyStorage"

        /** `FlutterSecureStorageConfig.java:16` + `FlutterSecureStorage.java:51` (`prefixe + "_" + cle`). */
        const val VALUE_KEY =
            "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg_notes_tech.vault.kek.v1"

        /** `StorageCipherImplementationGCM.java:21` */
        const val WRAPPED_KEY = "AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK"

        /** `StorageCipherFactory.java:14-15` — la bibliothèque note ce qu'elle a employé. */
        const val ALGORITHM_KEY_MARKER = "FlutterSecureSAlgorithmKey"
        const val ALGORITHM_STORAGE_MARKER = "FlutterSecureSAlgorithmStorage"

        const val EXPECTED_KEY_ALGORITHM = "RSA_ECB_OAEPwithSHA_256andMGF1Padding"
        const val EXPECTED_STORAGE_ALGORITHM = "AES_GCM_NoPadding"

        /** `KeyCipherImplementationRSAOAEP.java:30` */
        const val KEY_ALIAS_SUFFIX = ".FlutterSecureStoragePluginKeyOAEP"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** `KeyCipherImplementationRSAOAEP.java:49` — le fournisseur compte autant que la transformation. */
        private const val KEYSTORE_RSA_PROVIDER = "AndroidKeyStoreBCWorkaround"
        private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"

        private const val AES = "AES"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"

        /** `StorageCipherImplementationGCM.java:99` */
        private const val IV_SIZE = 12

        /** `StorageCipherImplementationGCM.java:19` */
        private const val GCM_TAG_BITS = 128

        /** La KEK de Notes Tech : 32 octets, écrits en 64 caractères hexadécimaux. */
        private const val KEK_SIZE_BYTES = 32
    }
}
