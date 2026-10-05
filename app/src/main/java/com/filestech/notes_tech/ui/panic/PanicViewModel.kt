package com.filestech.notes_tech.ui.panic

import androidx.lifecycle.ViewModel
import com.filestech.notes_tech.security.panic.PanicReport
import com.filestech.notes_tech.security.panic.PanicService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.filestech.notes_tech.di.ApplicationScope as PorteeApplicative

/**
 * Où en est la panique.
 *
 * ⚠️ [report] n'est **jamais** consommé, contrairement aux autres issues de l'application. L'écran
 * de fin est terminal : il n'y a plus rien derrière lui, et le faire disparaître d'un changement de
 * configuration renverrait l'utilisateur à des réglages vides sans lui dire ce qui a été détruit.
 */
data class PanicUiState(val running: Boolean = false, val report: PanicReport? = null)

@HiltViewModel
class PanicViewModel @Inject constructor(
    private val panic: PanicService,
    @PorteeApplicative private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(PanicUiState())
    val state: StateFlow<PanicUiState> = _state.asStateFlow()

    /**
     * Déclenche la séquence.
     *
     * ## ⚠️ L'attente se fait dans la portée du PROCESSUS, pas dans celle du modèle
     *
     * `PanicService.trigger` place déjà la séquence hors de portée d'une annulation. Mais l'attente
     * de son résultat, elle, doit survivre aussi : sur `viewModelScope`, une rotation d'écran
     * pendant les quelques secondes de la destruction annulerait la collecte, et l'écran de fin
     * n'arriverait jamais. L'utilisateur resterait sur « effacement en cours… » devant une
     * application dont les données ont bel et bien disparu.
     *
     * C'est la même leçon que la sauvegarde finale de l'éditeur : le geste survivait, son
     * observation non.
     */
    fun trigger() {
        if (_state.value.running || _state.value.report != null) return
        _state.value = PanicUiState(running = true)
        val sequence = panic.trigger()
        applicationScope.launch {
            val bilan = sequence.await()
            _state.value = PanicUiState(running = false, report = bilan)
        }
    }
}
