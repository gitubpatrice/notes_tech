package com.filestech.notes_tech.security.kek

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.filestech.notes_tech.core.crypto.wipe
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import timber.log.Timber
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
 *
 * 🔴 **No `setUnlockedDeviceRequired` either, and deliberately** (security audit of 2026-09-26, K3).
 * It would make this key usable only on an unlocked phone — and Android DELETES such a key when the
 * screen lock is removed (measured on API 34, D-026): the whole database would be lost with it. The
 * price is written here so that nobody "hardens" this spec: on a locked phone, code running under the
 * app's UID can use the key; the vaults keep their own secret, and the notes it exposes are those the
 * app declares without a password.
 *
 * ⚠️ The 2.0.4-2.0.9 bridge DID set it (`KeystoreBridge.kt:241-242` at v2.0.9). When Android deletes
 * that key, [load] answers "nothing here", the `flutter_secure_storage` copy gives the key, and
 * `KekRepository.promoteToPrimary` reseals it under a key created HERE, without the attribute: the
 * database is saved by that copy — see `FlutterSecureStorageKekSource`, which must therefore stay.
 */
class KeystoreSealedKekSource(private val context: Context) : WritableKekSource {

    override val name: String get() = "keystore"

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ByteArray? {
        val scelle = readSealedValue() ?: return null

        // Un scellé présent sans sa clé Keystore signifie que l'OS a détruit la clé — RETRAIT du
        // verrouillage d'écran pour une clé qui exigeait un appareil déverrouillé (celle de la
        // passerelle 2.0.4 ; un simple changement de code la garde, mesuré le 2026-09-25, D-026),
        // restauration partielle. Le scellé est alors définitivement
        // indéchiffrable, mais ce n'est PAS à cette classe de conclure : elle répond « je n'ai
        // rien », et c'est [KekRepository] qui décide, en fonction de l'existence de la base, si
        // c'est bénin ou fatal.
        val secretKey = existingKey() ?: return null

        val kek = try {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, scelle.nonce))
                doFinal(scelle.ciphertext)
            }
        } catch (e: Exception) {
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
        val snapshot = runCatching { prefs.all }
            .getOrElse { cause -> throw KekFailure.SourceUnavailable(name, cause) }

        val blob = snapshot[KEY_BLOB]
        val nonce = snapshot[KEY_NONCE]

        // Rien du tout : c'est la seule absence véritable.
        if (blob == null && nonce == null) return null

        // 🔴 Tout le reste est un scellé PRÉSENT mais inutilisable, et ce n'est pas une absence.
        //
        // Cette méthode écrivait `snapshot[KEY_BLOB] as? String ?: return null`, deux fois — le
        // motif exact que `FlutterSecureStorageKekSource.readStoredValue` avait été corrigé pour
        // éviter, quelques heures plus tôt. Le correctif avait fermé le trou d'un côté et laissé
        // son jumeau ouvert de l'autre.
        //
        // Trois états s'y confondaient avec « je n'ai rien » : un couple incomplet — l'un des deux
        // écrit, l'autre pas —, et une entrée d'un type inattendu. Aucun ne prouve l'absence de
        // clé ; tous menaient à `generateAndPersist` si la base n'existait pas encore.
        //
        // Relevé en CRITIQUE, indépendamment, par les deux relectures externes du 2026-08-13.
        if (blob == null || nonce == null) {
            throw KekFailure.SourceUnavailable(
                name,
                IllegalStateException("scelle incomplet : blob=${blob != null} nonce=${nonce != null}"),
            )
        }
        if (blob !is String || nonce !is String) {
            throw KekFailure.SourceUnavailable(
                name,
                IllegalStateException("scelle present mais d'un type inattendu"),
            )
        }
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
        } catch (e: Exception) {
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
            // ⚠️ `commit()` et son résultat sont vérifiés : cet effacement précède la destruction
            // de la clé Keystore. S'il échoue en silence, on détruit la clé en laissant un scellé
            // qu'elle seule pouvait ouvrir — un état qu'aucune relecture ultérieure ne rattrape.
            // Relevé par une relecture externe (GPT-5.5, 2026-08-13).
            if (!prefs.edit().remove(KEY_BLOB).remove(KEY_NONCE).commit()) {
                throw KekFailure.SourceUnavailable(
                    name,
                    IllegalStateException("effacement du scelle precedent refuse par les preferences"),
                )
            }
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                Timber.w("clé de scellage préexistante supprimée — aucune base à protéger")
                ks.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            throw KekFailure.SourceUnavailable(name, e)
        }
        store(kek)
    }

    /**
     * Détruit le scellé **puis** la clé qui l'ouvrait.
     *
     * ## ⚠️ Cet ordre-ci, et pas l'inverse
     *
     * La clé de l'`AndroidKeyStore` est ce qu'un attaquant ne peut ni extraire ni recalculer : la
     * détruire suffit à rendre le scellé illisible. Mais l'ordre inverse — clé d'abord — laisserait,
     * si le processus meurt entre les deux, un scellé orphelin dans les préférences, c'est-à-dire un
     * fichier qui ressemble à un secret protégé et n'en est plus un. Rien ne pourrait plus le
     * distinguer d'un scellé valide, et une version future pourrait s'y casser les dents.
     *
     * Effacer d'abord la donnée, ensuite le moyen de la lire, laisse à chaque interruption possible
     * un état cohérent.
     *
     * ⚠️ `commit()` et non `apply()` : la panique doit savoir si l'effacement a **réellement**
     * abouti avant d'annoncer quoi que ce soit à l'utilisateur.
     */
    @Synchronized
    override fun destroy() {
        try {
            // ⚠️ Le FICHIER est supprimé, pas seulement ses deux clés : un `notes_tech.kek.xml`
            // vide resterait dans le répertoire de l'application après une panique qui vient
            // d'annoncer qu'il ne restait rien.
            supprimerLeFichierDePreferences(context, PREFS_NAME)
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            throw KekFailure.SourceUnavailable(name, e)
        }
    }

    private fun existingKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(KEY_ALIAS, null) as? SecretKey
    } catch (e: Exception) {
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
