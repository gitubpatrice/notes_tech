package com.filestech.notes_tech.ui.applock

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.R
import com.filestech.notes_tech.security.applock.AppLockKeystoreUnavailableException
import com.filestech.notes_tech.security.applock.AppLockManager
import com.filestech.notes_tech.security.applock.AppLockParams
import com.filestech.notes_tech.security.applock.BiometricPreparation
import com.filestech.notes_tech.security.applock.BiometricUnlockKey
import com.filestech.notes_tech.security.applock.LockChange
import com.filestech.notes_tech.security.applock.PinCheck
import com.filestech.notes_tech.security.applock.PinProof
import com.filestech.notes_tech.security.applock.RelockDelay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.crypto.Cipher
import javax.inject.Inject

/** Which change the PIN sheet serves. */
enum class PinPurpose { ENABLE, DISABLE, CHANGE_PIN, ENABLE_BIOMETRIC, LENGTHEN_DELAY }

/** Where the sheet is. [CURRENT] proves the PIN; [NEW] and [CONFIRM] choose one. */
enum class PinStep { CURRENT, NEW, CONFIRM }

enum class PinSheetMessage {
    WRONG_PIN,
    MISMATCH,
    TOO_SHORT,
    KEYSTORE_UNAVAILABLE,
    NOT_RECORDED,
    NOT_SAVED,
    UNVERIFIABLE,
}

data class PinSheetState(
    val purpose: PinPurpose,
    val step: PinStep,
    val busy: Boolean = false,
    val message: PinSheetMessage? = null,
    val waitMillis: Long = 0L,
    val waitAnnouncedMillis: Long = 0L,
    /** Bumped after every entry: the sheet clears the typed PIN at each change. */
    val attempts: Int = 0,
)

data class AppLockSettingsState(
    val configured: Boolean,
    val biometricEnabled: Boolean,
    val relockDelay: RelockDelay,
    val sheet: PinSheetState? = null,
    /** Biometrics are being set up: the screen shows the prompt, which needs the activity. */
    val enrollingBiometric: Boolean = false,
    /** Said once in a snackbar, then consumed ([onNoticeShown]). */
    @StringRes val notice: Int? = null,
)

/**
 * The app lock's settings. Every change that lowers protection goes through the PIN sheet's
 * [PinStep.CURRENT] first, which is where the [PinProof] the manager demands comes from — the rule
 * is enforced by [AppLockManager], this class only walks the user to it.
 *
 * The PIN attempts made here are THE SAME attempts as on the lock screen: same counter, same wait.
 * Otherwise the unlocked app would be an unthrottled way to find the PIN — and with it, very likely,
 * the PIN of a vault.
 */
@HiltViewModel
class AppLockSettingsViewModel @Inject constructor(
    private val appLock: AppLockManager,
    private val biometricKey: BiometricUnlockKey,
    private val io: CoroutineDispatcher,
) : ViewModel() {

    private data class Local(
        val sheet: PinSheetState? = null,
        val enrollingBiometric: Boolean = false,
        val notice: Int? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AppLockSettingsState> = combine(
        appLock.configured,
        appLock.biometricEnabled,
        appLock.relockDelay,
        local,
    ) { configured, biometric, delay, ui ->
        AppLockSettingsState(configured, biometric, delay, ui.sheet, ui.enrollingBiometric, ui.notice)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        // Read, not assumed: a switch that flips under the user's eyes on first display is a lie.
        initialValue = AppLockSettingsState(
            appLock.isConfigured(),
            appLock.isBiometricEnabled(),
            appLock.relockDelayNow(),
        ),
    )

    // Held between the steps of ONE sheet, never in the UI state: nothing to show, and a PIN.
    private var proof: PinProof? = null
    private var firstEntry: String? = null
    private var pendingDelay: RelockDelay? = null

    private var ticker: Job? = null

    fun onToggleLock() = if (appLock.isConfigured()) {
        openSheet(PinPurpose.DISABLE, PinStep.CURRENT)
    } else {
        openSheet(PinPurpose.ENABLE, PinStep.NEW)
    }

    fun onChangePin() = openSheet(PinPurpose.CHANGE_PIN, PinStep.CURRENT)

    /**
     * 🔴 **The app left the screen: an open sheet closes, and its PIN proof goes with it** — security
     * audit of 2026-09-26, V2.
     *
     * The proof of the current PIN was kept at the NEW step until the sheet itself opened or closed.
     * With a relock delay, leaving and coming back within it does not lock the app, so whoever picked
     * up the phone found the sheet asking for a NEW PIN — and changed it without the old one, the
     * biometric unlock letting the owner in unaware.
     *
     * ⚠️ Only when a sheet is open: during the biometric prompt the sheet is already closed, and the
     * enrolment still needs the proof it was granted.
     */
    fun onLeftTheApp() {
        if (local.value.sheet != null) close(notice = null)
    }

    /** Off needs nothing — it raises protection. On needs the PIN, then a real prompt. */
    fun onToggleBiometric() {
        if (appLock.isBiometricEnabled()) {
            viewModelScope.launch { report(appLock.setBiometricEnabled(false, proof = null), success = null) }
        } else {
            openSheet(PinPurpose.ENABLE_BIOMETRIC, PinStep.CURRENT)
        }
    }

    /** A shorter delay needs nothing; a longer one needs the PIN. */
    fun onDelayChosen(delay: RelockDelay) {
        if (delay.seconds > appLock.relockDelayNow().seconds) {
            // AFTER `openSheet`, which forgets the previous flow — `pendingDelay` included. Set
            // before, it was wiped at once, and the PIN typed next found nothing to apply
            // (`AppLockSettingsViewModelTest.delay_flow`, 2026-09-24).
            openSheet(PinPurpose.LENGTHEN_DELAY, PinStep.CURRENT)
            pendingDelay = delay
        } else {
            viewModelScope.launch { report(appLock.setRelockDelay(delay, proof = null), success = null) }
        }
    }

    fun onSheetDismissed() {
        forgetFlow()
        local.update { it.copy(sheet = null) }
    }

    fun onNoticeShown() = local.update { it.copy(notice = null) }

    fun onPinEntered(pin: String) {
        val sheet = local.value.sheet ?: return
        if (sheet.busy || sheet.waitMillis > 0) return
        if (!AppLockParams.isValidPin(pin)) {
            updateSheet { answered(PinSheetMessage.TOO_SHORT) }
            return
        }
        when (sheet.step) {
            PinStep.CURRENT -> checkCurrent(pin, sheet.purpose)
            PinStep.NEW -> {
                firstEntry = pin
                updateSheet { answered(message = null, step = PinStep.CONFIRM) }
            }
            PinStep.CONFIRM -> if (pin == firstEntry) {
                saveNewPin(pin, sheet.purpose)
            } else {
                // Both entries are dropped: which of the two was mistyped, nobody knows.
                firstEntry = null
                updateSheet { answered(PinSheetMessage.MISMATCH, step = PinStep.NEW) }
            }
        }
    }

    /**
     * The key, then the cipher for the enrolment prompt — made AFTER the PIN, never before: the key
     * exists only for someone who proved the PIN. `null`: the reason is already on its way to the user.
     */
    suspend fun prepareEnrolment(): Cipher? {
        val preparation = withContext(io) {
            try {
                biometricKey.create()
                biometricKey.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Deliberately broad, like `AndroidBiometricUnlockKey.prepare`: key generation goes
                // through an IPC, and OEM builds surface its refusals as unchecked exceptions.
                Timber.w(e, "biometric unlock key could not be created")
                null
            }
        }
        val cipher = (preparation as? BiometricPreparation.Ready)?.cipher
        if (cipher == null) abandonEnrolment(R.string.app_lock_biometric_setup_failed)
        return cipher
    }

    /** On only if the prompt's cipher actually RAN — the same proof the lock screen demands. */
    suspend fun completeEnrolment(outcome: BiometricOutcome) {
        val ran = outcome is BiometricOutcome.Succeeded && withContext(io) { biometricKey.verify(outcome.cipher) }
        if (!ran) {
            val said = if (outcome is BiometricOutcome.Failed || outcome is BiometricOutcome.Succeeded) {
                R.string.app_lock_biometric_setup_failed
            } else {
                null
            }
            abandonEnrolment(said)
            return
        }
        val change = proof?.let { appLock.setBiometricEnabled(true, it) } ?: LockChange.ProofRequired
        proof = null
        local.update { it.copy(enrollingBiometric = false) }
        if (change != LockChange.Saved) deleteBiometricKey()
        report(change, success = R.string.app_lock_biometric_enabled)
    }

    /** The prompt was dismissed, failed, or the screen went away: no key is left behind. */
    fun abandonEnrolment(@StringRes notice: Int? = null) {
        proof = null
        local.update { it.copy(enrollingBiometric = false, notice = notice ?: it.notice) }
        viewModelScope.launch { deleteBiometricKey() }
    }

    private fun openSheet(purpose: PinPurpose, step: PinStep) {
        forgetFlow()
        local.update { it.copy(sheet = PinSheetState(purpose, step)) }
        // A wait left by earlier attempts — here or on the lock screen — shows at once.
        if (step == PinStep.CURRENT) startTicker()
    }

    private fun checkCurrent(pin: String, purpose: PinPurpose) {
        updateSheet { copy(busy = true, message = null) }
        viewModelScope.launch {
            when (val check = appLock.attemptPin(pin)) {
                is PinCheck.Accepted -> {
                    proof = check.proof
                    afterProof(purpose, check.proof)
                }
                is PinCheck.Rejected -> {
                    updateSheet { withWait(check.waitMillis).answered(PinSheetMessage.WRONG_PIN) }
                    startTicker()
                }
                is PinCheck.Throttled -> {
                    updateSheet { withWait(check.remainingMillis).answered(message = null) }
                    startTicker()
                }
                PinCheck.Unverifiable -> updateSheet { answered(PinSheetMessage.UNVERIFIABLE) }
                PinCheck.Unavailable -> updateSheet { answered(PinSheetMessage.KEYSTORE_UNAVAILABLE) }
                PinCheck.NotRecorded -> updateSheet { answered(PinSheetMessage.NOT_RECORDED) }
            }
        }
    }

    private suspend fun afterProof(purpose: PinPurpose, proof: PinProof) {
        when (purpose) {
            PinPurpose.DISABLE -> report(appLock.disable(proof), success = R.string.app_lock_disabled)
            PinPurpose.CHANGE_PIN -> updateSheet { answered(message = null, step = PinStep.NEW) }
            PinPurpose.ENABLE_BIOMETRIC -> local.update { it.copy(sheet = null, enrollingBiometric = true) }
            PinPurpose.LENGTHEN_DELAY -> {
                val change = pendingDelay?.let { appLock.setRelockDelay(it, proof) } ?: LockChange.ProofRequired
                report(change, success = null)
            }
            // Enabling starts at NEW: there is no current PIN to prove.
            PinPurpose.ENABLE -> Unit
        }
    }

    private fun saveNewPin(pin: String, purpose: PinPurpose) {
        updateSheet { copy(busy = true, message = null) }
        viewModelScope.launch {
            when (purpose) {
                PinPurpose.ENABLE -> report(appLock.enable(pin), success = R.string.app_lock_enabled)
                PinPurpose.CHANGE_PIN -> {
                    val change = proof?.let { appLock.changePin(it, pin) } ?: LockChange.ProofRequired
                    report(change, success = R.string.app_lock_pin_changed)
                }
                // Only these two choose a new PIN.
                PinPurpose.DISABLE, PinPurpose.ENABLE_BIOMETRIC, PinPurpose.LENGTHEN_DELAY -> Unit
            }
        }
    }

    /**
     * Ends a change: the sheet closes on success or on a stale proof (which asks for the PIN again),
     * and stays open, with the reason, when retrying can help.
     */
    private fun report(change: LockChange, @StringRes success: Int?) {
        when (change) {
            LockChange.Saved -> close(notice = success)
            LockChange.ProofRequired -> close(notice = R.string.app_lock_proof_expired)
            LockChange.NotSaved -> stayOr(PinSheetMessage.NOT_SAVED, R.string.app_lock_not_saved)
            LockChange.Unavailable ->
                stayOr(PinSheetMessage.KEYSTORE_UNAVAILABLE, R.string.app_lock_keystore_unavailable)
        }
    }

    private fun close(@StringRes notice: Int?) {
        forgetFlow()
        local.update { it.copy(sheet = null, notice = notice ?: it.notice) }
    }

    private fun stayOr(message: PinSheetMessage, @StringRes notice: Int) {
        if (local.value.sheet != null) {
            updateSheet { answered(message) }
        } else {
            local.update { it.copy(notice = notice) }
        }
    }

    private fun forgetFlow() {
        proof = null
        firstEntry = null
        pendingDelay = null
        ticker?.cancel()
    }

    private fun updateSheet(change: PinSheetState.() -> PinSheetState) =
        local.update { current -> current.copy(sheet = current.sheet?.change()) }

    private fun PinSheetState.withWait(millis: Long): PinSheetState =
        if (millis > 0) copy(waitMillis = millis, waitAnnouncedMillis = millis) else this

    /** A wait found already running is announced once, as it stands now. */
    private fun PinSheetState.withAnnouncedWait(millis: Long): PinSheetState =
        if (waitAnnouncedMillis == 0L) copy(waitAnnouncedMillis = millis) else this

    /** The sheet once an entry is dealt with: no longer busy, [message] said, the typed PIN cleared. */
    private fun PinSheetState.answered(message: PinSheetMessage?, step: PinStep = this.step): PinSheetState =
        copy(busy = false, message = message, step = step, attempts = attempts + 1)

    private suspend fun deleteBiometricKey() = withContext(io) {
        try {
            biometricKey.delete()
        } catch (e: AppLockKeystoreUnavailableException) {
            Timber.w(e, "biometric unlock key not deleted")
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var remaining = appLock.throttleRemainingMillis()
            val waited = remaining > 0
            if (waited) updateSheet { withAnnouncedWait(remaining) }
            while (remaining > 0) {
                updateSheet { copy(waitMillis = remaining) }
                delay(TICK_MILLIS)
                remaining = appLock.throttleRemainingMillis()
            }
            // The message goes with the wait, as on the lock screen.
            updateSheet { copy(waitMillis = 0L, waitAnnouncedMillis = 0L, message = if (waited) null else message) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TICK_MILLIS = 500L
    }
}
