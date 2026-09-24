package com.filestech.notes_tech.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.export.ExportResult
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.ui.common.userMessageFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * L'issue d'un export, telle que l'écran doit la présenter.
 *
 * ⚠️ [result] et [error] sont **consommés** par l'écran, jamais laissés en place : sans cela, une
 * rotation rejouerait le partage et l'utilisateur verrait une seconde fenêtre s'ouvrir sans l'avoir
 * demandée.
 */
data class ExportUiState(val busy: Boolean = false, val result: ExportResult? = null, @StringRes val error: Int? = null)

@HiltViewModel
class ExportViewModel @Inject constructor(private val exporter: NoteExporter) : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private var enCours: Job? = null

    /**
     * Fabrique l'archive de toutes les notes.
     *
     * ⚠️ [inboxLabel] et [vaultMention] viennent de l'écran, **pas** du contexte applicatif. La
     * langue choisie est posée sur le contexte de l'activité par `attachBaseContext` ; un
     * `applicationContext.getString` rendrait la langue du système et pas celle que l'utilisateur a
     * choisie. C'est le même défaut que le sélecteur de langue de la phase 5, sous une autre forme.
     *
     * ⚠️ [vaultMention] est une **fonction**, pas un gabarit pré-formaté. Passer `"%s"` puis
     * appeler `format` dessus casse en silence le jour où la chaîne traduite gagne un paramètre —
     * relevé par l'audit i18n du 2026-08-14.
     */
    fun exportAll(inboxLabel: String, vaultMention: (String) -> String) {
        if (enCours?.isActive == true) return
        _state.value = ExportUiState(busy = true)
        enCours = viewModelScope.launch {
            try {
                _state.value = ExportUiState(busy = false, result = exporter.exportAll(inboxLabel, vaultMention))
            } catch (e: CancellationException) {
                // L'écran est parti : il n'y a plus personne pour lire un message. Le fichier
                // partiel a déjà été effacé par l'exporteur.
                throw e
            } catch (e: Exception) {
                // ⚠️ The raw message was kept here until 2026-09-24, on the grounds that "the
                // published app does the same (`settings_screen.dart:598`)" and that "no space
                // left" helps the user. The first half stopped being true with notes_tech 2.0.9,
                // which shows `describeError` (`:618`); the second rested on an I/O message that is
                // internal text in the system's language, with a sandbox path in it. The sentence
                // now comes from `userMessageFor`, and the exception goes to the log.
                Timber.w(e, "export de toutes les notes")
                _state.value = ExportUiState(busy = false, error = userMessageFor(e))
            }
        }
    }

    /** À appeler une fois le partage lancé et le message affiché. */
    fun consume() {
        _state.value = _state.value.copy(result = null, error = null)
    }
}
