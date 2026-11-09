package com.filestech.notes_tech.ui.common

import android.content.Context
import android.content.res.Configuration
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import java.util.Locale

/**
 * [this], with the language chosen in the app's settings — for an activity's `attachBaseContext`
 * (`MainActivity` since 3.0.0; moved here in 3.4.0 for the blind drop's activity, which shows text
 * too: one answer to "which language", not two).
 *
 * ⚠️ **Hilt is not ready at that point**: `attachBaseContext` runs before the activity is injected. The
 * preference is read directly, with the same class and keys — not with a copy of the logic.
 *
 * `SYSTEM` (no code) forces nothing: the context stays as Android built it, following the device's
 * setting, even when it changes while the app runs.
 */
internal fun Context.avecLaLangueChoisie(): Context {
    val langue = AppSettings(LegacyPreferences(this)).localeNow().code?.let(Locale::forLanguageTag) ?: return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(langue)
    return createConfigurationContext(configuration)
}
