package com.filestech.notes_tech.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.VaultMode
import com.filestech.notes_tech.security.vault.FolderVaultService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Les dossiers du tiroir, avec l'état ouvert/fermé de ceux qui sont des coffres. */
data class FoldersUiState(
    val folders: List<Folder> = emptyList(),
    val unlockedFolderIds: Set<String> = emptySet(),
    val busy: Boolean = false,
) {
    val inbox: Folder? get() = folders.firstOrNull { it.isInbox }
    val userFolders: List<Folder> get() = folders.filterNot { it.isInbox }
}

/**
 * Ce que le tiroir doit dire à l'écran après une action.
 *
 * Un canal d'événements et non un champ d'état : un message affiché une fois ne doit pas
 * réapparaître à la rotation de l'écran. C'est le piège du porteur qu'on recopie dans un `remember`
 * — perdu à la rotation — ou dans un `rememberSaveable`, qui en fait un second dépositaire de la
 * même vérité.
 */
sealed interface FolderEvent {
    data class Renamed(val name: String) : FolderEvent
    data class Created(val folder: Folder) : FolderEvent

    /**
     * Un dossier a été supprimé, et [notes] notes ont subi [sort].
     *
     * ## 🔴🔴 Pourquoi le sort est PORTÉ et non déduit du nombre
     *
     * Cet événement ne portait qu'un `movedNotes: Int`, valant `0` pour une suppression **avec**
     * les notes. Deux gestes très différents — déplacer vers la boîte de réception, ou détruire —
     * se lisaient donc à travers le même entier, et un `0` était ambigu : suppression avec notes,
     * **ou** dossier vide dont on gardait les notes. Brancher le message sur `notes == 0` aurait
     * annoncé une destruction à quelqu'un qui venait de vider un dossier vide.
     *
     * Même leçon qu'au §96 : *un appelant ne peut pas oublier de regarder ce qu'il reçoit, alors
     * qu'il peut très bien mal interpréter un compteur.*
     */
    data class Deleted(val sort: SortDesNotes, val notes: Int) : FolderEvent
    data class VaultRemoved(val decrypted: Int) : FolderEvent
    data class VaultPartiallyRemoved(val failed: Int) : FolderEvent

    /**
     * ⚠️⚠️ **`null` autorisé, et c'est le point.** Ce champ était non-nullable, et l'émetteur y
     * mettait `e::class.java.simpleName` quand l'exception n'avait pas de message — c'est-à-dire
     * qu'il affichait « SQLiteConstraintException » à l'utilisateur. Un nom de classe n'est pas un
     * message, et un ViewModel n'a pas à décider du texte lu à l'écran.
     *
     * Effet de bord révélateur : l'écran portait bien un repli vers `common_error`, mais sur une
     * valeur qui ne pouvait pas être nulle — le compilateur signalait un elvis mort. *Un repli
     * inatteignable et une valeur de secours inventée ailleurs sont le même défaut vu des deux
     * bouts.*
     */
    data class Failed(val message: String?) : FolderEvent
}

/**
 * Ce qu'il est advenu des notes d'un dossier supprimé.
 *
 * ⚠️ `when` exhaustif chez le consommateur : un troisième sort ne pourra pas naître sans qu'on
 * décide de ce qu'il dit à l'utilisateur.
 */
enum class SortDesNotes {
    /** Déplacées vers la boîte de réception. */
    DEPLACEES,

    /** Détruites avec le dossier, définitivement. */
    SUPPRIMEES,
}

@HiltViewModel
class FoldersDrawerViewModel @Inject constructor(
    private val folders: FoldersRepository,
    private val notes: NotesRepository,
    private val vaults: FolderVaultService,
) : ViewModel() {

    private val events = MutableSharedFlow<FolderEvent>(extraBufferCapacity = 4)
    val eventFlow = events.asSharedFlow()

    val state: StateFlow<FoldersUiState> =
        combine(folders.observeAll(), vaults.unlockedFolderIds) { liste, ouverts ->
            FoldersUiState(folders = liste, unlockedFolderIds = ouverts)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = FoldersUiState(),
        )

    fun create(name: String) = enExecutant {
        events.emit(FolderEvent.Created(folders.create(name)))
    }

    fun rename(folderId: String, name: String) = enExecutant {
        folders.rename(folderId, name)
        events.emit(FolderEvent.Renamed(name))
    }

    fun lockNow(folderId: String) = vaults.lock(folderId)

    /**
     * Supprime un dossier en **conservant** ses notes, déplacées vers la boîte de réception.
     *
     * ## 🔴 Pour un coffre, ce geste DÉCHIFFRE tout
     *
     * Les notes d'un coffre sont scellées par une clé qui appartient au dossier. Les déplacer sans
     * les déchiffrer les rendrait illisibles pour toujours. Le déchiffrement passe donc par
     * `removeVaultProtection`, qui a la propriété qui compte : **un échec partiel n'efface rien et
     * rescelle ce qui avait déjà été ouvert.**
     *
     * L'ordre — déprotéger, puis supprimer — n'est pas une commodité : `deleteKeepingNotes` refuse
     * une source qui est encore un coffre, et cette garde vit dans le dépôt, pas ici. Un appelant
     * qui l'oublierait se ferait refuser, pas obéir.
     */
    fun deleteKeepingNotes(folder: Folder) = enExecutant {
        if (folder.isVault) {
            val bilan = vaults.removeVaultProtection(folder.id)
            if (!bilan.isComplete) {
                events.emit(FolderEvent.VaultPartiallyRemoved(bilan.failed))
                return@enExecutant
            }
        }
        val deplacees = folders.deleteKeepingNotes(folder.id, Folder.INBOX_ID)
        events.emit(FolderEvent.Deleted(SortDesNotes.DEPLACEES, deplacees))
    }

    /**
     * Supprime un dossier **et toutes ses notes**, définitivement, par cascade SQL.
     *
     * ⚠️ **La base d'abord, la clé Keystore ensuite.** L'ordre inverse a existé dans l'application
     * publiée : un échec de la suppression en base après celle de la clé laissait un coffre dont
     * les notes chiffrées n'avaient plus aucune clé. Perte définitive, sur une opération qui
     * n'était même pas censée échouer. Le détail est sur `FolderVaultService.deletePinKey`.
     */
    fun deleteWithNotes(folder: Folder) = enExecutant {
        // Libère la clé du coffre en mémoire avant la suppression, et évite qu'un futur dossier
        // réutilisant l'identifiant hérite d'une session fantôme.
        if (folder.isVault) vaults.lock(folder.id)
        // 🔴 **Compté AVANT la cascade**, sinon il n'y a plus rien à compter. Corbeille comprise :
        // voir `NoteDao.countAllInFolder`.
        val detruites = notes.countAllInFolder(folder.id)
        folders.delete(folder.id)
        if (folder.vault?.mode == VaultMode.PIN) vaults.deletePinKey(folder.id)
        events.emit(FolderEvent.Deleted(SortDesNotes.SUPPRIMEES, detruites))
    }

    /**
     * Retire la protection d'un coffre : déchiffre tout, puis efface son matériel.
     *
     * ⚠️ **Un échec partiel n'efface rien** et se signale comme tel. L'utilisateur doit savoir que
     * son dossier est resté un coffre, sans quoi il croira ses notes en clair et disponibles.
     */
    fun removeVaultProtection(folderId: String) = enExecutant {
        val bilan = vaults.removeVaultProtection(folderId)
        if (bilan.isComplete) {
            events.emit(FolderEvent.VaultRemoved(bilan.done))
        } else {
            events.emit(FolderEvent.VaultPartiallyRemoved(bilan.failed))
        }
    }

    /**
     * Enchaîne une action et le signalement de son échec.
     *
     * ⚠️ `CancellationException` **remonte** : elle dit que la coroutine s'arrête, pas que l'action
     * a raté. L'attraper afficherait « échec » à l'utilisateur au moment où il quitte l'écran.
     */
    private fun enExecutant(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ⚠️ Le message brut, tel quel — et `null` s'il n'y en a pas. C'est l'écran qui
                // choisit quoi dire dans ce cas ; voir [FolderEvent.Failed].
                events.emit(FolderEvent.Failed(e.message))
            }
        }
    }

    private companion object {
        const val ARRET_DIFFERE_MILLIS = 5_000L
    }
}
