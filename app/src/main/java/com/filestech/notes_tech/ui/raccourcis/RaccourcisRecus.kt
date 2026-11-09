package com.filestech.notes_tech.ui.raccourcis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Carries out a launcher shortcut once the app is unlocked (D-041, 3.4.0): a blank note in the Inbox —
 * never a vault —, opened to be written, with the microphone for [Raccourci.DICTEE].
 *
 * 🔴 Composed only inside the app lock's content, and not while the panic mode runs — the place of
 * `PartagesRecus`, for the same reasons: nothing is written while the app is locked, nothing on a
 * database the panic is destroying.
 */
@Composable
fun RaccourcisRecus(onOuvrir: (Destination.Editor) -> Unit) {
    val viewModel: RaccourcisViewModel = hiltViewModel()
    val demande by viewModel.demande.collectAsStateWithLifecycle()
    val ouvrir by rememberUpdatedState(onOuvrir)

    LaunchedEffect(viewModel) { viewModel.aOuvrir.collect { ouvrir(it) } }
    LaunchedEffect(demande) { if (demande != null) viewModel.executer() }
}

@HiltViewModel
class RaccourcisViewModel @Inject constructor(
    private val enAttente: RaccourcisEnAttente,
    private val notes: NotesRepository,
) : ViewModel() {

    val demande = enAttente.contenu

    // A channel, not a SharedFlow: an editor to open is not lost when nothing collects yet.
    private val ouvertures = Channel<Destination.Editor>(Channel.BUFFERED)
    val aOuvrir: Flow<Destination.Editor> = ouvertures.receiveAsFlow()

    fun executer() {
        // Taken here, synchronously: a second recomposition finds nothing left to carry out.
        val raccourci = enAttente.prendre() ?: return
        viewModelScope.launch {
            try {
                val note = notes.create(folderId = Folder.INBOX_ID)
                ouvertures.send(Destination.Editor(note.id, dicter = raccourci == Raccourci.DICTEE))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The app is open on its list: the user taps "New note" — no message for a shortcut.
                Timber.w(e, "raccourci non execute")
            }
        }
    }
}
