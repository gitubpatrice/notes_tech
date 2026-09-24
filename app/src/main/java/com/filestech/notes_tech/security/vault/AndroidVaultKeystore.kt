package com.filestech.notes_tech.security.vault

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VaultKeystore] adossé à `AndroidKeyStore`.
 *
 * Portage de `KeystoreBridge.kt` du projet Flutter, **débarrassé de sa couche `MethodChannel`** :
 * les cinq méthodes qui y étaient exposées par nom de chaîne deviennent des appels typés, et la
 * traduction des erreurs en codes de chaîne — puis leur retraduction côté Dart — disparaît avec
 * elle. C'est le seul endroit du portage où le code Kotlin d'origine était déjà écrit ; ce qui
 * change ici est ce qui l'entourait, pas ce qu'il fait.
 *
 * ## 🔴 Ce qui rend cette classe irremplaçable, et fragile
 *
 * La clé ne quitte jamais le matériel sécurisé. Cela veut dire deux choses ensemble : un attaquant
 * ne peut pas attaquer un code à quatre chiffres hors de l'appareil, **et** personne ne peut
 * sauvegarder ni restaurer cette clé. Un coffre à code ne survit ni à une réinstallation, ni à un
 * changement d'appareil, ni à une réinitialisation de l'écran de verrouillage. C'est le contrat, il
 * est assumé, et c'est pourquoi le mode phrase secrète existe à côté.
 *
 * ⚠️ **Aucune clé de ce magasin n'est visible depuis un autre UID.** La build de portage porte un
 * `applicationId` suffixé `.next` : elle ne peut donc pas ouvrir les coffres à code de
 * l'application publiée, quoi qu'on fasse. Ce n'est pas une limite des tests, c'est une propriété
 * du système — cf. `docs/06-ISOLATION-PENDANT-LE-CHANTIER.md`.
 */
@Singleton
class AndroidVaultKeystore @Inject constructor(@ApplicationContext private val context: Context) : VaultKeystore {

    /**
     * Chargé paresseusement, et **à chaque échec de nouveau**.
     *
     * `KeyStore.load(null)` peut échouer transitoirement — magasin verrouillé, service de clés en
     * cours de redémarrage après une mise à jour du système. Mémoriser l'instance une fois pour
     * toutes ferait alors traîner l'échec pour toute la durée du processus.
     */
    private fun keyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (e: Exception) {
        throw KeystoreUnavailableException(e)
    }

    override fun createKey(alias: String): Boolean {
        val store = keyStore()
        if (containsAlias(store, alias)) return false

        val key = generate(alias)
        assertHardwareBacked(store, alias, key)
        return true
    }

    override fun seal(alias: String, plaintext: ByteArray): SealedByKeystore {
        val key = requireKey(alias)
        return try {
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            // Aucun paramètre : la clé exige un chiffrement randomisé, donc le Keystore tire
            // lui-même un nonce neuf. Vouloir en fournir un lèverait — et c'est très bien, puisque
            // réutiliser un couple (clé, nonce) casserait GCM en entier.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(plaintext)
            val nonce = checkNotNull(cipher.iv) { "le keystore n'a pas produit de nonce" }
            SealedByKeystore(ciphertext = ciphertext, nonce = nonce)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeystorePermanentlyInvalidatedException(e)
        } catch (e: Exception) {
            throw KeystoreUnavailableException(e)
        }
    }

    override fun open(alias: String, sealed: SealedByKeystore): ByteArray {
        val key = requireKey(alias)
        return try {
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(VaultParams.TAG_BITS, sealed.nonce))
            cipher.doFinal(sealed.ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Le système a détruit la clé. Le coffre est irrécupérable, et l'effacer est la bonne
            // réponse : garder des notes que plus rien n'ouvrira n'aide personne.
            throw KeystorePermanentlyInvalidatedException(e)
        } catch (e: AEADBadTagException) {
            // 🔴 La clé est là, elle a fonctionné, et le contenu ne valide pas. Le code de
            // l'utilisateur n'intervient PAS à cette couche — il n'est consommé qu'après, par
            // Argon2id. Compter ceci comme une tentative ratée reviendrait donc à détruire un
            // coffre au bout de cinq lectures d'une colonne abîmée.
            // La cause est conservée : « Tag mismatch » ne dit rien de secret, et c'est le seul
            // indice qu'aura un diagnostic de base abîmée.
            throw MalformedVaultDataException("scelle keystore illisible pour l'alias demande", e)
        } catch (e: Exception) {
            // ⚠️ **Liste blanche, jamais liste noire.** La version Flutter a d'abord traité toute
            // exception du Keystore comme un échec légitime, donc comme une raison d'effacer un
            // coffre. Une mise à jour du système exposant un sous-type inattendu suffisait alors à
            // détruire les notes d'un utilisateur qui n'avait rien fait. Corrigé en v1.0.3 (F5) :
            // seule l'invalidation permanente compte.
            throw KeystoreUnavailableException(e)
        }
    }

    override fun deleteKey(alias: String) {
        val store = keyStore()
        try {
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        } catch (e: Exception) {
            throw KeystoreUnavailableException(e)
        }
    }

    override fun deleteKeysWithPrefix(prefix: String): Int {
        val store = keyStore()
        val vises = try {
            // ⚠️ La liste est matérialisée AVANT la première suppression. Supprimer pendant qu'on
            // énumère laisse le comportement à la discrétion de l'implémentation du magasin — au
            // mieux une exception, au pire des alias sautés en silence. Sur ce chemin-là, « sauté
            // en silence » veut dire une clé de coffre qui survit à une panique.
            store.aliases().toList().filter { it.startsWith(prefix) }
        } catch (e: Exception) {
            throw KeystoreUnavailableException(e)
        }

        var effacees = 0
        var premierEchec: Exception? = null
        for (alias in vises) {
            try {
                store.deleteEntry(alias)
                effacees++
            } catch (e: Exception) {
                // On continue : une clé récalcitrante ne doit pas empêcher d'effacer les suivantes.
                // Mais on ne se tait pas — c'est à la fin qu'on signale, et l'étape doit échouer.
                if (premierEchec == null) premierEchec = e
            }
        }
        // ⚠️⚠️ **On relit, et on relit AVANT de lever.** `deleteEntry` qui rend la main sans lever ne
        // prouve pas que l'alias a disparu — c'est une promesse de l'implémentation du magasin, pas
        // une observation.
        //
        // Les deux autres destructions critiques de la séquence de panique relisent déjà :
        // `KekRepository.destroy()` rappelle chaque source, `supprimerLeFichierDePreferences`
        // contrôle l'existence du fichier. Celle-ci ne relisait rien, alors qu'elle porte l'étape
        // `PIN_KEYS_WIPE` — c'est-à-dire la seule barrière d'un coffre à code contre une attaque
        // menée hors de l'appareil. Le commentaire de `KekRepository` reproche mot pour mot ce
        // défaut à l'application publiée : « `hasKey()` est écrit dix lignes plus bas et répondrait
        // à la question ». Il l'était ici aussi, et personne ne l'appelait.
        //
        // ⚠️ **Le premier correctif plaçait `throw premierEchec` juste au-dessus de cette
        // relecture** — donc sur le seul chemin où une suppression a protesté, c'est-à-dire
        // exactement celui où l'on veut savoir ce qui a survécu, la relecture ne s'exécutait
        // jamais. Un correctif inatteignable là où il sert. Relevé par la relecture externe du
        // 2026-08-15, sur le correctif du matin même.
        //
        // ⚠️ On **ré-énumère**, on ne filtre pas `vises` : relire l'instantané pris avant la boucle
        // ne contrôlerait que les alias qui existaient déjà, et un alias créé depuis — le préfixe
        // visé ici est celui des coffres à code — ne serait ni supprimé ni vu.
        val survivants = try {
            store.aliases().toList().filter { it.startsWith(prefix) }
        } catch (e: Exception) {
            throw KeystoreUnavailableException(e)
        }

        // ⚠️ **Le moindre doute fait échouer l'étape**, et les deux doutes ne sont pas de même
        // nature. Des survivants, c'est une observation : des clés sont là. Un échec de suppression
        // sans survivant, c'est un magasin qui a protesté puis annoncé n'avoir plus rien — et on ne
        // fait pas confiance à un oracle qui vient de se tromper. Les deux échouent.
        //
        // Échouer à tort ne coûte plus ce que ça coûtait : depuis que l'écran de fin distingue
        // `minimalGuarantee` de `isComplete`, une étape ratée hors destruction de clé s'affiche
        // « protégé, nettoyage incomplet » et non plus en alarme.
        if (survivants.isNotEmpty() || premierEchec != null) {
            // ⚠️ Le nombre, jamais les alias : ils portent l'identifiant du dossier de coffre.
            throw KeystoreUnavailableException(
                premierEchec ?: IllegalStateException("clés de coffre survivantes : ${survivants.size}"),
            )
        }
        return effacees
    }

    override fun hasKey(alias: String): Boolean = containsAlias(keyStore(), alias)

    private fun containsAlias(store: KeyStore, alias: String): Boolean = try {
        store.containsAlias(alias)
    } catch (e: Exception) {
        throw KeystoreUnavailableException(e)
    }

    private fun requireKey(alias: String): SecretKey {
        val store = keyStore()
        val key = try {
            store.getKey(alias, null)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeystorePermanentlyInvalidatedException(e)
        } catch (e: Exception) {
            throw KeystoreUnavailableException(e)
        }
        // ⚠️ Une clé ABSENTE n'est pas une donnée abîmée : c'est un état d'où l'on ne peut rien
        // conclure sur le coffre. Elle peut manquer parce que le système l'a purgée, parce que
        // l'application a été réinstallée, ou parce qu'un effacement a été interrompu à mi-course.
        // Aucun de ces cas ne justifie de compter une tentative.
        return key as? SecretKey ?: throw KeystoreUnavailableException(
            IllegalStateException("aucune cle secrete sous cet alias"),
        )
    }

    private fun generate(alias: String): SecretKey {
        val generator = try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        } catch (e: Exception) {
            throw KeystoreUnavailableException(e)
        }
        // StrongBox d'abord, repli sur le TEE. Le repli est silencieux et normal : le S9 et la
        // plupart des appareils d'avant 2019 n'ont pas de StrongBox, et leur TEE suffit.
        return try {
            generator.init(specFor(alias, strongBox = true))
            generator.generateKey()
        } catch (_: Exception) {
            try {
                generator.init(specFor(alias, strongBox = false))
                generator.generateKey()
            } catch (e: Exception) {
                throw classerLEchecDeGeneration(e, Build.VERSION.SDK_INT, appareilSecurise())
            }
        }
    }

    /** `false` when the phone has no PIN, pattern or password; `null` when the system would not say. */
    private fun appareilSecurise(): Boolean? =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure }.getOrNull()

    private fun specFor(alias: String, strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            // Pas d'authentification système : le code du coffre EST le facteur d'authentification.
            // En ajouter une seconde — biométrie, code de l'écran de verrouillage — doublerait la
            // saisie sans rien ajouter, et la biométrie irait à l'encontre du modèle de menace :
            // on peut contraindre un doigt, pas un mot mémorisé.
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Exige un appareil déverrouillé pour se servir de la clé. Empêche qu'un pont de
            // débogage branché sur un téléphone verrouillé puisse exercer la clé pour essayer des
            // codes hors bande.
            builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    /**
     * Vérifie que la clé produite est bien retenue par du matériel sécurisé, et la supprime sinon.
     *
     * Sur un appareil sans TEE ni StrongBox — émulateur, système modifié —, le générateur retombe
     * **silencieusement** sur une implantation logicielle. Le coffre à code paraîtrait alors
     * protégé tout en étant attaquable hors de l'appareil, où quatre chiffres ne tiennent pas une
     * seconde. Mieux vaut refuser et proposer une phrase secrète.
     *
     * ⚠️ Si l'introspection elle-même échoue, la clé est **conservée**. C'est le choix de
     * l'application publiée, et il est le bon : pénaliser l'utilisateur pour une API qui ne répond
     * pas ferait perdre une fonctionnalité sur la foi d'un incident, alors que le cas est
     * quasi inexistant sur les Android modernes.
     */
    private fun assertHardwareBacked(store: KeyStore, alias: String, key: SecretKey) {
        val secure = try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // `isInsideSecureHardware` est déprécié depuis l'API 31 au profit de
                // `securityLevel`. L'original appelait le membre déprécié dans les deux branches
                // d'un `if` sur la version — un reste de refonte, sans effet mais trompeur.
                info.securityLevel in SECURE_LEVELS
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
        } catch (_: Exception) {
            return
        }
        if (!secure) {
            runCatching { store.deleteEntry(alias) }
            throw KeystoreSoftwareOnlyException()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256

        /**
         * Les niveaux qui valent « retenu par du matériel ».
         *
         * `SECURITY_LEVEL_UNKNOWN_SECURE` en fait partie : il signifie « sécurisé, mais le système
         * ne dit pas par quoi ». L'exclure refuserait le mode PIN sur des appareils parfaitement
         * capables.
         */
        val SECURE_LEVELS = setOf(
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            KeyProperties.SECURITY_LEVEL_STRONGBOX,
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
        )
    }
}

/**
 * What a failed PIN vault key generation means — the port's reading of notes_tech 2.0.9's
 * `DEVICE_NOT_SECURE` (`KeystoreBridge.kt:256-270`, `keystore_bridge.dart:166-169`).
 *
 * On API 28+ the key requires an unlocked device, so a phone WITHOUT a screen lock cannot get one,
 * and no retry will change that: the user has to set a screen lock or choose a passphrase vault.
 * Asked of the system ([appareilSecurise]), never guessed from the exception, whose type and text
 * vary between vendors.
 *
 * ⚠️ Everything else stays "unavailable, retry": an unknown security state (`null`) included —
 * telling someone to set a screen lock they already have would be the worse error. Below API 28 the
 * requirement does not exist, so a missing lock cannot be the cause.
 *
 * Pure, so the four cases of `keystore_error_mapping_test.dart` are replayed on the JVM.
 */
internal fun classerLEchecDeGeneration(cause: Exception, sdkInt: Int, appareilSecurise: Boolean?): VaultException =
    if (sdkInt >= Build.VERSION_CODES.P && appareilSecurise == false) {
        KeystoreDeviceNotSecureException(cause)
    } else {
        KeystoreUnavailableException(cause)
    }
