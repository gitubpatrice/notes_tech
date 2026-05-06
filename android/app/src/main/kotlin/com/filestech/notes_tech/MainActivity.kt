package com.filestech.notes_tech

import android.os.Bundle
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
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "notes_tech/secure_window"
        // Doit correspondre à `AppConstants.prefKeySecureWindowEnabled`
        // côté Dart. shared_preferences mappe sur `FlutterSharedPreferences`.
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val PREF_KEY = "flutter.secure_window_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lecture précoce — on respecte la préférence dès le 1er frame.
        // Activé par défaut tant que rien n'est encore persisté.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_KEY, true)
        applySecureFlag(enabled)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        runOnUiThread { applySecureFlag(enabled) }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        // v0.9 — pont AndroidKeyStore pour les coffres en mode PIN.
        registerKeystoreBridge(applicationContext, flutterEngine.dartExecutor.binaryMessenger)
    }

    private fun applySecureFlag(enabled: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (enabled) {
            window.setFlags(flag, flag)
        } else {
            window.clearFlags(flag)
        }
    }
}
