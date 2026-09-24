package com.filestech.notes_tech.ui.applock

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.security.applock.AppLockParams
import com.filestech.notes_tech.security.applock.BiometricAvailability
import com.filestech.notes_tech.security.applock.RelockDelay
import com.filestech.notes_tech.security.applock.StrongBiometrics
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import com.filestech.notes_tech.ui.common.CarteFilesTech
import com.filestech.notes_tech.ui.common.ClavierNumerique
import com.filestech.notes_tech.ui.common.PointsDeSaisie
import com.filestech.notes_tech.ui.common.TitreDeSection
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.settings.DialogueDeChoix
import com.filestech.notes_tech.ui.theme.Formes
import kotlinx.coroutines.launch

/**
 * The settings' "App lock" section, wired: its ViewModel, the PIN sheet, the delay chooser and the
 * biometric enrolment prompt. [snackbars] are the settings screen's own.
 */
@Composable
fun AppLockSection(snackbars: SnackbarHostState) {
    val viewModel: AppLockSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    // Asked at every composition: enrolments change in the device settings, outside the app.
    val availability = StrongBiometrics.availability(context)
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var delayChooserOpen by rememberSaveable { mutableStateOf(false) }

    // Consumed, then shown from the composition's scope: consuming changes this effect's key and
    // cancels it, so a snackbar shown from inside would never appear (SettingsScreen, LigneDExport).
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        viewModel.onNoticeShown()
        scope.launch { snackbars.showSnackbar(resources.getString(notice)) }
    }

    val promptTitle = stringResource(R.string.app_lock_settings_biometric)
    val cancel = stringResource(R.string.common_cancel)
    LaunchedEffect(state.enrollingBiometric) {
        if (!state.enrollingBiometric) return@LaunchedEffect
        if (activity == null) {
            viewModel.abandonEnrolment(R.string.app_lock_biometric_setup_failed)
            return@LaunchedEffect
        }
        var settled = false
        try {
            val cipher = viewModel.prepareEnrolment()
            if (cipher != null) {
                val outcome = BiometricPrompts.authenticate(activity, cipher, promptTitle, cancel)
                viewModel.completeEnrolment(outcome)
            }
            settled = true
        } finally {
            // The section left composition mid-prompt — the app locked, typically: nothing may stay
            // half set up, least of all a key nobody saw pass a prompt.
            if (!settled) viewModel.abandonEnrolment()
        }
    }

    AppLockSectionContent(
        state = state,
        availability = availability,
        onToggleLock = viewModel::onToggleLock,
        onChangePin = viewModel::onChangePin,
        onToggleBiometric = viewModel::onToggleBiometric,
        onOpenDelay = { delayChooserOpen = true },
    )

    if (delayChooserOpen) {
        DialogueDeChoix(
            titre = stringResource(R.string.app_lock_settings_delay),
            options = RelockDelay.entries,
            actif = state.relockDelay,
            libelle = { relockDelayLabel(it) },
            onDismiss = { delayChooserOpen = false },
            onSelect = {
                delayChooserOpen = false
                viewModel.onDelayChosen(it)
            },
        )
    }

    state.sheet?.let { sheet ->
        AppLockPinSheet(sheet = sheet, onPin = viewModel::onPinEntered, onDismiss = viewModel::onSheetDismissed)
    }
}

/** The section's rows, without their ViewModel — the states a test must reach. */
@Composable
internal fun AppLockSectionContent(
    state: AppLockSettingsState,
    availability: BiometricAvailability,
    onToggleLock: () -> Unit,
    onChangePin: () -> Unit,
    onToggleBiometric: () -> Unit,
    onOpenDelay: () -> Unit,
) {
    TitreDeSection(stringResource(R.string.app_lock_settings_title))
    CarteFilesTech {
        Column {
            // `toggleable` on the ROW, `onCheckedChange = null` on the switch: one node carrying the
            // label, the state and the role — the S9 sweep of 2026-08-17 found a nameless switch
            // otherwise (04-PIEGES §77).
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_lock_settings_toggle)) },
                supportingContent = { Text(stringResource(R.string.app_lock_settings_toggle_subtitle)) },
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                trailingContent = { Switch(checked = state.configured, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.toggleable(
                    value = state.configured,
                    role = Role.Switch,
                    onValueChange = { onToggleLock() },
                ),
            )
            if (!state.configured) return@Column

            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_lock_settings_change_pin)) },
                leadingContent = { Icon(Icons.Outlined.Password, contentDescription = null) },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onChangePin),
            )

            HorizontalDivider()
            // Off stays reachable when the device no longer offers biometrics: the user must always
            // be able to turn it off. On needs a sensor, and one enrolled.
            val biometricRowEnabled = state.biometricEnabled || availability == BiometricAvailability.AVAILABLE
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_lock_settings_biometric)) },
                supportingContent = {
                    Text(
                        stringResource(
                            when (availability) {
                                BiometricAvailability.AVAILABLE -> R.string.app_lock_settings_biometric_subtitle
                                BiometricAvailability.NOT_ENROLLED -> R.string.app_lock_settings_biometric_not_enrolled
                                BiometricAvailability.UNAVAILABLE -> R.string.app_lock_settings_biometric_unavailable
                            },
                        ),
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Fingerprint, contentDescription = null) },
                trailingContent = {
                    Switch(checked = state.biometricEnabled, onCheckedChange = null, enabled = biometricRowEnabled)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.toggleable(
                    value = state.biometricEnabled,
                    enabled = biometricRowEnabled,
                    role = Role.Switch,
                    onValueChange = { onToggleBiometric() },
                ),
            )

            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_lock_settings_delay)) },
                supportingContent = { Text(relockDelayLabel(state.relockDelay)) },
                leadingContent = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onOpenDelay),
            )
        }
    }
}

/**
 * The PIN sheet of the settings: prove the current PIN, or choose a new one and confirm it.
 *
 * ⚠️ `FLAG_SECURE` while open, for the same reason as the lock screen and the vault PIN sheet: the
 * pressed keys are the PIN.
 */
@Composable
internal fun AppLockPinSheet(sheet: PinSheetState, onPin: (String) -> Unit, onDismiss: () -> Unit) {
    SecureWindowGuard()

    var typed by remember { mutableStateOf("") }
    var digitsVisible by remember(sheet.step, sheet.attempts) { mutableStateOf(false) }
    LaunchedEffect(sheet.step, sheet.attempts) { typed = "" }

    val busy by rememberUpdatedState(sheet.busy)
    // A PIN being checked or saved is not interrupted by a swipe: the change would land after the
    // sheet is gone, with nobody told. The Back gesture is vetoed the same way, below.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !busy },
    )
    val waiting = sheet.waitMillis > 0
    val canType = !sheet.busy && !waiting && sheet.message != PinSheetMessage.UNVERIFIABLE

    ModalBottomSheet(onDismissRequest = { if (!sheet.busy) onDismiss() }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    when (sheet.step) {
                        PinStep.CURRENT -> R.string.app_lock_pin_current_title
                        PinStep.NEW -> R.string.app_lock_pin_new_title
                        PinStep.CONFIRM -> R.string.app_lock_pin_confirm_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (sheet.step == PinStep.NEW) {
                Text(
                    text = stringResource(R.string.app_lock_pin_new_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            PointsDeSaisie(
                saisi = typed,
                longueurMax = AppLockParams.PIN_MAX_LENGTH,
                visible = digitsVisible,
                onBasculer = { digitsVisible = !digitsVisible },
            )
            MessageSlot(
                busy = sheet.busy,
                waitMillis = sheet.waitMillis,
                waitAnnouncedMillis = sheet.waitAnnouncedMillis,
                message = sheet.message?.let { pinSheetMessage(it) },
            )
            ClavierNumerique(
                enabled = canType,
                onDigit = { digit -> if (typed.length < AppLockParams.PIN_MAX_LENGTH) typed += digit },
                onDelete = { typed = typed.dropLast(1) },
            )
            Button(
                onClick = { onPin(typed) },
                enabled = canType && typed.isNotEmpty(),
                shape = Formes.bouton,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.common_validate))
            }
            ActionDeDialogue(
                texte = stringResource(R.string.common_cancel),
                onClick = { if (!sheet.busy) onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun pinSheetMessage(message: PinSheetMessage): String = when (message) {
    PinSheetMessage.WRONG_PIN -> stringResource(R.string.app_lock_wrong_pin)
    PinSheetMessage.MISMATCH -> stringResource(R.string.vault_pin_mismatch)
    PinSheetMessage.TOO_SHORT ->
        stringResource(R.string.vault_pin_too_short, AppLockParams.PIN_MIN_LENGTH, AppLockParams.PIN_MAX_LENGTH)
    PinSheetMessage.KEYSTORE_UNAVAILABLE -> stringResource(R.string.app_lock_keystore_unavailable)
    PinSheetMessage.NOT_RECORDED -> stringResource(R.string.app_lock_not_recorded)
    PinSheetMessage.NOT_SAVED -> stringResource(R.string.app_lock_not_saved)
    PinSheetMessage.UNVERIFIABLE -> stringResource(R.string.app_lock_unverifiable)
}

/** Read from the delay itself, so that a step added to [RelockDelay] cannot be shown with a wrong label. */
@Composable
internal fun relockDelayLabel(delay: RelockDelay): String {
    val seconds = delay.seconds
    return when {
        seconds == 0 -> stringResource(R.string.app_lock_delay_immediately)
        seconds < SECONDS_PER_MINUTE -> pluralStringResource(R.plurals.app_lock_delay_seconds, seconds, seconds)
        else -> {
            val minutes = seconds / SECONDS_PER_MINUTE
            pluralStringResource(R.plurals.app_lock_delay_minutes, minutes, minutes)
        }
    }
}

private const val SECONDS_PER_MINUTE = 60
