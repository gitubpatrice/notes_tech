package com.filestech.notes_tech.security.panic

import com.filestech.notes_tech.data.prefs.LegacyPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔴 **A panic that started and did not finish is finished at the next launch** — security audit of
 * 2026-09-26, P2 (second half).
 *
 * The sequence runs under `NonCancellable`, but a phone switched off, a battery out or a killed
 * process still cut it. Cut between the key and the database, the next launch found a database
 * without its key and showed the start-up failure screen — which says the notes are intact and not
 * to clear the app's data, the one gesture that would have finished the wipe. Cut earlier, the
 * notes simply came back.
 *
 * The flag is written with `commit`, before the first step, and kept by the preferences step
 * (`PanicService.PREFERENCES_CONSERVEES`); it goes only once the sequence has run to its end. At
 * launch, `StartupViewModel` sees it and opens nothing: the sequence runs again, the user having
 * confirmed it already.
 */
@Singleton
class PanicJournal @Inject constructor(private val prefs: LegacyPreferences) {

    /** @return `false` if the disk refused — the sequence runs anyway, and says nothing it cannot keep. */
    fun markStarted(): Boolean = prefs.commit { putBoolean(KEY, true) }

    fun isPending(): Boolean = prefs.contains(KEY)

    fun clear(): Boolean = prefs.commit { remove(KEY) }

    companion object {
        const val KEY = "panic_in_progress"
    }
}
