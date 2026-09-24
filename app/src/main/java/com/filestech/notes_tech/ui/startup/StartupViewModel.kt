package com.filestech.notes_tech.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.security.kek.KekFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Ce que l'application peut être au démarrage. */
sealed interface StartupState {

    data object Opening : StartupState

    data object Ready : StartupState

    /**
     * L'ouverture a échoué. **Rien n'a été détruit** — c'est la promesse que porte l'écran affiché
     * à l'utilisateur, et elle est tenue par [com.filestech.notes_tech.security.kek.KekRepository].
     */
    data class Failed(val reason: FailureReason) : StartupState
}

/**
 * Pourquoi l'ouverture a échoué, du point de vue de **ce que l'utilisateur peut faire**.
 *
 * Le découpage n'est pas technique mais actionnable : deux causes qui appellent le même geste
 * n'ont aucune raison d'être deux écrans, et deux causes qui appellent des gestes différents ne
 * doivent surtout pas être confondues.
 */
enum class FailureReason {
    /**
     * Aucune clé, mais une base présente. Gesture: none that the user can make alone — keep the app
     * and its data, write to support. ("Install 2.0.4 first" stopped being possible with 2.0.5.)
     */
    MISSING_KEY,

    /** Le Keystore n'a pas répondu. Geste : réessayer, puis redémarrer l'appareil. */
    KEY_UNAVAILABLE,

    /** Autre chose. Geste : réessayer, et remonter le problème. */
    UNKNOWN,
}

@HiltViewModel
class StartupViewModel @Inject constructor(private val databaseProvider: DatabaseProvider) : ViewModel() {

    private val _state = MutableStateFlow<StartupState>(StartupState.Opening)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    init {
        open()
    }

    /**
     * Relance l'ouverture.
     *
     * Exposé publiquement parce qu'une des causes d'échec est **transitoire** : un Keystore
     * indisponible pendant un démarrage à froid redevient disponible quelques secondes plus tard.
     * Sans ce geste, l'utilisateur n'aurait d'autre issue que de tuer l'application — et une
     * vérification faite une seule fois, qui ne se retente jamais, est le motif de défaut qui
     * revient le plus souvent dans ce portefeuille.
     */
    fun retry() {
        if (_state.value is StartupState.Opening) return
        open()
    }

    private fun open() {
        _state.value = StartupState.Opening
        viewModelScope.launch {
            try {
                databaseProvider.get()
                _state.value = StartupState.Ready
            } catch (e: KekFailure) {
                // Pas de `runCatching` ici, et pas de `catch (e: Exception)` : le premier avale
                // `CancellationException` et ferait continuer une coroutine annulée ; le second
                // traiterait une erreur de programmation comme un échec métier affichable.
                _state.value = StartupState.Failed(e.toReason())
            }
        }
    }

    private fun KekFailure.toReason(): FailureReason {
        // Le message est journalisé, jamais affiché : il décrit une cause technique et ne dit pas
        // à l'utilisateur quoi faire. L'écran, lui, part du geste attendu.
        Timber.e(this, "ouverture de la base impossible — la base n'est PAS touchée")
        return when (this) {
            is KekFailure.NoKeyForExistingDatabase -> FailureReason.MISSING_KEY
            is KekFailure.SourceUnavailable -> FailureReason.KEY_UNAVAILABLE
            is KekFailure.MalformedKey -> FailureReason.UNKNOWN
        }
    }
}
