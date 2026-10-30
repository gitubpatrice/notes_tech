package com.filestech.notes_tech.ui.settings

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.importation.NoteImporter
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
 * The outcome of an import, as the screen shows it. [result] and [error] are **consumed** by the
 * screen, as the export's are: a rotation must not show the summary twice.
 */
data class ImportUiState(
    val busy: Boolean = false,
    val result: NoteImporter.Resultat? = null,
    @StringRes val error: Int? = null,
)

/** The Settings' "Import" (3.2.0, D-032). */
@HiltViewModel
class ImportViewModel @Inject constructor(private val importer: NoteImporter) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var enCours: Job? = null

    /** The documents chosen in the picker; nothing chosen, nothing done. */
    fun importer(documents: List<Uri>) {
        if (documents.isEmpty() || enCours?.isActive == true) return
        _state.value = ImportUiState(busy = true)
        enCours = viewModelScope.launch {
            try {
                _state.value = ImportUiState(busy = false, result = importer.importer(documents))
            } catch (e: CancellationException) {
                // The screen left: the transaction, if it had begun, is rolled back with it.
                throw e
            } catch (e: Exception) {
                Timber.w(e, "import de notes")
                _state.value = ImportUiState(busy = false, error = userMessageFor(e))
            }
        }
    }

    fun consume() {
        _state.value = _state.value.copy(result = null, error = null)
    }
}
