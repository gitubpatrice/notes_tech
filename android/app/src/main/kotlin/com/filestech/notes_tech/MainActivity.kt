package com.filestech.notes_tech

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Notes Tech — activité Flutter unique.
 *
 * Sécurité :
 *  - FLAG_SECURE est appliqué au plus tôt (`onCreate`) à partir d'une
 *    valeur persistée dans SharedPreferences (clé partagée avec le
 *    `SettingsService` Dart). Évite la fenêtre dans laquelle Android
 *    pourrait capturer un screenshot avant que Flutter ait fini de
 *    démarrer.
 *  - Le canal `notes_tech/secure_window` permet à Flutter de basculer
 *    le flag dynamiquement quand l'utilisateur change le réglage.
 *
 * v1.0.7 UI I1 — refcount de "force enabled" (pattern Pass Tech v2.3.8) :
 *    Certains écrans très sensibles (vault unlock sheets, ai_chat avec
 *    contexte RAG, éditeur de note déchiffrée) doivent forcer FLAG_SECURE
 *    même si l'utilisateur l'a désactivé globalement dans les préférences.
 *    Le refcount permet d'imbriquer plusieurs forces (ex: vault sheet
 *    pendant ai_chat) et de ne restaurer la pref qu'au dernier `restore()`.
 *    Le flag effectif est : `forcedCount > 0 OR userPref`.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "notes_tech/secure_window"
        // F4 v1.1.0 — channel dédié au clipboard "sensible" Android 13+.
        // Pose `ClipDescription.EXTRA_IS_SENSITIVE` qui informe les
        // clipboard managers tiers + Knox + Material You "Smart Reply"
        // que le payload ne doit pas être pré-affiché en preview ni
        // mémorisé dans l'historique.
        private const val CLIPBOARD_CHANNEL = "com.filestech.notes_tech/clipboard"
        // Doit correspondre à `AppConstants.prefKeySecureWindowEnabled`
        // côté Dart. shared_preferences mappe sur `FlutterSharedPreferences`.
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val PREF_KEY = "flutter.secure_window_enabled"
    }

    private var userPrefEnabled: Boolean = true
    private var forcedCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lecture précoce — on respecte la préférence dès le 1er frame.
        // Activé par défaut tant que rien n'est encore persisté.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        userPrefEnabled = prefs.getBoolean(PREF_KEY, true)
        applyEffective()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setEnabled" -> {
                        // Met à jour la pref utilisateur (depuis Settings).
                        // Le flag effectif tient compte du refcount forcé.
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        userPrefEnabled = enabled
                        runOnUiThread { applyEffective() }
                        result.success(null)
                    }
                    "forceEnabled" -> {
                        // Refcount + force du flag pour la durée d'un écran
                        // sensible. Idempotent côté caller via refcount Dart.
                        forcedCount += 1
                        runOnUiThread { applyEffective() }
                        result.success(null)
                    }
                    "restore" -> {
                        // Décrémente le refcount ; ne descend jamais < 0
                        // pour tolérer un dispose double / pop forcé.
                        if (forcedCount > 0) forcedCount -= 1
                        runOnUiThread { applyEffective() }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        // F4 v1.1.0 — channel clipboard sensitive.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CLIPBOARD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "copySensitive" -> {
                        val text = call.argument<String>("text") ?: ""
                        try {
                            val cm = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("note", text)
                            // Android 13 (API 33) introduit le flag IS_SENSITIVE
                            // qui empêche les previews dans le clipboard
                            // editor + bloque la mémorisation dans l'historique.
                            if (Build.VERSION.SDK_INT >= 33) {
                                val extras = PersistableBundle()
                                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                clip.description.extras = extras
                            }
                            cm.setPrimaryClip(clip)
                            result.success(true)
                        } catch (e: Throwable) {
                            result.success(false)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
        // v0.9 — pont AndroidKeyStore pour les coffres en mode PIN.
        registerKeystoreBridge(applicationContext, flutterEngine.dartExecutor.binaryMessenger)
    }

    private fun applyEffective() {
        // Flag effectif = pref utilisateur OU au moins un écran sensible
        // a demandé le force. Tant que `forcedCount > 0`, le flag reste ON
        // quelle que soit la préférence.
        val effective = userPrefEnabled || forcedCount > 0
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (effective) {
            window.setFlags(flag, flag)
        } else {
            window.clearFlags(flag)
        }
    }
}
