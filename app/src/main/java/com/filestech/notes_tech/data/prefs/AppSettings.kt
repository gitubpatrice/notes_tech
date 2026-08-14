package com.filestech.notes_tech.data.prefs

import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.security.vault.VaultParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Le thème demandé par l'utilisateur. Miroir de `ThemeMode` côté Flutter. */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** La langue demandée. `SYSTEM` suit le réglage de l'appareil. */
enum class LocalePreference { SYSTEM, FRENCH, ENGLISH }

/**
 * Les réglages de l'application, dans le fichier de préférences hérité.
 *
 * Chaque valeur est lisible **tout de suite** (`…Now()`) et **observable** (`…Flow`). Les deux sont
 * nécessaires et ne font pas double emploi : le thème doit être connu avant la première
 * composition, sans quoi l'écran s'affiche en clair puis bascule en sombre — un défaut qu'on ne
 * voit qu'à l'œil, sur un appareil, en thème sombre.
 *
 * ## ⚠️ Les valeurs persistées sont des littéraux, jamais `Enum.name`
 *
 * `settings_service.dart:66` le dit pour la version Flutter, et la raison vaut identiquement ici :
 * le nom d'une constante d'énumération n'est pas un contrat de sérialisation. Le renommer est un
 * geste anodin qui réinitialiserait silencieusement le réglage de tous les utilisateurs — et pire,
 * R8 est libre de renommer ce que `name` restitue.
 *
 * Les correspondances passent donc par un `when` **exhaustif** et non par une table : ajouter un
 * mode de tri sans décider de sa clé persistée doit échouer **à la compilation**. Avec une table,
 * l'oubli n'aurait explosé qu'à l'exécution, chez l'utilisateur, au premier changement de tri.
 */
@Singleton
class AppSettings @Inject constructor(private val prefs: LegacyPreferences) {

    // ── Thème ────────────────────────────────────────────────────────────────────────────────────

    fun themeNow(): ThemePreference = when (prefs.string(KEY_THEME)) {
        "light" -> ThemePreference.LIGHT
        "dark" -> ThemePreference.DARK
        else -> ThemePreference.SYSTEM
    }

    val theme: Flow<ThemePreference> = observing { themeNow() }

    fun setTheme(value: ThemePreference) = prefs.putString(
        KEY_THEME,
        when (value) {
            ThemePreference.LIGHT -> "light"
            ThemePreference.DARK -> "dark"
            ThemePreference.SYSTEM -> "system"
        },
    )

    // ── Langue ───────────────────────────────────────────────────────────────────────────────────

    fun localeNow(): LocalePreference = when (prefs.string(KEY_LOCALE)) {
        "fr" -> LocalePreference.FRENCH
        "en" -> LocalePreference.ENGLISH
        else -> LocalePreference.SYSTEM
    }

    val locale: Flow<LocalePreference> = observing { localeNow() }

    fun setLocale(value: LocalePreference) = prefs.putString(
        KEY_LOCALE,
        when (value) {
            LocalePreference.FRENCH -> "fr"
            LocalePreference.ENGLISH -> "en"
            LocalePreference.SYSTEM -> "system"
        },
    )

    // ── Tri des notes ────────────────────────────────────────────────────────────────────────────

    fun sortNow(): NoteSortMode {
        val brut = prefs.string(KEY_SORT)
        return NoteSortMode.entries.firstOrNull { persistedKey(it) == brut } ?: NoteSortMode.DEFAULT
    }

    val sort: Flow<NoteSortMode> = observing { sortNow() }

    fun setSort(value: NoteSortMode) = prefs.putString(KEY_SORT, persistedKey(value))

    // ── Fenêtre protégée ─────────────────────────────────────────────────────────────────────────

    /**
     * `FLAG_SECURE` : interdit la capture d'écran et masque l'aperçu dans les applications
     * récentes. **Activé par défaut**, comme dans la version publiée.
     *
     * ⚠️ Le défaut compte autant que la valeur : un utilisateur qui n'a jamais ouvert les réglages
     * doit être protégé. Un défaut à `false` transformerait un oubli de configuration en fuite.
     */
    fun secureWindowNow(): Boolean = prefs.boolean(KEY_SECURE_WINDOW, default = true)

    val secureWindow: Flow<Boolean> = observing { secureWindowNow() }

    fun setSecureWindow(value: Boolean) = prefs.putBoolean(KEY_SECURE_WINDOW, value)

    // ── Auto-verrouillage des coffres ────────────────────────────────────────────────────────────

    /** En minutes. `0` = jamais, jusqu'à un verrouillage manuel ou le passage en arrière-plan. */
    fun vaultAutoLockMinutesNow(): Int = prefs.int(KEY_VAULT_AUTO_LOCK, default = VaultParams.DEFAULT_AUTO_LOCK_MINUTES)

    val vaultAutoLockMinutes: Flow<Int> = observing { vaultAutoLockMinutesNow() }

    fun setVaultAutoLockMinutes(value: Int) = prefs.putInt(KEY_VAULT_AUTO_LOCK, value)

    // ── Écran de présentation ────────────────────────────────────────────────────────────────────

    /**
     * `true` tant que l'écran de présentation n'a jamais été vu.
     *
     * Effacer les données depuis les réglages Android fait disparaître la clé, donc rejoue l'écran
     * — sémantique voulue, alignée sur les autres applications Files Tech.
     */
    fun shouldShowSplash(): Boolean = !prefs.boolean(KEY_SPLASH_SHOWN, default = false)

    fun markSplashShown() = prefs.putBoolean(KEY_SPLASH_SHOWN, true)

    // ── Brouillons perdus dans un coffre ─────────────────────────────────────────────────────────

    /**
     * Les notes dont la dernière modification n'a pas pu être enregistrée parce que le coffre s'est
     * verrouillé pendant la sauvegarde.
     *
     * C'est une **perte de données silencieuse** si personne ne la signale : l'utilisateur a tapé,
     * l'écran s'est comporté normalement, et le texte n'existe nulle part. D'où la bannière.
     */
    fun vaultLostDrafts(): List<String> = prefs.stringList(KEY_VAULT_LOST_DRAFTS)

    /**
     * Le nombre de modifications perdues, **observable**.
     *
     * 🔴 Sans ce flux, la bannière n'apparaît jamais dans la session où la perte se produit.
     * L'éditeur écrit dans les préférences, l'accueil lit un état local figé à sa création, et le
     * seul signalement d'une perte silencieuse reste... silencieux. Le défaut annulait exactement
     * ce que la bannière existe pour empêcher. Relevé par une relecture externe (GPT-5.2).
     */
    val vaultLostDraftsCount: Flow<Int> = observing { vaultLostDrafts().size }

    fun addVaultLostDraft(noteId: String) {
        val actuels = vaultLostDrafts()
        if (noteId in actuels) return
        prefs.putStringList(KEY_VAULT_LOST_DRAFTS, actuels + noteId)
    }

    fun clearVaultLostDrafts() = prefs.remove(KEY_VAULT_LOST_DRAFTS)

    /**
     * Relit [lecture] à chaque écriture dans le fichier, et n'émet que si le résultat a changé.
     *
     * `distinctUntilChanged` n'est pas une optimisation : sans lui, changer le thème ferait émettre
     * le flux du tri, celui de la langue et tous les autres — et chaque écran abonné se
     * recomposerait pour une valeur identique.
     */
    private fun <T> observing(lecture: () -> T): Flow<T> = prefs.changes().map { lecture() }.distinctUntilChanged()

    private fun persistedKey(mode: NoteSortMode): String = when (mode) {
        NoteSortMode.UPDATED_DESC -> "updatedDesc"
        NoteSortMode.UPDATED_ASC -> "updatedAsc"
        NoteSortMode.CREATED_DESC -> "createdDesc"
        NoteSortMode.CREATED_ASC -> "createdAsc"
        NoteSortMode.TITLE_ASC -> "titleAsc"
        NoteSortMode.TITLE_DESC -> "titleDesc"
    }

    private companion object {
        // Les clés Dart nues — `core/constants.dart:139-160`. Le préfixe `flutter.` est ajouté par
        // [LegacyPreferences], une fois, au seul endroit qui connaît le format du fichier.
        const val KEY_THEME = "theme_mode"
        const val KEY_LOCALE = "app_locale"
        const val KEY_SORT = "note_sort_mode"
        const val KEY_SECURE_WINDOW = "secure_window_enabled"
        const val KEY_VAULT_AUTO_LOCK = "vault_auto_lock_minutes"
        const val KEY_VAULT_LOST_DRAFTS = "vault_lost_drafts"

        /** `services/first_launch_flag.dart:16`. */
        const val KEY_SPLASH_SHOWN = "splash_shown_v1"
    }
}
