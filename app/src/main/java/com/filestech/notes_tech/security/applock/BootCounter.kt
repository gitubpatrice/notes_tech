package com.filestech.notes_tech.security.applock

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which boot of the device this is.
 *
 * The throttle's deadline lives on `elapsedRealtime`, which restarts from zero at every boot: a
 * deadline from the previous boot means nothing in this one. This is how [AppLockManager] tells them
 * apart.
 */
fun interface BootCounter {

    /** `null` when the device does not say — which [AppLockManager] treats as "another boot". */
    fun currentBoot(): Int?
}

/** `Settings.Global.BOOT_COUNT`, API 24 — the project's floor. Readable without any permission. */
@Singleton
class AndroidBootCounter @Inject constructor(@ApplicationContext private val context: Context) : BootCounter {

    override fun currentBoot(): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        Timber.w(e, "BOOT_COUNT not provided by this device")
        null
    } catch (e: SecurityException) {
        Timber.w(e, "BOOT_COUNT not readable")
        null
    }
}
