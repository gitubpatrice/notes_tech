package com.filestech.notes_tech.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.HomeStartPreference
import com.filestech.notes_tech.data.prefs.LocalePreference
import com.filestech.notes_tech.data.prefs.ThemePreference
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.NoteSortMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * @param inboxStoredName the Inbox's name as stored (D-046): the home screen choice shows the name the
 *   drawer shows — the user may have renamed it. `null` until the folders are read. Raw on purpose, like
 *   `rememberFolderDisplayName`: a default name is translated where it is shown, in the app's language.
 */
data class SettingsUiState(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val locale: LocalePreference = LocalePreference.SYSTEM,
    val secureWindow: Boolean = true,
    val vaultAutoLockMinutes: Int = 15,
    val sort: NoteSortMode = NoteSortMode.DEFAULT,
    val keyboardNoLearning: Boolean = true,
    val homeStart: HomeStartPreference = HomeStartPreference.ALL_NOTES,
    val inboxStoredName: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: AppSettings, folders: FoldersRepository) :
    ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settings.theme,
        settings.locale,
        settings.secureWindow,
        settings.vaultAutoLockMinutes,
        settings.sort,
    ) { theme, locale, fenetre, delai, tri ->
        SettingsUiState(theme, locale, fenetre, delai, tri)
    }.combine(settings.keyboardNoLearning) { etat, sansApprentissage ->
        // A sixth flow: `combine` has typed overloads up to five.
        etat.copy(keyboardNoLearning = sansApprentissage)
    }.combine(settings.homeStart) { etat, accueil ->
        etat.copy(homeStart = accueil)
    }.combine(
        // ⚠️ Caught: this flow reads the database, and a failure there must not take the whole settings
        // screen down for a label. Without the name, the choice says "Inbox" in the app's language.
        // `onStart`: `combine` waits for a first value from every flow — the other settings must not
        // wait for the database to answer before they follow a change.
        folders.observeAll()
            .map<List<Folder>, String?> { tous -> tous.firstOrNull { it.isInbox }?.name }
            .catch { emit(null) }
            .onStart { emit(null) }
            .distinctUntilChanged(),
    ) { etat, nomDeLaBoite ->
        etat.copy(inboxStoredName = nomDeLaBoite)
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
            sort = settings.sortNow(),
            keyboardNoLearning = settings.keyboardNoLearningNow(),
            homeStart = settings.homeStartNow(),
        ),
    )

    fun setTheme(value: ThemePreference) = settings.setTheme(value)

    fun setLocale(value: LocalePreference) = settings.setLocale(value)

    fun setSecureWindow(value: Boolean) = settings.setSecureWindow(value)

    fun setKeyboardNoLearning(value: Boolean) = settings.setKeyboardNoLearning(value)

    fun setVaultAutoLockMinutes(value: Int) = settings.setVaultAutoLockMinutes(value)

    /** D-046: takes effect at the next start, never on a home screen already shown. */
    fun setHomeStart(value: HomeStartPreference) = settings.setHomeStart(value)

    /**
     * Le tri des notes.
     *
     * ⚠️ **Le même réglage que celui de la barre d'accueil**, pas un second. L'application publiée
     * ne le propose que dans les réglages ; le portage l'avait déplacé dans la barre. Il est
     * désormais aux deux endroits, et c'est un choix : `AppSettings.sort` est l'unique source, donc
     * les deux écrans se suivent l'un l'autre sans qu'aucun ne soit maître.
     */
    fun setSort(value: NoteSortMode) = settings.setSort(value)

    private companion object {
        const val ARRET_DIFFERE_MILLIS = 5_000L
    }
}
