package com.filestech.notes_tech

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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
class KeystoreBridge(@Suppress("unused") private val ctx: Context) : MethodCallHandler {

    companion object {
        const val CHANNEL_NAME = "notes_tech/keystore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val GCM_TAG_BITS = 128
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
                "hasKey" -> {
                    val alias = call.argument<String>("alias")
                        ?: return result.error("BAD_ARG", "alias missing", null)
                    result.success(ks.containsAlias(alias))
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            // Pas de contenu sensible : alias non-secret, plaintext jamais
            // logué. Classe + message suffisent pour triage.
            result.error("KEYSTORE_ERROR", "${e.javaClass.simpleName}: ${e.message}", null)
        }
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
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                b.setIsStrongBoxBacked(true)
            }
            return b.build()
        }

        // StrongBox d'abord, fallback TEE software (S9, émulateurs, OEM
        // sans StrongBox). Échec silencieux — pas remonté à l'UI.
        try {
            gen.init(build(strongBox = true))
            gen.generateKey()
        } catch (_: Exception) {
            gen.init(build(strongBox = false))
            gen.generateKey()
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
