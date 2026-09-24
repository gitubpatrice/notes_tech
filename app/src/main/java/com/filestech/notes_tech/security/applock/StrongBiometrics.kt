package com.filestech.notes_tech.security.applock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG

/** Whether this device can open the app with a biometric, and if not, what the user can do about it. */
enum class BiometricAvailability {
    AVAILABLE,

    /** A strong sensor exists, nothing is enrolled: the user can fix it in the device settings. */
    NOT_ENROLLED,

    /** No sensor strong enough, or none usable now. Nothing the app can offer. */
    UNAVAILABLE,
}

/**
 * The single place that decides which biometrics may open the app (Agenda Tech, audit F3).
 *
 * Class 3 ([BIOMETRIC_STRONG]) only. Class 2 is exactly the tier the platform refuses for Keystore
 * keys, because a photo defeats it on many face-unlock builds — SMS Tech accepted it until 1.25.3.
 * `DEVICE_CREDENTIAL` stays out too: a relative who knows the phone's code is precisely who an app
 * lock on notes is for.
 *
 * ⚠️ Consequence the settings screen must not hide: face unlock is offered only where the device
 * rates it Class 3 (recent Pixels), not on most Samsung phones — whose face unlock is Class 2.
 *
 * The prompt and the settings ask the SAME question through this object: when they drifted apart in
 * Agenda Tech, the toggle offered a tier the prompt then refused, and the unlock silently did nothing.
 */
object StrongBiometrics {

    /** The mask handed to both `canAuthenticate` and `PromptInfo.setAllowedAuthenticators`. */
    const val ALLOWED_AUTHENTICATORS: Int = BIOMETRIC_STRONG

    fun availability(context: Context): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            else -> BiometricAvailability.UNAVAILABLE
        }

    fun isAvailable(context: Context): Boolean = availability(context) == BiometricAvailability.AVAILABLE
}
