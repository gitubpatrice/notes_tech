package com.filestech.notes_tech.security.kek

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * Écrit le stockage **exactement comme `flutter_secure_storage 10.3.1`**, pour que la couche ② soit
 * éprouvée contre le vrai format et non contre l'idée qu'on s'en fait.
 *
 * ## Pourquoi ce fichier existe
 *
 * `FlutterSecureStorageKekSource` transcrit à l'envers la cryptographie d'une bibliothèque tierce.
 * Un test qui préparerait les données avec les mêmes constantes que le lecteur ne prouverait rien :
 * il vérifierait que ma transcription est cohérente avec elle-même.
 *
 * Ce fichier est donc écrit **depuis les sources Java de la bibliothèque**, à l'endroit cité pour
 * chaque étape, et non depuis `FlutterSecureStorageKekSource`. Même principe que
 * `LegacyDatabaseFixture`, qui recopie le DDL de sqflite plutôt que de laisser Room créer la base.
 *
 * ⚠️ **C'est une copie, donc une source de divergence possible.** Si la bibliothèque change de
 * format, ce fichier doit changer aussi — sinon les tests continueront de passer sur un format qui
 * n'existe plus. La procédure de relevé est dans `docs/03-KEK-ACQUISITION.md`.
 *
 * ## Ce que le test peut exercer, et ce qu'il ne peut pas
 *
 * L'`AndroidKeyStore` est cloisonné par UID : cette fixture crée son alias dans le trousseau de la
 * build de test, jamais dans celui de l'application publiée. Elle prouve donc que **le format est
 * lu correctement**, pas que la build isolée peut atteindre le matériel de l'application réelle —
 * ce qui est impossible par construction et ne se vérifiera qu'à la bascule (phase 8).
 */
object FlutterSecureStorageFixture {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** `StorageCipherImplementationGCM.java:18` — 16 octets, donc AES-128. */
    private const val AES_KEY_SIZE = 16

    /** `StorageCipherImplementationGCM.java:99` */
    private const val IV_SIZE = 12

    /** `StorageCipherImplementationGCM.java:19` */
    private const val GCM_TAG_BITS = 128

    /**
     * Comment MGF1 est paramétré dans l'enveloppe RSA-OAEP.
     *
     * ⚠️ Exposé comme paramètre pour une seule raison : permettre au test d'écrire avec **SHA-256**
     * et de vérifier que la lecture échoue alors. C'est ce contre-test qui prouve que le `SHA-1` de
     * `FlutterSecureStorageKekSource` n'est pas décoratif.
     */
    enum class Mgf1Digest(val spec: MGF1ParameterSpec) {
        /** Ce que fait réellement la bibliothèque — `KeyCipherImplementationRSAOAEP.java:53`. */
        SHA1(MGF1ParameterSpec.SHA1),

        /** Ce que tout le monde écrirait de mémoire. Doit rendre la valeur illisible. */
        SHA256(MGF1ParameterSpec.SHA256),
    }

    /**
     * Prépare un stockage complet et lisible.
     *
     * @param kekHex les 64 caractères hexadécimaux que Notes Tech y écrit (`vault_service.dart:104`).
     */
    fun seed(
        context: Context,
        keyAliasBase: String,
        kekHex: String,
        mgf1: Mgf1Digest = Mgf1Digest.SHA1,
        keyAlgorithmMarker: String? = FlutterSecureStorageKekSource.EXPECTED_KEY_ALGORITHM,
        storageAlgorithmMarker: String? = FlutterSecureStorageKekSource.EXPECTED_STORAGE_ALGORITHM,
    ) {
        val alias = keyAliasBase + FlutterSecureStorageKekSource.KEY_ALIAS_SUFFIX
        createRsaKey(alias)

        // 1. La clé AES de la bibliothèque, scellée par la clé RSA du Keystore.
        //    `StorageCipherImplementationGCM.java:46-52`
        val aesKey = ByteArray(AES_KEY_SIZE).also(SecureRandom()::nextBytes)
        val secretKey = SecretKeySpec(aesKey, "AES")

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val publicKey = keyStore.getCertificate(alias).publicKey
        val wrapCipher = Cipher.getInstance("RSA/ECB/OAEPPadding", "AndroidKeyStoreBCWorkaround")
        wrapCipher.init(Cipher.WRAP_MODE, publicKey, oaep(mgf1))
        val wrapped = wrapCipher.wrap(secretKey)

        context.getSharedPreferences(FlutterSecureStorageKekSource.KEY_STORAGE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(FlutterSecureStorageKekSource.WRAPPED_KEY, Base64.encodeToString(wrapped, Base64.DEFAULT))
            .commit()

        // 2. La valeur, chiffrée `IV ‖ AES-GCM(...)`. `StorageCipherImplementationGCM.java:66-81`
        val iv = ByteArray(IV_SIZE).also(SecureRandom()::nextBytes)
        val dataCipher = Cipher.getInstance("AES/GCM/NoPadding")
        dataCipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val payload = dataCipher.doFinal(kekHex.toByteArray(Charsets.UTF_8))
        val envelope = iv + payload

        val editor = context.getSharedPreferences(
            FlutterSecureStorageKekSource.DATA_PREFS,
            Context.MODE_PRIVATE,
        ).edit()
        editor.putString(
            FlutterSecureStorageKekSource.VALUE_KEY,
            Base64.encodeToString(envelope, Base64.DEFAULT),
        )
        // 3. Les marqueurs d'algorithme. `StorageCipherFactory.java:120-121`
        keyAlgorithmMarker?.let { editor.putString(FlutterSecureStorageKekSource.ALGORITHM_KEY_MARKER, it) }
        storageAlgorithmMarker?.let {
            editor.putString(FlutterSecureStorageKekSource.ALGORITHM_STORAGE_MARKER, it)
        }
        editor.commit()
    }

    /** Remplace la valeur chiffrée par des octets arbitraires, en gardant tout le reste intact. */
    fun corruptStoredValue(context: Context) {
        context.getSharedPreferences(FlutterSecureStorageKekSource.DATA_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(
                FlutterSecureStorageKekSource.VALUE_KEY,
                Base64.encodeToString(ByteArray(48) { it.toByte() }, Base64.DEFAULT),
            )
            .commit()
    }

    /** Retire la clé AES enveloppée, en laissant la valeur en place. */
    fun removeWrappedKey(context: Context) {
        context.getSharedPreferences(FlutterSecureStorageKekSource.KEY_STORAGE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(FlutterSecureStorageKekSource.WRAPPED_KEY)
            .commit()
    }

    fun clear(context: Context, keyAliasBase: String) {
        context.getSharedPreferences(FlutterSecureStorageKekSource.DATA_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(FlutterSecureStorageKekSource.KEY_STORAGE_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(keyAliasBase + FlutterSecureStorageKekSource.KEY_ALIAS_SUFFIX)
        }
    }

    /**
     * Crée la paire RSA **avec les paramètres exacts de la bibliothèque** —
     * `KeyCipherImplementationRSAOAEP.java:36-44`.
     *
     * `setDigests(SHA256)` et `ENCRYPTION_PADDING_RSA_OAEP` ne sont pas indifférents : l'
     * `AndroidKeyStore` refuse à l'usage toute opération qui sort de ce que la clé autorise. Une
     * clé créée avec d'autres attributs ferait échouer le déballage pour une raison qui n'aurait
     * rien à voir avec le format des données.
     */
    private fun createRsaKey(alias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) return

        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT,
        )
            .setCertificateSubject(X500Principal("CN=$alias"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setCertificateSerialNumber(BigInteger.valueOf(1))
            .setCertificateNotBefore(start.time)
            .setCertificateNotAfter(end.time)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun oaep(mgf1: Mgf1Digest) =
        javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1", mgf1.spec, PSource.PSpecified.DEFAULT)
}
