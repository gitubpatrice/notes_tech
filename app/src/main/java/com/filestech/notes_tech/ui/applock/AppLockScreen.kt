package com.filestech.notes_tech.ui.applock

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.security.applock.AppLockParams
import com.filestech.notes_tech.security.applock.StrongBiometrics
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import com.filestech.notes_tech.ui.common.ClavierNumerique
import com.filestech.notes_tech.ui.common.PointsDeSaisie
import com.filestech.notes_tech.ui.common.retryWaitMessage
import com.filestech.notes_tech.ui.panic.PanicConfirmDialog
import com.filestech.notes_tech.ui.panic.activityPanicViewModel
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.theme.Formes
import kotlinx.coroutines.launch

/**
 * The lock screen, wired: its state, the biometric prompt, and the panic mode behind "forgot your PIN".
 *
 * @param epoch the lock this screen stands for ([com.filestech.notes_tech.security.applock.AppLockState.Locked]).
 *   Biometrics are offered once per epoch: a new lock asks again, a dismissed prompt does not come back
 *   by itself — which, on a device whose prompt pauses the activity, would loop.
 */
@Composable
fun AppLockRoute(epoch: Int, modifier: Modifier = Modifier) {
    val viewModel: AppLockViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val biometricPreferred by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    // Triggers only: the report is shown by the host at the top of the app, which a lock lifting
    // mid-panic cannot take away (see `activityPanicViewModel`).
    val panic = activityPanicViewModel()

    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    // Asked at every composition, never remembered: this screen can stay composed across a trip to
    // the background, during which a fingerprint may be added or removed (Agenda Tech, LockScreen).
    val biometricUsable = biometricPreferred && activity != null && StrongBiometrics.isAvailable(context)

    val scope = rememberCoroutineScope()
    val promptTitle = stringResource(R.string.app_lock_biometric_prompt_title)
    val usePin = stringResource(R.string.app_lock_biometric_prompt_use_pin)
    val askBiometric: () -> Unit = {
        if (activity != null) {
            // In the screen's scope: leaving composition — the PIN typed meanwhile — cancels the prompt.
            scope.launch {
                val cipher = viewModel.prepareBiometric() ?: return@launch
                viewModel.completeBiometric(BiometricPrompts.authenticate(activity, cipher, promptTitle, usePin), epoch)
            }
        }
    }

    LaunchedEffect(epoch, biometricUsable) {
        if (biometricUsable) askBiometric()
    }

    AppLockScreen(
        state = state,
        biometricAvailable = biometricUsable,
        onSubmit = viewModel::submit,
        onPinChanged = viewModel::clearMessage,
        onBiometric = askBiometric,
        onErase = panic::trigger,
        modifier = modifier,
    )
}

/**
 * The lock screen itself, **without its ViewModel**, so that every state it can show is reached in a
 * test — the wait, the unverifiable lock, the path to panic mode — without a real lock on the device.
 *
 * ⚠️ `FLAG_SECURE` while it is composed, whatever the user's screenshot setting: the keypad's pressed
 * keys are always in the same place, so a recording of them is the PIN. The PIN vault sheets have the
 * same guard for the same reason.
 */
@Composable
fun AppLockScreen(
    state: AppLockUiState,
    biometricAvailable: Boolean,
    onSubmit: (String) -> Unit,
    onPinChanged: () -> Unit,
    onBiometric: () -> Unit,
    onErase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureWindowGuard()

    var typed by remember { mutableStateOf("") }
    // The digits are hidden again after every attempt: a PIN revealed for one attempt must not stay
    // readable over the user's shoulder for the next.
    var digitsVisible by remember(state.attempts) { mutableStateOf(false) }
    var forgotOpen by rememberSaveable { mutableStateOf(false) }
    var panicConfirmOpen by rememberSaveable { mutableStateOf(false) }

    // Cleared after EVERY attempt, right or wrong: typing over a wrong PIN would otherwise append to it
    // and spend a second attempt on a PIN nobody typed (the vault sheets' §-lesson of 2026-08-14).
    LaunchedEffect(state.attempts) { typed = "" }

    val waiting = state.waitMillis > 0
    val canType = state.verifiable && !state.busy && !waiting

    Surface(modifier = modifier.fillMaxSize().testTag(LOCK_SCREEN_TAG)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // ⚠️ Sized to fit a Galaxy S9 — 360 x 740 dp, system bars included — WITHOUT scrolling, at
            // the default text size: laid out like the vault sheet, with the logo above the title and
            // 72 dp keys, it needed about 800 dp, and "forgot your PIN?" was below the fold (measured
            // on 2026-09-24). The screen still scrolls, for larger text.
            Column(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_splash_logo),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(R.string.app_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 12.dp).semantics { heading() },
                    )
                }

                if (!state.verifiable) {
                    // A PIN that can no longer be checked does not close the biometric door: a Class 3
                    // biometric authenticates the owner on its own, and the state — the PIN key lost,
                    // the biometric one intact — comes only from a Keystore fault. Letting the owner in
                    // beats making them erase everything (external review, GPT-5.6, 2026-09-24, which
                    // found the button hidden here and the text saying erasing was the only way).
                    Text(
                        text = stringResource(
                            if (biometricAvailable) {
                                R.string.app_lock_unverifiable_biometric
                            } else {
                                R.string.app_lock_unverifiable
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    if (biometricAvailable) BiometricButton(enabled = !state.busy, onClick = onBiometric)
                    EraseButton(onClick = { panicConfirmOpen = true })
                    return@Column
                }

                Text(text = stringResource(R.string.app_lock_prompt), style = MaterialTheme.typography.bodyLarge)
                PointsDeSaisie(
                    saisi = typed,
                    longueurMax = AppLockParams.PIN_MAX_LENGTH,
                    visible = digitsVisible,
                    onBasculer = { digitsVisible = !digitsVisible },
                )
                MessageSlot(
                    busy = state.busy,
                    waitMillis = state.waitMillis,
                    waitAnnouncedMillis = state.waitAnnouncedMillis,
                    message = state.message?.let { stringResource(it.text()) },
                )
                ClavierNumerique(
                    enabled = canType,
                    onDigit = { digit ->
                        if (typed.length < AppLockParams.PIN_MAX_LENGTH) typed += digit
                        onPinChanged()
                    },
                    onDelete = {
                        typed = typed.dropLast(1)
                        onPinChanged()
                    },
                    tailleDeTouche = KEY_SIZE,
                )
                Button(
                    onClick = { onSubmit(typed) },
                    enabled = canType && AppLockParams.isValidPin(typed),
                    shape = Formes.bouton,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.app_lock_unlock))
                }
                // Side by side, and onto two lines when a translation does not fit.
                FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    if (biometricAvailable) BiometricButton(enabled = !state.busy, onClick = onBiometric)
                    TextButton(onClick = { forgotOpen = true }) {
                        Text(stringResource(R.string.app_lock_forgot_pin))
                    }
                }
            }
        }
    }

    if (forgotOpen) {
        AlertDialog(
            onDismissRequest = { forgotOpen = false },
            title = { Text(stringResource(R.string.app_lock_forgot_pin)) },
            text = { Text(stringResource(R.string.app_lock_forgot_body)) },
            dismissButton = {
                ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = { forgotOpen = false })
            },
            confirmButton = {
                EraseButton(
                    onClick = {
                        forgotOpen = false
                        panicConfirmOpen = true
                    },
                )
            },
        )
    }
    if (panicConfirmOpen) {
        // The panic mode's own confirmation, word typed and all: forgetting the PIN is no reason to
        // make destroying every note easier than it is from the settings.
        PanicConfirmDialog(
            onDismiss = { panicConfirmOpen = false },
            onConfirmed = {
                panicConfirmOpen = false
                onErase()
            },
        )
    }
}

@Composable
private fun BiometricButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Outlined.Fingerprint, contentDescription = null)
        Text(text = stringResource(R.string.app_lock_use_biometric), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun EraseButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = Formes.bouton,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(stringResource(R.string.app_lock_forgot_erase))
    }
}

/**
 * Where a PIN pad speaks — a wrong PIN, a wait, a keystore that did not answer. Shared by the lock
 * screen and the settings' PIN sheet.
 *
 * ⚠️ Its height is reserved (two lines, in the system's text size) so that a message appearing does
 * not move the keypad under a finger on its way — the vault sheets' §87.
 *
 * ⚠️ The countdown ticks on screen, but what a screen reader hears is fixed when the wait STARTS
 * ([waitAnnouncedMillis]): a live region re-read every second would drown everything else the user
 * tries to hear.
 */
@Composable
internal fun MessageSlot(busy: Boolean, waitMillis: Long, waitAnnouncedMillis: Long, message: String?) {
    val shown: String? = if (waitMillis > 0) retryWaitMessage(waitMillis) else message
    val announced: String? = if (waitMillis > 0) {
        retryWaitMessage(waitAnnouncedMillis.coerceAtLeast(waitMillis))
    } else {
        shown
    }
    val twoLines = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() * 2 }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = twoLines)
            .testTag(LOCK_MESSAGE_TAG)
            .clearAndSetSemantics {
                if (announced != null) {
                    contentDescription = announced
                    liveRegion = LiveRegionMode.Assertive
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            shown != null -> Text(
                text = shown,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun LockMessage.text(): Int = when (this) {
    LockMessage.WRONG_PIN -> R.string.app_lock_wrong_pin
    LockMessage.KEYSTORE_UNAVAILABLE -> R.string.app_lock_keystore_unavailable
    LockMessage.NOT_RECORDED -> R.string.app_lock_not_recorded
    LockMessage.BIOMETRIC_FAILED -> R.string.app_lock_biometric_failed
    LockMessage.BIOMETRIC_INVALIDATED -> R.string.app_lock_biometric_invalidated
}

/** Named for tests: the lock screen must be FOUND when locked, and ABSENT when not. */
const val LOCK_SCREEN_TAG = "app-lock-screen"

internal const val LOCK_MESSAGE_TAG = "app-lock-message"

private val CONTENT_MAX_WIDTH = 360.dp

/** The vault sheets keep 72 dp; 64 dp is still well above the 48 dp touch-target minimum. */
private val KEY_SIZE = 64.dp
