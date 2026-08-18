package com.filestech.notes_tech

import android.content.Context
import android.os.Build
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Notes Tech — pont vers AndroidKeyStore pour le mode coffre PIN (v0.9).
 *
 * Une clé AES-256-GCM par coffre PIN, alias = `vault_pin_<folder_id>`.
 * La clé reste résidente dans le TEE (ou StrongBox quand dispo) — seul
 * le ciphertext + nonce sont stockés en DB. Sans le hardware d'origine,
 * impossible d'attaquer le coffre offline : c'est ce qui compense la
 * faible entropie d'un PIN 4-6 chiffres (le bruteforce devient on-device,
 * donc soumis au rate-limit applicatif + auto-wipe v0.9).
 *
 * Méthodes exposées (canal `notes_tech/keystore`) :
 *  - createKey(alias)             → Boolean (true si créée, false si existait)
 *  - wrap(alias, plaintext)       → { ciphertext, nonce } (Keystore IV random)
 *  - unwrap(alias, ciphertext, nonce) → plaintext
 *  - deleteKey(alias)             → null (idempotent)
 *  - hasKey(alias)                → Boolean
 *
 * Auth model : `setUserAuthenticationRequired(false)`. Le PIN applicatif
 * est l'auth factor côté Flutter ; ajouter une auth Keystore (biométrie /
 * device credential) doublerait l'UX.
 *
 * Pattern aligné sur Pass Tech v2 (KeystoreBridge équivalent).
 */
class KeystoreBridge(private val ctx: Context) : MethodCallHandler {

    companion object {
        const val CHANNEL_NAME = "notes_tech/keystore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val GCM_TAG_BITS = 128

        // ── Passerelle 2.0.4 : le contrat que lit la version Kotlin ────────────────────────
        //
        // Ces quatre valeurs sont un CONTRAT avec `KeystoreSealedKekSource` du portage Kotlin.
        // Les changer ici sans les changer la-bas rend la migration silencieusement inoperante :
        // la 2.0.4 aurait l'air d'avoir fonctionne, et la 3.0.0 basculerait sur la couche ② de
        // secours sans que personne ne le sache. Cf. `notes_files_tech/docs/10-PASSERELLE-2.0.4.md`.
        private const val KEK_ALIAS = "notes_tech.db.kek.v1"
        private const val KEK_PREFS_NAME = "notes_tech.kek"
        private const val KEK_BLOB = "db_kek_v1.blob"
        private const val KEK_NONCE = "db_kek_v1.nonce"

        /** La KEK fait 32 octets, soit 64 caracteres hexadecimaux. */
        private const val KEK_BYTES = 32
    }

    private val ks: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun onMethodCall(call: MethodCall, result: Result) {
        try {
            when (call.method) {
                "createKey" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    result.success(createKey(alias))
                }
                "wrap" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    val plaintext = call.argument<ByteArray>("plaintext")
                        ?: return result.error("BAD_ARG", "plaintext missing", null)
                    result.success(wrap(alias, plaintext))
                }
                "unwrap" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    val ciphertext = call.argument<ByteArray>("ciphertext")
                        ?: return result.error("BAD_ARG", "ciphertext missing", null)
                    val nonce = call.argument<ByteArray>("nonce")
                        ?: return result.error("BAD_ARG", "nonce missing", null)
                    result.success(unwrap(alias, ciphertext, nonce))
                }
                "deleteKey" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    if (ks.containsAlias(alias)) ks.deleteEntry(alias)
                    result.success(null)
                }
                "deleteKeysWithPrefix" -> {
                    val prefix = call.argument<String>("prefix")
                        ?: return result.error("BAD_ARG", "prefix missing", null)
                    // Itère TOUS les alias du Keystore et supprime ceux qui
                    // matchent le préfixe. Utilisé par le mode panique pour
                    // wiper d'un coup toutes les clés `vault_pin_*` sans
                    // dépendre d'une DB encore lisible.
                    val toDelete = mutableListOf<String>()
                    val aliases = ks.aliases()
                    while (aliases.hasMoreElements()) {
                        val a = aliases.nextElement()
                        if (a.startsWith(prefix)) toDelete.add(a)
                    }
                    for (a in toDelete) {
                        try { ks.deleteEntry(a) } catch (_: Exception) {/* best-effort */}
                    }
                    result.success(toDelete.size)
                }
                "sealDatabaseKek" -> {
                    val kek = call.argument<ByteArray>("kek")
                        ?: return result.error("BAD_ARG", "kek missing", null)
                    result.success(sealDatabaseKek(kek))
                }
                "hasKey" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    result.success(ks.containsAlias(alias))
                }
                else -> result.notImplemented()
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Spécifique : la clé Keystore a été détruite par l'OS (changement
            // d'écran de verrouillage, factory reset partiel, biométrie
            // retirée). Le wipe du coffre est LÉGITIME — pas de rollback.
            result.error(
                "KEY_PERMANENTLY_INVALIDATED",
                "Keystore key invalidated: ${e.message}",
                null,
            )
        } catch (e: Exception) {
            // Pas de contenu sensible : alias non-secret, plaintext jamais
            // logué. Classe + message suffisent pour triage.
            //
            // Le `code` est `e.javaClass.simpleName` pour permettre à la
            // couche Dart de différencier les exceptions transitoires
            // (StrongBox→TEE migration après OTA Samsung, lockscreen
            // verrouillé bloquant l'accès, OOM JNI) des exceptions
            // légitimes — afin de ne PAS auto-wipe sur exception transitoire.
            result.error(
                e.javaClass.simpleName,
                "${e.javaClass.simpleName}: ${e.message}",
                null,
            )
        }
    }

    /**
     * Re-scelle la KEK de la base sous l'alias que lira la version Kotlin.
     *
     * C'est TOUTE la passerelle : la 2.0.4 migre sa propre cle pendant qu'elle est encore
     * installee et qu'elle sait la lire, au lieu de laisser la 3.0.0 rejouer a l'envers la
     * cryptographie interne de `flutter_secure_storage`.
     *
     * 🔴 **Les preferences sont ecrites ICI, en natif.** Le greffon `shared_preferences` prefixe
     * toutes ses cles par `flutter.` et utilise son propre fichier : une ecriture Dart de
     * `db_kek_v1.blob` atterrirait en `flutter.db_kek_v1.blob` dans `FlutterSharedPreferences`,
     * pas dans `notes_tech.kek`. La version Kotlin ne trouverait rien, et la 2.0.4 aurait l'air
     * d'avoir fonctionne. C'est le piege qu'on rate en ecrivant cette release.
     *
     * ⚠️⚠️ **Des OCTETS BRUTS, et non la chaine hexadecimale que decrivait la procedure.**
     * `docs/10-PASSERELLE-2.0.4.md` prevoyait un parametre `kekHex`, parce qu'il partait de ce que
     * `flutter_secure_storage` contient (64 caracteres hexadecimaux). Mais au point d'appel reel la
     * KEK est deja materialisee en `Uint8List` — et la repasser par une `String` creerait une copie
     * du secret **que Dart ne sait pas effacer** : une `String` est immuable, elle survit jusqu'au
     * ramasse-miettes. Le code Flutter prend d'ailleurs soin d'effacer son `Uint8List` des la base
     * ouverte (`database.dart`, `VaultService.wipe`). Reintroduire une copie ineffacable pour la
     * commodite d'un parametre serait defaire ce soin.
     *
     * Ce que la version Kotlin lit ne change pas : le clair scelle reste les 32 octets bruts.
     *
     * ⚠️ **`Base64.NO_WRAP`, jamais `DEFAULT`** : `DEFAULT` insere des retours a la ligne que le
     * decodage strict cote Kotlin refuse.
     *
     * ⚠️⚠️ **La longueur est verifiee, et ce n'est pas du zele.** Sceller une valeur de mauvaise
     * taille produirait un scelle **valide contenant une mauvaise cle** : la version Kotlin le
     * lirait sans rien soupconner et n'aurait aucune raison de basculer sur la couche ② de secours.
     * *Une absence de scelle se rattrape ; un scelle faux, non.*
     *
     * ⚠️ **Idempotence : les preferences ET l'alias.** Un scelle present sans sa cle Keystore est
     * indechiffrable — il faut le refaire, pas le garder. Ne regarder que les preferences
     * condamnerait cet appareil a la couche ② pour toujours.
     *
     * @param kek les 32 octets bruts de la KEK de la base.
     * @return `true` si un scelle a ete ecrit, `false` s'il y en avait deja un d'utilisable.
     */
    private fun sealDatabaseKek(kek: ByteArray): Boolean {
        val prefs = ctx.getSharedPreferences(KEK_PREFS_NAME, Context.MODE_PRIVATE)
        val dejaScelle = prefs.contains(KEK_BLOB) && prefs.contains(KEK_NONCE)
        if (dejaScelle && ks.containsAlias(KEK_ALIAS)) return false

        require(kek.size == KEK_BYTES) { "KEK attendue en $KEK_BYTES octets, recu ${kek.size}" }

        try {
            createKey(KEK_ALIAS)
            val sealed = wrap(KEK_ALIAS, kek)
            prefs.edit()
                .putString(KEK_BLOB, Base64.encodeToString(sealed["ciphertext"], Base64.NO_WRAP))
                .putString(KEK_NONCE, Base64.encodeToString(sealed["nonce"], Base64.NO_WRAP))
                .apply()
        } finally {
            // ⚠️ Le tampon decode par le codec Flutter nous appartient : l'effacer ici evite qu'une
            // copie de la KEK traine dans le tas de la JVM apres l'appel. Sur le chemin d'echec
            // aussi — c'est meme la qu'on l'oublierait.
            kek.fill(0)
        }
        return true
    }

    private fun createKey(alias: String): Boolean {
        if (ks.containsAlias(alias)) return false
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)

        fun build(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
            // v1.0.7 sécu M-03 — défense en profondeur : exige que l'écran
            // soit déverrouillé pour utiliser la clé (API 28+). Empêche
            // qu'un attaquant avec un debug bridge USB sur un device verrouillé
            // ne puisse exercer la clé pour brute-forcer le PIN du coffre
            // hors-bande. Idempotent : ne change pas les clés existantes,
            // seules les nouvelles créations bénéficient du flag.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                b.setUnlockedDeviceRequired(true)
            }
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                b.setIsStrongBoxBacked(true)
            }
            return b.build()
        }

        // StrongBox d'abord, fallback TEE software (S9, émulateurs, OEM
        // sans StrongBox). Échec silencieux — pas remonté à l'UI.
        val key: SecretKey = try {
            gen.init(build(strongBox = true))
            gen.generateKey()
        } catch (_: Exception) {
            gen.init(build(strongBox = false))
            gen.generateKey()
        }

        // Sécurité : valider que la clé créée est bien hardware-backed
        // (TEE ou StrongBox). Sur device sans TEE/StrongBox (rooté avec
        // Magisk patch, émulateur, etc.), `KeyGenerator` retombe
        // silencieusement sur software Keystore — le PIN devient alors
        // bruteforçable hors-device. On supprime la clé et on remonte
        // une erreur typée pour que la couche Dart propose un coffre
        // passphrase à la place.
        try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE_PROVIDER)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val secure = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // securityLevel >= TEE (1) considéré sécurisé. STRONGBOX = 2.
                @Suppress("DEPRECATION")
                keyInfo.isInsideSecureHardware
            } else {
                @Suppress("DEPRECATION")
                keyInfo.isInsideSecureHardware
            }
            if (!secure) {
                ks.deleteEntry(alias)
                throw IllegalStateException("KEYSTORE_SOFTWARE_ONLY")
            }
        } catch (e: IllegalStateException) {
            // Re-throw — sera mappé en code "IllegalStateException" + message
            // "KEYSTORE_SOFTWARE_ONLY" côté Dart.
            throw e
        } catch (_: Exception) {
            // Si l'introspection échoue (rare), on garde la clé. L'utilisateur
            // n'est pas pénalisé pour un bug d'API ; le risque résiduel est
            // accepté (probabilité quasi-nulle sur Android moderne).
        }

        return true
    }

    private fun wrap(alias: String, plaintext: ByteArray): Map<String, ByteArray> {
        val key = ks.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Keystore key not found for alias")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Init sans IV explicite → Keystore génère un IV random frais.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv ?: throw IllegalStateException("missing IV")
        return mapOf("ciphertext" to ct, "nonce" to iv)
    }

    private fun unwrap(alias: String, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val key = ks.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Keystore key not found for alias")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }
}

/** Helper : enregistre le canal depuis [MainActivity.configureFlutterEngine]. */
fun registerKeystoreBridge(
    context: Context,
    messenger: io.flutter.plugin.common.BinaryMessenger,
) {
    MethodChannel(messenger, KeystoreBridge.CHANNEL_NAME)
        .setMethodCallHandler(KeystoreBridge(context))
}
