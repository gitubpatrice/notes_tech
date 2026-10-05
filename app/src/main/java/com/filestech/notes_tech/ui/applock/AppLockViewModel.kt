package com.filestech.notes_tech.ui.applock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.security.applock.AppLockManager
import com.filestech.notes_tech.security.applock.BiometricPreparation
import com.filestech.notes_tech.security.applock.BiometricUnlockKey
import com.filestech.notes_tech.security.applock.PinCheck
import com.filestech.notes_tech.security.applock.PinRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject

/** What the lock screen can have to say. */
enum class LockMessage { WRONG_PIN, KEYSTORE_UNAVAILABLE, NOT_RECORDED, BIOMETRIC_FAILED, BIOMETRIC_INVALIDATED }

data class AppLockUiState(
    /** False once no PIN can be checked on this device: only the erase path is left. */
    val verifiable: Boolean = true,
    /** A PIN is being checked — a key derivation, up to a second on an old phone. */
    val busy: Boolean = false,
    /** What is left of the wait, ticking. */
    val waitMillis: Long = 0L,
    /**
     * The wait as it was when it started, and as it is ANNOUNCED. The visible countdown ticks; a
     * screen reader must not read it out every second.
     */
    val waitAnnouncedMillis: Long = 0L,
    val message: LockMessage? = null,
    /** Bumped after every attempt: the screen clears the typed PIN at each change. */
    val attempts: Int = 0,
)

/**
 * The lock screen's state, scoped to the activity: it outlives each lock, the wait's countdown with it.
 *
 * It decides nothing — every decision is [AppLockManager]'s. It turns outcomes into what the screen
 * shows, and keeps the countdown ticking.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val appLock: AppLockManager,
    private val biometricKey: BiometricUnlockKey,
    private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(AppLockUiState(verifiable = appLock.pinRecord() !is PinRecord.Unreadable))
    val state: StateFlow<AppLockUiState> = _state.asStateFlow()

    val biometricEnabled: StateFlow<Boolean> = appLock.biometricEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = appLock.isBiometricEnabled(),
    )

    /** The single countdown; replaced, never stacked (Agenda Tech: one leaked coroutine per failure). */
    private var ticker: Job? = null

    init {
        // A wait left by a previous process — or by a previous lock of this one — shows at once.
        startTicker()
    }

    fun submit(pin: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val check = appLock.attemptPin(pin)
            _state.update { current ->
                val next = current.copy(busy = false, attempts = current.attempts + 1)
                when (check) {
                    is PinCheck.Accepted -> next
                    is PinCheck.Rejected -> next.withWait(check.waitMillis).copy(message = LockMessage.WRONG_PIN)
                    is PinCheck.Throttled -> next.withWait(check.remainingMillis)
                    PinCheck.Unverifiable -> next.copy(verifiable = false)
                    PinCheck.Unavailable -> next.copy(message = LockMessage.KEYSTORE_UNAVAILABLE)
                    PinCheck.NotRecorded -> next.copy(message = LockMessage.NOT_RECORDED)
                }
            }
            if (check is PinCheck.Rejected || check is PinCheck.Throttled) startTicker()
        }
    }

    /** Typing clears the last message, so that the next one is announced even if it is the same. */
    fun clearMessage() {
        if (_state.value.message != null) _state.update { it.copy(message = null) }
    }

    /** The cipher to hand to the prompt, or `null` — the reason is then on screen. */
    suspend fun prepareBiometric(): Cipher? = when (val preparation = withContext(io) { biometricKey.prepare() }) {
        is BiometricPreparation.Ready -> preparation.cipher
        BiometricPreparation.Invalidated -> {
            appLock.onBiometricKeyLost()
            _state.update { it.copy(message = LockMessage.BIOMETRIC_INVALIDATED) }
            null
        }
        BiometricPreparation.Unavailable -> {
            _state.update { it.copy(message = LockMessage.BIOMETRIC_FAILED) }
            null
        }
    }

    /**
     * Opens only if the cipher the prompt authorised actually RUNS — the callback alone proves nothing
     * (Agenda Tech, audit F3) — and only for the lock [epoch] the prompt was shown for.
     */
    suspend fun completeBiometric(outcome: BiometricOutcome, epoch: Int) {
        when (outcome) {
            is BiometricOutcome.Succeeded -> {
                val ran = withContext(io) { biometricKey.verify(outcome.cipher) }
                if (ran) {
                    appLock.unlockWithBiometric(epoch)
                } else {
                    _state.update { it.copy(message = LockMessage.BIOMETRIC_FAILED) }
                }
            }
            BiometricOutcome.Failed -> _state.update { it.copy(message = LockMessage.BIOMETRIC_FAILED) }
            BiometricOutcome.Cancelled, BiometricOutcome.Busy -> Unit
        }
    }

    private fun AppLockUiState.withWait(millis: Long): AppLockUiState =
        if (millis > 0) copy(waitMillis = millis, waitAnnouncedMillis = millis) else this

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var remaining = appLock.throttleRemainingMillis()
            if (remaining > 0 && _state.value.waitAnnouncedMillis == 0L) {
                _state.update { it.copy(waitAnnouncedMillis = remaining) }
            }
            val waited = remaining > 0
            while (remaining > 0) {
                _state.update { it.copy(waitMillis = remaining) }
                delay(TICK_MILLIS)
                remaining = appLock.throttleRemainingMillis()
            }
            // The message goes with the wait: "Incorrect PIN" read out again thirty seconds later,
            // when the keypad comes back, would say the wrong thing at the wrong time.
            _state.update {
                it.copy(waitMillis = 0L, waitAnnouncedMillis = 0L, message = if (waited) null else it.message)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TICK_MILLIS = 500L
    }
}
