package com.filestech.notes_tech.ui.scellee

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.scellee.LectureScellee
import com.filestech.notes_tech.data.scellee.lireUneNoteScellee
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The home screen's "Open a sealed note" (D-044, 3.4.0): the file picked is read — as "Open with" reads one
 * ([lireUneNoteScellee]) — and deposited in [NotesScelleesEnAttente]; the code is asked by
 * [NotesScelleesRecues], the same as for "Open with". Apart from [NotesScelleesViewModel], which holds the
 * code dialog: one ViewModel per role (coherence audit of 3.4.0).
 */
@HiltViewModel
class SelecteurDeNoteScelleeViewModel @Inject constructor(
    private val enAttente: NotesScelleesEnAttente,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // A channel, not a SharedFlow: a refusal is not lost when nothing collects yet.
    private val refus = Channel<Int>(Channel.BUFFERED)

    /** A file picked that is no sealed note: said where it was picked. */
    val refusDuSelecteur: Flow<Int> = refus.receiveAsFlow()

    fun lire(uri: Uri) {
        viewModelScope.launch {
            when (val lue = lireUneNoteScellee(context.contentResolver, uri)) {
                is LectureScellee.Lue -> enAttente.deposer(lue.octets)
                LectureScellee.PasUneNoteScellee -> refus.send(R.string.sealed_not_a_sealed_note)
                LectureScellee.TropGrosse -> refus.send(R.string.sealed_too_big)
                LectureScellee.Illisible -> refus.send(R.string.sealed_error_unreadable)
            }
        }
    }
}
