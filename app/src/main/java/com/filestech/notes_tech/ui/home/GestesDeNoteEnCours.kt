package com.filestech.notes_tech.ui.home

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.estProtegee
import com.filestech.notes_tech.ui.common.DialogueDeSortieDeCoffre
import com.filestech.notes_tech.ui.common.DialogueDestructif
import com.filestech.notes_tech.ui.common.selonLeNombre
import com.filestech.notes_tech.ui.editor.FeuilleDeCouleur
import com.filestech.notes_tech.ui.editor.FeuilleDeDeplacement
import kotlinx.coroutines.launch

/**
 * What the selection has open, or waiting, on the home screen (3.3.0; 3.1.0 had one note's sheet).
 *
 * Out of `HomeRoute`, which was already at the edge of what detekt lets a function hold:
 *
 * - the three things the selection opens before it acts — the move's sheet ([deplacement]), the
 *   colour's ([couleur]), the confirmation of the irreversible choice ([suppression]) — and the exit
 *   question the view model asks for ([sortie]). Kept by id, and SAVED: a rotation does not lose a
 *   question the user is reading (`TrashScreen`'s rule, 2026-08-17);
 * - the three things that wait for a vault's secret, as `gesteEnAttente` does for folders:
 *   - [selectionEnAttente]: a note of a CLOSED vault is selected only once the vault is open
 *     (courtesy; the protection is `HomeViewModel.executer`, at execution);
 *   - [gesteEnAttente]: a gesture refused at execution because a vault is closed — a selected note's,
 *     or the move's destination — runs again once it is open: accepted, it must not be dropped in
 *     silence. Run again, it is checked again: a second closed vault asks for its own secret then;
 *   - [creationEnAttente]: "+" in a closed vault asks for its secret, then creates the note (3.1.0).
 *
 * The waiting ones are kept with the folder they wait for, and dropped with the unlock sheet if the user
 * gives up. They are not saved: they hold the vault the unlock sheet is for, which is not saved either.
 */
@Stable
internal class GestesDeNoteEnCours(
    suppression: List<String>? = null,
    deplacement: List<String>? = null,
    couleur: List<String>? = null,
    sortie: GesteSurDesNotes.Deplacer? = null,
    sortieNombre: Int = 0,
) {
    /** The notes whose permanent deletion waits for its confirmation. */
    var suppression by mutableStateOf(suppression)

    /** The notes the move's sheet is open for. */
    var deplacement by mutableStateOf(deplacement)

    /** The notes the colour's sheet is open for. */
    var couleur by mutableStateOf(couleur)

    /** The move waiting for the exit question, and how many sealed notes it takes out of their vault. */
    var sortie by mutableStateOf(sortie)
        private set
    var sortieNombre by mutableStateOf(sortieNombre)
        private set

    private var selectionEnAttente by mutableStateOf<Note?>(null)
    private var gesteEnAttente by mutableStateOf<HomeEvent.GesteEnAttenteDuCoffre?>(null)
    private var creationEnAttente by mutableStateOf<String?>(null)

    /**
     * A long press on [note], or a tap while selecting: selects it — or deselects it, which never asks
     * for anything — or returns the closed vault whose secret must come first: the caller opens the
     * unlock sheet for it, and [reprendreApres] selects the note once it is open.
     */
    fun basculer(
        note: Note,
        selectionnee: Boolean,
        dossiers: List<Folder>,
        coffresOuverts: Set<String>,
        basculerLaSelection: (String) -> Unit,
    ): Folder? {
        if (!selectionnee) {
            val dossier = dossiers.firstOrNull { it.id == note.folderId }
            if (note.estProtegee(dossier) && note.folderId !in coffresOuverts) {
                // Folders not loaded yet: no sheet can ask for the secret, so the note is not selected.
                if (dossier != null) selectionEnAttente = note
                return dossier
            }
        }
        basculerLaSelection(note.id)
        return null
    }

    fun attendreLeCoffre(evenement: HomeEvent.GesteEnAttenteDuCoffre) {
        gesteEnAttente = evenement
    }

    fun demanderLaSortie(evenement: HomeEvent.SortieDeCoffreAConfirmer) {
        sortie = evenement.geste
        sortieNombre = evenement.notesScellees
    }

    fun oublierLaSortie() {
        sortie = null
        sortieNombre = 0
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
    fun reprendreApres(
        dossier: Folder,
        selectionner: (String) -> Unit,
        executer: (GesteSurDesNotes) -> Unit,
        creer: () -> Unit,
    ) {
        val aSelectionner = selectionEnAttente
        if (aSelectionner != null && aSelectionner.folderId == dossier.id) {
            selectionEnAttente = null
            selectionner(aSelectionner.id)
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
        if (selectionEnAttente?.folderId != dossierId) selectionEnAttente = null
        if (gesteEnAttente?.folder?.id != dossierId) gesteEnAttente = null
        if (creationEnAttente != dossierId) creationEnAttente = null
    }

    /** Giving up the secret is giving up the gesture: what waited falls with the unlock sheet. */
    fun abandonner() {
        selectionEnAttente = null
        gesteEnAttente = null
        creationEnAttente = null
    }

    companion object {
        /** What a rotation, or the process dying, must not lose: the questions open, by id. */
        val Sauvegarde: Saver<GestesDeNoteEnCours, Any> = listSaver(
            save = { etat ->
                // A lambda, not `::ArrayList`: that reference resolves to the Java constructor taking a
                // `Collection<? extends E>`, which Kotlin 2.4 flags as a Java type mismatch.
                listOf(
                    etat.suppression?.let { ArrayList(it) },
                    etat.deplacement?.let { ArrayList(it) },
                    etat.couleur?.let { ArrayList(it) },
                    etat.sortie?.ids?.let { ArrayList(it) },
                    etat.sortie?.dossierId,
                    etat.sortieNombre,
                )
            },
            restore = { valeurs ->
                @Suppress("UNCHECKED_CAST")
                fun liste(i: Int) = valeurs[i] as List<String>?
                val sortieIds = liste(INDEX_SORTIE_IDS)
                val sortieDossier = valeurs[INDEX_SORTIE_DOSSIER] as String?
                GestesDeNoteEnCours(
                    suppression = liste(0),
                    deplacement = liste(1),
                    couleur = liste(2),
                    sortie = if (sortieIds != null && sortieDossier != null) {
                        GesteSurDesNotes.Deplacer(sortieIds, sortieDossier)
                    } else {
                        null
                    },
                    sortieNombre = valeurs[INDEX_SORTIE_NOMBRE] as Int,
                )
            },
        )

        private const val INDEX_SORTIE_IDS = 3
        private const val INDEX_SORTIE_DOSSIER = 4
        private const val INDEX_SORTIE_NOMBRE = 5
    }
}

/**
 * The sheets and questions of the selection: move, colour, the confirmation of the irreversible choice,
 * and the exit question. Each one runs its gesture on the notes it was opened for — by id: whether they
 * may be touched is the view model's to decide, at execution.
 */
@Composable
internal fun FeuillesDesGestesDeNote(
    etat: GestesDeNoteEnCours,
    notes: List<Note>,
    dossiers: List<Folder>,
    coffresOuverts: Set<String>,
    executer: (GesteSurDesNotes) -> Unit,
) {
    // A sheet acts on the notes it was opened for that the list still shows — what the user sees
    // selected, nothing else (external review, 2026-11-09).
    val presentes = notes.mapTo(HashSet()) { it.id }
    val parId = dossiers.associateBy { it.id }
    etat.deplacement?.let { ids ->
        // The folder all of them are in is no destination; several folders: every folder is one.
        val dossierCommun = notes.filter { it.id in ids }.map { it.folderId }.distinct().singleOrNull()
        FeuilleDeDeplacement(
            dossiers = dossiers,
            dossierActuel = dossierCommun,
            onChoisir = { cible ->
                etat.deplacement = null
                executer(GesteSurDesNotes.Deplacer(ids.filter { it in presentes }, cible))
            },
            onDismiss = { etat.deplacement = null },
        )
    }

    etat.couleur?.let { ids ->
        val choisies = notes.filter { it.id in ids }
        val couleurs = choisies.map { it.color }.distinct()
        FeuilleDeCouleur(
            // Several colours among them: no swatch is ticked — ticking "no colour" would say otherwise.
            actuelle = couleurs.singleOrNull(),
            melangees = couleurs.size > 1,
            dansUnCoffre = choisies.any { it.estProtegee(parId[it.folderId]) },
            onChoisir = { couleur ->
                etat.couleur = null
                executer(GesteSurDesNotes.Colorer(ids.filter { it in presentes }, couleur))
            },
            onDismiss = { etat.couleur = null },
        )
    }

    // The trash's own question for one note, word for word; its plural for several.
    // The number said is the number erased: the notes still shown, counted once for both (second
    // external review). None shown yet — a question restored before the list loads — shows nothing.
    etat.suppression?.filter { it in presentes }?.takeIf { it.isNotEmpty() }?.let { ids ->
        DialogueDestructif(
            titre = selonLeNombre(ids.size, R.string.trash_delete_forever_title, R.plurals.notes_delete_forever_title),
            corps = selonLeNombre(ids.size, R.string.trash_delete_forever_body, R.plurals.notes_delete_forever_body),
            libelleConfirmation = stringResource(R.string.trash_delete_forever),
            onConfirmer = {
                etat.suppression = null
                executer(GesteSurDesNotes.SupprimerDefinitivement(ids))
            },
            onAnnuler = { etat.suppression = null },
        )
    }

    etat.sortie?.let { geste ->
        DialogueDeSortieDeCoffre(
            nombre = etat.sortieNombre,
            onConfirmer = {
                etat.oublierLaSortie()
                // As the sheets: the notes still shown, nothing else (second external review).
                val visibles = geste.ids.filter { it in presentes }
                if (visibles.isNotEmpty()) executer(geste.copy(ids = visibles, sortieDeCoffreConfirmee = true))
            },
            onAnnuler = etat::oublierLaSortie,
        )
    }

    // A vault that closes under an open question takes the question with it, as the editor's do: the
    // answer would be checked again anyway, but a question about notes now hidden is no question.
    // Keyed on the notes too: a question restored after the process died is decided once they load
    // (3-axes audit, 2026-11-09) — and computed here only, not at every recomposition.
    LaunchedEffect(coffresOuverts, notes, dossiers) {
        val protegees = notes.filter { it.estProtegee(parId[it.folderId]) }
        val ferme = { ids: List<String>? ->
            ids != null && protegees.any { it.id in ids && it.folderId !in coffresOuverts }
        }
        if (ferme(etat.suppression)) etat.suppression = null
        if (ferme(etat.couleur)) etat.couleur = null
        if (ferme(etat.deplacement)) etat.deplacement = null
        if (ferme(etat.sortie?.ids)) etat.oublierLaSortie()
    }
}

/**
 * Says what the selection's gestures did — the twin of `MessagesDeDossier`, collecting the same flow as
 * `HomeRoute`, which leaves these events to it. One note is said as it was before 3.3.0, several by
 * their number ([selonLeNombre]).
 *
 * ⚠️ `portee.launch` around `showSnackbar`: it suspends until the message closes, and calling it in
 * the `collect` would hold back the events that follow. ⚠️ `Long` with an "Undo", not the default:
 * with an action, Material keeps the message until it is dismissed, and an "Undo" that never leaves
 * covers the bottom of the list.
 */
@Composable
internal fun MessagesDesGestesDeNote(viewModel: HomeViewModel, snackbars: SnackbarHostState) {
    val portee = rememberCoroutineScope()
    val ressources = LocalResources.current
    LaunchedEffect(viewModel, ressources) {
        fun dire(message: String, annuler: (() -> Unit)? = null) {
            portee.launch {
                val issue = snackbars.showSnackbar(
                    message = message,
                    actionLabel = annuler?.let { ressources.getString(R.string.common_undo) },
                    duration = if (annuler != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (issue == SnackbarResult.ActionPerformed) annuler?.invoke()
            }
        }
        viewModel.eventFlow.collect { evenement ->
            when (evenement) {
                is HomeEvent.Archivees -> {
                    val n = evenement.ids.size
                    val message = if (evenement.archivees) {
                        ressources.selonLeNombre(n, R.string.note_editor_archived, R.plurals.notes_archived)
                    } else {
                        ressources.selonLeNombre(n, R.string.note_editor_unarchived, R.plurals.notes_unarchived)
                    }
                    val annuler = { viewModel.annulerLArchivage(evenement.ids, evenement.archivees) }
                    dire(message, annuler.takeIf { evenement.annulable })
                }

                is HomeEvent.MisesALaCorbeille -> dire(
                    ressources.selonLeNombre(
                        evenement.ids.size,
                        R.string.note_moved_to_trash,
                        R.plurals.notes_moved_to_trash,
                    ),
                ) { viewModel.restaurer(evenement.ids) }

                is HomeEvent.Restaurees ->
                    dire(ressources.selonLeNombre(evenement.nombre, R.string.trash_restored, R.plurals.notes_restored))

                is HomeEvent.Deplacees ->
                    dire(ressources.selonLeNombre(evenement.nombre, R.string.note_editor_moved, R.plurals.notes_moved))

                // The trash's own plural, "N notes permanently deleted": the same words for the same act.
                is HomeEvent.Supprimees ->
                    dire(
                        ressources.selonLeNombre(
                            evenement.nombre,
                            R.string.trash_deleted_forever,
                            R.plurals.trash_emptied,
                        ),
                    )

                is HomeEvent.ActionFailed -> dire(ressources.getString(evenement.message))

                is HomeEvent.NoteCreated, is HomeEvent.VaultLocked, is HomeEvent.CreationFailed,
                is HomeEvent.GesteEnAttenteDuCoffre, is HomeEvent.SortieDeCoffreAConfirmer,
                -> Unit
            }
        }
    }
}
