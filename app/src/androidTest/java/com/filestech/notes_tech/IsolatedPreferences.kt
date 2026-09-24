package com.filestech.notes_tech

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.ui.secure.SecureWindowController

/**
 * A context whose preferences ALONE live in another file — everything else is the real context.
 *
 * 04-PIEGES §72: a test that writes into the app's real private area can destroy what someone set on
 * the test phone, the more surely that it "cleans up behind itself". The dictation tests divert
 * `getFilesDir`; the app lock tests divert `getSharedPreferences` the same way.
 *
 * ⚠️ Not `mockk` either: on Android 10 its inline agent instruments `toString`, and reflecting over an
 * activity then loads an API 31 class — the whole instrumentation process crashed on the S9
 * (2026-09-24, `NoClassDefFoundError: android.app.PictureInPictureUiState`).
 */
internal class IsolatedPreferencesContext(base: Context, private val suffix: String = "_isolated_for_tests") :
    ContextWrapper(base) {

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("$name$suffix", mode)

    /** Removes the diverted file. */
    fun delete(name: String) {
        getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        baseContext.deleteSharedPreferences("$name$suffix")
    }
}

/**
 * A real [SecureWindowController] whose user setting says "screenshots allowed", on diverted
 * preferences: what it reports is then the screens' own requests, and nothing else.
 */
internal fun controllerWithScreenshotsAllowed(context: IsolatedPreferencesContext): SecureWindowController {
    val settings = AppSettings(LegacyPreferences(context))
    settings.setSecureWindow(false)
    return SecureWindowController(settings)
}
