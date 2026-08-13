package com.filestech.notes_tech.security.kek

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.filestech.notes_tech.core.crypto.wipe
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import timber.log.Timber
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Couche ① — la KEK scellée par une clé de l'`AndroidKeyStore`, sous notre propre format.
 *
 * C'est la **source autoritaire** : celle qu'écrit la release passerelle 2.0.4 côté Flutter, et
 * celle qu'utilise une installation neuve. Elle n'a besoin que d'API de plateforme, ce qui la rend
 * lisible et testable sans dépendre du format interne d'une bibliothèque tierce.
 *
 * ## Contrat de format — la passerelle 2.0.4 doit écrire exactement ceci
 *
 * | Élément | Valeur |
 * |---|---|
 * | Alias `AndroidKeyStore` | [KEY_ALIAS] |
 * | Algorithme | AES-256-GCM, tag 128 bits, nonce généré par le Keystore |
 * | Clair scellé | les **32 octets bruts** de la KEK (pas sa forme hexadécimale) |
 * | Fichier de préférences | [PREFS_NAME] |
 * | Clés | [KEY_BLOB], [KEY_NONCE] |
 * | Encodage | `Base64.NO_WRAP` |
 *
 * Le scellage est réalisé côté Flutter par `KeystoreBridge.kt`, qui expose déjà `wrap`/`unwrap` —
 * 235 lignes de Kotlin en production depuis la v0.9. La passerelle n'ajoute qu'un appel et une
 * écriture de préférences ; elle **ne retire rien** de `flutter_secure_storage`, pour qu'un retour
 * en arrière reste possible.
 *
 * ## Ce qui est délibérément absent
 *
 * Pas de `setUserAuthenticationRequired` : la KEK ouvre la base au démarrage, avant toute
 * interaction. Exiger une authentification ici imposerait une invite biométrique au lancement,
 * alors que le facteur d'authentification du produit est le verrouillage de coffre, pas l'ouverture
 * de l'application.
 */
class KeystoreSealedKekSource(
    private val context: Context,
) : WritableKekSource {

    override val name: String get() = "keystore"

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ByteArray? {
        val scelle = readSealedValue() ?: return null

        // Un scellé présent sans sa clé Keystore signifie que l'OS a détruit la clé — changement
        // d'écran de verrouillage, restauration partielle. Le scellé est alors définitivement
        // indéchiffrable, mais ce n'est PAS à cette classe de conclure : elle répond « je n'ai
        // rien », et c'est [KekRepository] qui décide, en fonction de l'existence de la base, si
        // c'est bénin ou fatal.
        val secretKey = existingKey() ?: return null

        val kek = try {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, scelle.nonce))
                doFinal(scelle.ciphertext)
            }
        } catch (e: GeneralSecurityException) {
            // Distinction essentielle (cf. KDoc de [KekSource]) : un échec de déchiffrement peut
            // venir d'un Keystore momentanément indisponible. On ne rend surtout pas `null`, qui
            // se lirait comme « aucune clé n'existe ».
            throw KekFailure.SourceUnavailable(name, e)
        }

        if (kek.size != SqlCipherRawKey.KEY_SIZE_BYTES) {
            kek.wipe()
            throw KekFailure.MalformedKey(name, null)
        }
        return kek
    }

    /**
     * Lit le couple (scellé, nonce) en **une seule** lecture instantanée.
     *
     * `getAll()` rend une copie prise sous le verrou interne des préférences, donc le couple est
     * forcément cohérent. Deux `getString` successifs peuvent, eux, encadrer une écriture et
     * rendre le scellé d'avant avec le nonce d'après : le déchiffrement GCM échoue alors, et
     * l'ouverture est refusée alors qu'une clé valide existe bel et bien.
     *
     * Constat remonté par la relecture externe (GPT-5.2, 2026-08-13), §4.2.
     */
    private fun readSealedValue(): SealedValue? {
        val snapshot = prefs.all
        val blob = snapshot[KEY_BLOB] as? String ?: return null
        val nonce = snapshot[KEY_NONCE] as? String ?: return null
        return SealedValue(ciphertext = decodeBase64(blob), nonce = decodeBase64(nonce))
    }

    private class SealedValue(val ciphertext: ByteArray, val nonce: ByteArray)

    /**
     * `@Synchronized` : deux écritures concurrentes sur le **même alias** peuvent s'entrelacer —
     * l'une crée sa clé, l'autre la remplace, et les préférences finissent avec un scellé produit
     * par une clé que le Keystore ne détient plus. Le couple devient indéchiffrable.
     *
     * Constat remonté par la relecture externe (GPT-5.2, 2026-08-13), §1.2. Le verrou est ici en
     * plus de celui de `KekRepository.acquire` : cette classe est publique et ne doit pas dépendre
     * de la discipline de ses appelants.
     */
    @Synchronized
    override fun store(kek: ByteArray) {
        require(kek.size == SqlCipherRawKey.KEY_SIZE_BYTES) {
            "KEK de taille invalide : ${kek.size} octets"
        }
        try {
            val key = existingKey() ?: createKey()
            val cipher = Cipher.getInstance(AES_GCM).apply {
                // Sans IV explicite : le Keystore en génère un frais, ce qu'impose
                // `setRandomizedEncryptionRequired(true)`. Réutiliser un nonce en GCM est
                // catastrophique, et cette contrainte rend la faute impossible.
                init(Cipher.ENCRYPT_MODE, key)
            }
            val ciphertext = cipher.doFinal(kek)
            val iv = requireNotNull(cipher.iv) { "le Keystore n'a pas fourni de nonce" }

            // `commit()` et non `apply()` : l'appelant vient peut-être de générer cette clé et va
            // s'en servir pour créer la base. Si le processus meurt entre les deux, `apply()`
            // (asynchrone) laisserait une base chiffrée par une clé jamais persistée — donc
            // illisible à jamais. On paie l'écriture synchrone pour supprimer cette fenêtre.
            val written = prefs.edit()
                .putString(KEY_BLOB, encodeBase64(ciphertext))
                .putString(KEY_NONCE, encodeBase64(iv))
                .commit()
            if (!written) throw KekFailure.SourceUnavailable(name, null)
        } catch (e: GeneralSecurityException) {
            throw KekFailure.SourceUnavailable(name, e)
        }
    }

    /**
     * ⚠️ Destructif — lire le contrat sur [WritableKekSource.replaceKeyAndStore] avant d'appeler.
     *
     * L'ordre compte. Le scellé est effacé **avant** la clé : interrompu entre les deux, il reste
     * une clé sans scellé, ce que [load] traite comme « je n'ai rien », donc un état récupérable.
     * Dans l'autre sens, il resterait un scellé sans clé — indéchiffrable, et [load] lèverait une
     * indisponibilité au lieu de laisser le chemin première installation se dérouler.
     */
    @Synchronized
    override fun replaceKeyAndStore(kek: ByteArray) {
        try {
            prefs.edit().remove(KEY_BLOB).remove(KEY_NONCE).commit()
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                Timber.w("clé de scellage préexistante supprimée — aucune base à protéger")
                ks.deleteEntry(KEY_ALIAS)
            }
        } catch (e: GeneralSecurityException) {
            throw KekFailure.SourceUnavailable(name, e)
        }
        store(kek)
    }

    private fun existingKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(KEY_ALIAS, null) as? SecretKey
    } catch (e: GeneralSecurityException) {
        throw KekFailure.SourceUnavailable(name, e)
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /**
     * `Base64.NO_WRAP` : sans lui, `Base64.DEFAULT` insère des retours à la ligne, et une valeur
     * relue par un lecteur strict deviendrait invalide. Le décodage tolère les deux, l'encodage
     * n'en produit qu'une seule forme.
     */
    private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /**
     * Type de retour **non nullable** : cette fonction ne rend jamais `null`, elle lève.
     *
     * Elle était déclarée nullable, ce qui poussait ses appelants à écrire un `?: return null`
     * inatteignable — du code mort qui donnait à lire un chemin inexistant, et faisait passer une
     * corruption pour une absence de clé.
     */
    private fun decodeBase64(value: String): ByteArray = try {
        Base64.decode(value, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        // Valeur corrompue : ce n'est pas une indisponibilité. On laisse [KekRepository] trancher
        // selon l'existence de la base.
        throw KekFailure.MalformedKey(name, e)
    }

    companion object {
        /** ⚠️ Doit correspondre à l'alias écrit par la passerelle 2.0.4. */
        const val KEY_ALIAS = "notes_tech.db.kek.v1"

        const val PREFS_NAME = "notes_tech.kek"
        const val KEY_BLOB = "db_kek_v1.blob"
        const val KEY_NONCE = "db_kek_v1.nonce"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val AES_KEY_BITS = 256
    }
}
