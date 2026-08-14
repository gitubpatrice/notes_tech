package com.filestech.notes_tech.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.LocalePreference
import com.filestech.notes_tech.data.prefs.ThemePreference

/**
 * Les réglages.
 *
 * ⚠️ **Le délai d'auto-verrouillage n'est pas un confort.** Il décide combien de temps la clé d'un
 * coffre reste en mémoire après la dernière interaction. `0` veut dire « jamais », et c'est un
 * choix légitime que l'écran doit proposer sans le décourager — le verrouillage au passage en
 * arrière-plan reste actif dans tous les cas.
 */
@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenAbout: () -> Unit, onOpenLegal: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Recreation de l'activite au changement de langue, cf. le commentaire du selecteur ci-dessous.
    val activite = LocalActivity.current

    var choixDeTheme by remember { mutableStateOf(false) }
    var choixDeLangue by remember { mutableStateOf(false) }
    var choixDeDelai by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_close),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            TitreDeSection(stringResource(R.string.settings_section_appearance))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = { Text(stringResource(libelleDeTheme(state.theme))) },
                modifier = Modifier.clickable { choixDeTheme = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = { Text(stringResource(libelleDeLangue(state.locale))) },
                modifier = Modifier.clickable { choixDeLangue = true },
            )
            HorizontalDivider()

            TitreDeSection(stringResource(R.string.settings_section_security))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_secure_window)) },
                supportingContent = { Text(stringResource(R.string.settings_secure_window_subtitle)) },
                trailingContent = {
                    Switch(checked = state.secureWindow, onCheckedChange = viewModel::setSecureWindow)
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_vault_auto_lock)) },
                supportingContent = {
                    Text(
                        if (state.vaultAutoLockMinutes == 0) {
                            stringResource(R.string.settings_vault_auto_lock_never)
                        } else {
                            pluralStringResource(
                                R.plurals.settings_vault_auto_lock_minutes,
                                state.vaultAutoLockMinutes,
                                state.vaultAutoLockMinutes,
                            )
                        },
                    )
                },
                modifier = Modifier.clickable { choixDeDelai = true },
            )
            HorizontalDivider()

            TitreDeSection(stringResource(R.string.settings_section_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_title)) },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenAbout),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.legal_title)) },
                leadingContent = { Icon(Icons.Outlined.Gavel, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenLegal),
            )
        }
    }

    if (choixDeTheme) {
        DialogueDeChoix(
            titre = stringResource(R.string.settings_theme),
            options = ThemePreference.entries,
            actif = state.theme,
            libelle = { stringResource(libelleDeTheme(it)) },
            onDismiss = { choixDeTheme = false },
            onSelect = {
                choixDeTheme = false
                viewModel.setTheme(it)
            },
        )
    }
    if (choixDeLangue) {
        DialogueDeChoix(
            titre = stringResource(R.string.settings_language),
            options = LocalePreference.entries,
            actif = state.locale,
            libelle = { stringResource(libelleDeLangue(it)) },
            onDismiss = { choixDeLangue = false },
            onSelect = { choisie ->
                choixDeLangue = false
                if (choisie != state.locale) {
                    viewModel.setLocale(choisie)
                    // 🔴 **La langue s'applique dans `attachBaseContext`, qui ne s'exécute qu'à la
                    // création de l'activité.** Sans cette recréation, l'utilisateur choisit
                    // « English », revient, et tout reste en français jusqu'au prochain démarrage.
                    // Le réglage était bien écrit : c'est son EFFET qui manquait.
                    //
                    // Même motif que le délai d'auto-verrouillage plus tôt dans la phase — un réglage
                    // écrit et jamais relu donne l'affichage du choix, pas le choix. Relevé par une
                    // relecture externe (GPT-5.2, 2026-08-14).
                    activite?.recreate()
                }
            },
        )
    }
    if (choixDeDelai) {
        DialogueDeChoix(
            titre = stringResource(R.string.settings_vault_auto_lock),
            options = DELAIS_PROPOSES,
            actif = state.vaultAutoLockMinutes,
            libelle = { minutes ->
                if (minutes == 0) {
                    stringResource(R.string.settings_vault_auto_lock_never)
                } else {
                    pluralStringResource(R.plurals.settings_vault_auto_lock_minutes, minutes, minutes)
                }
            },
            onDismiss = { choixDeDelai = false },
            onSelect = {
                choixDeDelai = false
                viewModel.setVaultAutoLockMinutes(it)
            },
        )
    }
}

@Composable
private fun TitreDeSection(texte: String) {
    Text(
        text = texte,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun <T> DialogueDeChoix(
    titre: String,
    options: List<T>,
    actif: T,
    libelle: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titre) },
        text = {
            Column {
                for (option in options) {
                    ListItem(
                        headlineContent = { Text(libelle(option)) },
                        // `onClick = null` sur le bouton radio : c'est la ligne entière qui est
                        // cliquable, et un second point de contact ferait deux cibles pour un seul
                        // choix — l'une d'elles plus petite que le minimum accessible.
                        leadingContent = { RadioButton(selected = option == actif, onClick = null) },
                        modifier = Modifier.clickable { onSelect(option) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

private fun libelleDeTheme(value: ThemePreference): Int = when (value) {
    ThemePreference.SYSTEM -> R.string.settings_theme_system
    ThemePreference.LIGHT -> R.string.settings_theme_light
    ThemePreference.DARK -> R.string.settings_theme_dark
}

private fun libelleDeLangue(value: LocalePreference): Int = when (value) {
    LocalePreference.SYSTEM -> R.string.settings_language_system
    LocalePreference.FRENCH -> R.string.settings_language_fr
    LocalePreference.ENGLISH -> R.string.settings_language_en
}

/** Les délais proposés, en minutes. `0` = jamais. Repris de l'application publiée. */
private val DELAIS_PROPOSES = listOf(0, 1, 5, 15, 30, 60)
