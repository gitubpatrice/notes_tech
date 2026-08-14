package com.filestech.notes_tech.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LocalePreference
import com.filestech.notes_tech.data.prefs.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SettingsUiState(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val locale: LocalePreference = LocalePreference.SYSTEM,
    val secureWindow: Boolean = true,
    val vaultAutoLockMinutes: Int = 15,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settings.theme,
        settings.locale,
        settings.secureWindow,
        settings.vaultAutoLockMinutes,
    ) { theme, locale, fenetre, delai ->
        SettingsUiState(theme, locale, fenetre, delai)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
        // ⚠️ La valeur initiale est LUE, pas supposée. Un état initial par défaut afficherait
        // « thème système » une fraction de seconde à quelqu'un qui a choisi le thème sombre, et
        // l'interrupteur de fenêtre protégée basculerait sous ses yeux.
        initialValue = SettingsUiState(
            theme = settings.themeNow(),
            locale = settings.localeNow(),
            secureWindow = settings.secureWindowNow(),
            vaultAutoLockMinutes = settings.vaultAutoLockMinutesNow(),
        ),
    )

    fun setTheme(value: ThemePreference) = settings.setTheme(value)

    fun setLocale(value: LocalePreference) = settings.setLocale(value)

    fun setSecureWindow(value: Boolean) = settings.setSecureWindow(value)

    fun setVaultAutoLockMinutes(value: Int) = settings.setVaultAutoLockMinutes(value)

    private companion object {
        const val ARRET_DIFFERE_MILLIS = 5_000L
    }
}
