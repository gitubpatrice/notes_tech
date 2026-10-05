package com.filestech.notes_tech.ui.applock

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.withResumed
import com.filestech.notes_tech.security.applock.StrongBiometrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

/** How a biometric prompt ended. */
sealed interface BiometricOutcome {

    /** The OS authorised [cipher]. Still to be RUN before anything opens — see `BiometricUnlockKey`. */
    class Succeeded(val cipher: Cipher?) : BiometricOutcome

    /** The user chose the PIN, or dismissed the prompt. Nothing to say. */
    data object Cancelled : BiometricOutcome

    /** Anything else — lockout, hardware error. The prompt has shown the system's own words. */
    data object Failed : BiometricOutcome

    /** Another prompt is already on screen: this call did nothing. */
    data object Busy : BiometricOutcome
}

/**
 * The one way this app shows a `BiometricPrompt`, for the lock screen and for the settings.
 *
 * Three lessons of the portfolio are built in, so that no caller has to remember them:
 *
 * - **Class 3 only**, with a `CryptoObject` — the mask comes from [StrongBiometrics], the same one the
 *   settings ask `canAuthenticate` with.
 * - **`withResumed` before `authenticate`**: androidx.biometric 1.1.0 commits a fragment transaction,
 *   which gives up in silence once the activity has saved its state — reachable by leaving the app
 *   while the key is being prepared (SMS Tech, Agenda Tech).
 * - **One prompt at a time**: the lock screen asks on display and again on every tap, and two
 *   overlapping calls fight over the same fragment.
 *
 * Cancelling the calling coroutine cancels the prompt: a lock screen that leaves composition — the PIN
 * was typed meanwhile — must not leave a prompt over the unlocked app.
 */
object BiometricPrompts {

    private val inFlight = AtomicBoolean(false)

    /** Main thread. */
    suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        usePin: String,
    ): BiometricOutcome {
        if (!inFlight.compareAndSet(false, true)) return BiometricOutcome.Busy
        try {
            val outcome = CompletableDeferred<BiometricOutcome>()
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        outcome.complete(BiometricOutcome.Succeeded(result.cryptoObject?.cipher))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        outcome.complete(
                            if (errorCode in CANCELLATIONS) BiometricOutcome.Cancelled else BiometricOutcome.Failed,
                        )
                    }
                    // `onAuthenticationFailed` — a finger not recognised — is left to the prompt,
                    // which stays up and says so itself.
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(usePin)
                .setAllowedAuthenticators(StrongBiometrics.ALLOWED_AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build()
            try {
                activity.lifecycle.withResumed { prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) }
                return outcome.await()
            } catch (e: CancellationException) {
                prompt.cancelAuthentication()
                throw e
            }
        } finally {
            inFlight.set(false)
        }
    }

    private val CANCELLATIONS = setOf(
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_CANCELED,
    )
}
