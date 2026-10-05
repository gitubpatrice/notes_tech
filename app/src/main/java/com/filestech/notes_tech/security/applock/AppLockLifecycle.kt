package com.filestech.notes_tech.security.applock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.filestech.notes_tech.security.vault.MonotonicClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `MainActivity`'s lifecycle, turned into lock decisions. The decisions are [RelockPolicy]'s; this
 * class only feeds it and carries them out. **Main thread only**, like the callbacks that call it.
 *
 * ## ⚠️ Synchronous, all of it
 *
 * The Recents snapshot is taken around `onStop`, not after whatever `onStop` started. A lock decided
 * in a coroutine lands too late (Agenda Tech, audit F13). Every read here — is a lock configured,
 * which delay — is an in-memory preference lookup, and every write is a state change.
 *
 * ## Vaults
 *
 * This class does not lock them: `NotesTechApplication` does, whenever the process leaves the
 * foreground, which every path to an app lock goes through. The two stay independent on purpose — the
 * vaults' protection must not depend on whether an app lock is configured.
 */
@Singleton
class AppLockLifecycle @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLock: AppLockManager,
    clock: MonotonicClock,
) {

    private val policy = RelockPolicy(clock::elapsedMillis)

    /**
     * Registered only while a picker's pass is pending. On the APPLICATION context: an activity
     * destroyed while the picker is open would otherwise take the receiver with it.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            unregisterScreenOff()
            if (policy.onScreenOff(appLock.relockDelayNow())) appLock.lockIfConfigured()
        }
    }
    private var screenOffRegistered = false

    /** Every activity-for-result the screens open reports here first — see `rememberAppResultLauncher`. */
    fun onExternalActivityLaunched() = policy.onExternalActivityLaunched()

    fun onStart() {
        unregisterScreenOff()
        if (policy.onStart()) appLock.lockIfConfigured()
    }

    fun onResume() = policy.onResume()

    /** `Activity.onUserInteraction` — see [RelockPolicy.onUserInteraction]. */
    fun onUserInteraction() = policy.onUserInteraction()

    /**
     * @param changingConfigurations the activity is being recreated at once — the language change
     *   does it. Locking then would ask for the PIN in the middle of the settings.
     */
    fun onStop(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        val configured = appLock.isConfigured()
        var spared = policy.sparesStopForPicker()
        if (spared && configured) {
            // Registered BEFORE the screen is checked (Gemini Pro's review of Agenda Tech,
            // 2026-09-15): the screen can go off while the picker opens, before this onStop, and that
            // broadcast would be missed by a receiver registered afterwards. Both run on the main
            // thread, so nothing slips between them.
            registerScreenOff()
            if (!isInteractive()) {
                unregisterScreenOff()
                policy.cancelPickerPass()
                spared = false
            }
        }
        if (policy.decideStop(configured, spared, appLock.relockDelayNow()) == StopDecision.LOCK_NOW) {
            appLock.lockIfConfigured()
        }
    }

    fun onNewIntent() {
        if (policy.onNewIntent()) {
            unregisterScreenOff()
            appLock.lockIfConfigured()
        }
    }

    private fun isInteractive(): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false

    /** Idempotent. `ACTION_SCREEN_OFF` is a protected system broadcast: `RECEIVER_NOT_EXPORTED` still gets it. */
    private fun registerScreenOff() {
        if (screenOffRegistered) return
        ContextCompat.registerReceiver(
            context,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOffRegistered = true
    }

    private fun unregisterScreenOff() {
        if (!screenOffRegistered) return
        context.unregisterReceiver(screenOffReceiver)
        screenOffRegistered = false
    }
}
