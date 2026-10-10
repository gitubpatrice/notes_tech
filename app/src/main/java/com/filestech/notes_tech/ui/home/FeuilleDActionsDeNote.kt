package com.filestech.notes_tech.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.common.DialogueDestructif
import com.filestech.notes_tech.ui.common.EntreeDeMenu
import kotlinx.coroutines.launch

/**
 * The actions of a note, opened by a long press on its card (3.1.0).
 *
 * Built like the folders' sheet (`FolderActionSheet`): same entries, destructive one in the error
 * colour and LAST, so that it is never the one a thumb hits by reflex.
 *
 * ⚠️ **The header names the note the way the card does** ([titreAffiche]): a sheet opened by a long
 * press says nothing, to a screen reader, about which note it acts on — and for a vault note it says
 * "Locked note", never the title, like the card it came from.
 */
@Composable
internal fun FeuilleDActionsDeNote(
    note: Note,
    onDismiss: () -> Unit,
    onEditer: () -> Unit,
    onCorbeille: () -> Unit,
    onSupprimer: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(bottom = 12.dp).navigationBarsPadding()) {
            Text(
                text = titreAffiche(note),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { heading() },
            )
            // First, and the only one that keeps the note: it opens it straight on the writing side.
            EntreeDeMenu(
                icon = Icons.Outlined.Edit,
                title = stringResource(R.string.note_editor_mode_edit),
                onClick = onEditer,
            )
            // Red like the ⋮ menu's same entry: the same gesture has the same colour everywhere.
            EntreeDeMenu(
                icon = Icons.Outlined.DeleteOutline,
                destructive = true,
                title = stringResource(R.string.note_editor_menu_trash),
                onClick = onCorbeille,
            )
            EntreeDeMenu(
                icon = Icons.Outlined.DeleteForever,
                destructive = true,
                title = stringResource(R.string.trash_delete_forever),
                onClick = onSupprimer,
            )
        }
    }
}

/**
 * What the long press on a note has open, or waiting, on the home screen (3.1.0).
 *
 * Out of `HomeRoute`, which was already at the edge of what detekt lets a function hold: the sheet,
 * the confirmation of the irreversible choice, and the two things that wait for a vault's secret, as
 * `gesteEnAttente` does for folders —
 *
 * - [menuEnAttente]: a long press on a note of a CLOSED vault offers its actions only once the vault
 *   is open (courtesy; the protection is `HomeViewModel.executer`, at execution);
 * - [gesteEnAttente]: a gesture refused at execution because the vault closed in the meantime runs
 *   again once it is reopened — accepted, it must not be dropped in silence.
 * - [creationEnAttente]: "+" in a closed vault asks for its secret, then creates the note. Until 3.1.0
 *   the secret was asked and nothing followed: the user had to tap "+" a second time — an accepted
 *   gesture dropped in silence (seen on the emulator, 2026-10-10).
 *
 * Both are kept with the folder they wait for, and dropped with the unlock sheet if the user gives up.
 */
@Stable
internal class GestesDeNoteEnCours {
    var noteEnMenu by mutableStateOf<Note?>(null)
    var noteASupprimer by mutableStateOf<Note?>(null)
    private var menuEnAttente by mutableStateOf<Note?>(null)
    private var gesteEnAttente by mutableStateOf<HomeEvent.GesteEnAttenteDuCoffre?>(null)
    private var creationEnAttente by mutableStateOf<String?>(null)

    /**
     * The long press on [note]. Opens its sheet, or returns the closed vault whose secret must come
     * first — the caller opens the unlock sheet for it.
     */
    fun appuiLong(note: Note, dossiers: List<Folder>, coffresOuverts: Set<String>): Folder? {
        val dossier = dossiers.firstOrNull { it.id == note.folderId }
        val protegee = note.isLocked || dossier?.isVault == true
        if (dossier != null && protegee && dossier.id !in coffresOuverts) {
            menuEnAttente = note
            return dossier
        }
        noteEnMenu = note
        return null
    }

    fun attendreLeCoffre(evenement: HomeEvent.GesteEnAttenteDuCoffre) {
        gesteEnAttente = evenement
    }

    /** "+" was tapped in the closed vault [dossier]: the note is created once its secret is given. */
    fun attendreLaCreation(dossier: Folder) {
        creationEnAttente = dossier.id
    }

    /**
     * [dossier] was just unlocked: what waited for IT resumes, and only that is taken off — what waits
     * for another vault keeps waiting (GPT-5.6 review, 2026-10-10: clearing everything first dropped a
     * gesture waiting for vault A when the sheet of vault B closed). Compared by id: the automatic lock
     * opens an unlock sheet too.
     */
    fun reprendreApres(dossier: Folder, executer: (GesteSurUneNote) -> Unit, creer: () -> Unit) {
        val menu = menuEnAttente
        if (menu != null && menu.folderId == dossier.id) {
            menuEnAttente = null
            noteEnMenu = menu
        }
        val geste = gesteEnAttente
        if (geste != null && geste.folder.id == dossier.id) {
            gesteEnAttente = null
            executer(geste.geste)
        }
        if (creationEnAttente == dossier.id) {
            creationEnAttente = null
            creer()
        }
    }

    /**
     * The unlock sheet now asks for [dossierId]'s secret — or for none: what waited for ANOTHER vault
     * is given up. The sheet is the gesture's context; a confirmed "Delete permanently" must not run
     * later, at an unlock made for another reason, once that sheet was replaced by another vault's
     * without being dismissed (3-axes audit, 2026-10-10).
     */
    fun garderSeulementPour(dossierId: String?) {
        if (menuEnAttente?.folderId != dossierId) menuEnAttente = null
        if (gesteEnAttente?.folder?.id != dossierId) gesteEnAttente = null
        if (creationEnAttente != dossierId) creationEnAttente = null
    }

    /** Giving up the secret is giving up the gesture: what waited falls with the unlock sheet. */
    fun abandonner() {
        menuEnAttente = null
        gesteEnAttente = null
        creationEnAttente = null
    }
}

/** The sheet of [etat]'s note, then the confirmation of the irreversible choice. */
@Composable
internal fun FeuillesDesGestesDeNote(
    etat: GestesDeNoteEnCours,
    executer: (GesteSurUneNote) -> Unit,
    ecrire: (Note) -> Unit,
) {
    etat.noteEnMenu?.let { note ->
        FeuilleDActionsDeNote(
            note = note,
            onDismiss = { etat.noteEnMenu = null },
            // A note of a closed vault gets this sheet only once its vault is open (`appuiLong`), and
            // the editor asks for the secret again if it closed meanwhile: nothing to check here.
            onEditer = {
                etat.noteEnMenu = null
                ecrire(note)
            },
            onCorbeille = {
                etat.noteEnMenu = null
                executer(GesteSurUneNote.MettreALaCorbeille(note))
            },
            onSupprimer = {
                etat.noteEnMenu = null
                etat.noteASupprimer = note
            },
        )
    }

    // The trash's own question, word for word: erasing from the list skips the trash, and says so.
    etat.noteASupprimer?.let { note ->
        DialogueDestructif(
            titre = stringResource(R.string.trash_delete_forever_title),
            corps = stringResource(R.string.trash_delete_forever_body),
            libelleConfirmation = stringResource(R.string.trash_delete_forever),
            onConfirmer = {
                etat.noteASupprimer = null
                executer(GesteSurUneNote.SupprimerDefinitivement(note))
            },
            onAnnuler = { etat.noteASupprimer = null },
        )
    }
}

/**
 * Says what the long-press gestures did — the twin of `MessagesDeDossier`, collecting the same flow as
 * `HomeRoute`, which leaves these events to it.
 *
 * ⚠️ `portee.launch` around `showSnackbar`: it suspends until the message closes, and calling it in
 * the `collect` would hold back the events that follow. ⚠️ `Long`, not the default: with an action,
 * Material keeps the message until it is dismissed, and an "Undo" that never leaves covers the bottom
 * of the list.
 */
@Composable
internal fun MessagesDesGestesDeNote(viewModel: HomeViewModel, snackbars: SnackbarHostState) {
    val portee = rememberCoroutineScope()
    val ressources = LocalResources.current
    LaunchedEffect(viewModel, ressources) {
        viewModel.eventFlow.collect { evenement ->
            when (evenement) {
                is HomeEvent.MovedToTrash -> portee.launch {
                    val issue = snackbars.showSnackbar(
                        message = ressources.getString(R.string.note_moved_to_trash),
                        actionLabel = ressources.getString(R.string.common_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (issue == SnackbarResult.ActionPerformed) viewModel.restaurer(evenement.noteId)
                }

                HomeEvent.Restored -> portee.launch {
                    snackbars.showSnackbar(ressources.getString(R.string.trash_restored))
                }

                HomeEvent.DeletedForever ->
                    portee.launch { snackbars.showSnackbar(ressources.getString(R.string.trash_deleted_forever)) }

                is HomeEvent.ActionFailed -> portee.launch {
                    snackbars.showSnackbar(ressources.getString(evenement.message))
                }

                is HomeEvent.NoteCreated, is HomeEvent.VaultLocked, is HomeEvent.CreationFailed,
                is HomeEvent.GesteEnAttenteDuCoffre,
                -> Unit
            }
        }
    }
}
