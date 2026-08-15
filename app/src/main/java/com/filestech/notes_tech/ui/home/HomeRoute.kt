package com.filestech.notes_tech.ui.home

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.VaultMode
import com.filestech.notes_tech.ui.folders.ConfirmDeleteFolderDialog
import com.filestech.notes_tech.ui.folders.ConfirmRemoveVaultProtectionDialog
import com.filestech.notes_tech.ui.folders.FolderAction
import com.filestech.notes_tech.ui.folders.FolderActionSheet
import com.filestech.notes_tech.ui.folders.FolderDeletionChoice
import com.filestech.notes_tech.ui.folders.FolderEvent
import com.filestech.notes_tech.ui.folders.FolderNameDialog
import com.filestech.notes_tech.ui.folders.FoldersDrawer
import com.filestech.notes_tech.ui.folders.FoldersDrawerViewModel
import com.filestech.notes_tech.ui.vault.ChooseVaultModeSheet
import com.filestech.notes_tech.ui.vault.CreateVaultSheet
import com.filestech.notes_tech.ui.vault.UnlockVaultSheet
import kotlinx.coroutines.launch

/**
 * L'écran d'accueil branché : ViewModels, tiroir, feuilles de dossier et de coffre.
 *
 * Séparé de [HomeScreen], qui reste sans état. Toute la colle est ici, et rien de ce qui s'affiche
 * n'en dépend — c'est ce qui rend l'écran testable sans base ni appareil.
 */
@Composable
fun HomeRoute(
    onOpenNote: (Note) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val homeViewModel: HomeViewModel = hiltViewModel()
    val foldersViewModel: FoldersDrawerViewModel = hiltViewModel()
    val state by homeViewModel.state.collectAsStateWithLifecycle()
    val foldersState by foldersViewModel.state.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbars = remember { SnackbarHostState() }
    val portee = rememberCoroutineScope()
    val messageNoteEnBoiteDeReception = stringResource(R.string.home_note_created_in_inbox)
    val ressourcesDeLEcran = LocalResources.current

    var dossierEnMenu by remember { mutableStateOf<Folder?>(null) }
    var dossierARenommer by remember { mutableStateOf<Folder?>(null) }
    var dossierASupprimer by remember { mutableStateOf<Folder?>(null) }
    var dossierADeproteger by remember { mutableStateOf<Folder?>(null) }

    /**
     * Le dossier dont la déprotection a été **confirmée** et n'attend plus que le secret.
     *
     * ⚠️ Sans ce report, l'utilisateur confirmait le geste le plus destructeur de l'application,
     * saisissait sa phrase secrète… et il ne se passait rien. Un geste accepté puis abandonné en
     * silence, ce que ce dépôt refuse partout ailleurs.
     */
    var deprotectionEnAttente by remember { mutableStateOf<String?>(null) }
    var creationDeDossier by remember { mutableStateOf(false) }
    var dossierAOuvrir by remember { mutableStateOf<Folder?>(null) }
    var dossierAProteger by remember { mutableStateOf<Folder?>(null) }
    var modeChoisi by remember { mutableStateOf<VaultMode?>(null) }

    // La purge de la corbeille est un rattrapage d'arrière-plan, lancé une fois par entrée sur
    // l'écran. `Unit` en clé : la relancer à chaque recomposition ferait un balayage par frappe.
    LaunchedEffect(Unit) { homeViewModel.purgeExpiredTrash() }

    MessagesDeDossier(foldersViewModel, snackbars)

    // ⚠️ La creation de note ouvre l'editeur, ou demande le secret du coffre. Les deux issues
    // partent du meme evenement : c'est le ViewModel qui sait laquelle, parce que lui seul sait si
    // l'ecriture a ete refusee par la transaction.
    LaunchedEffect(homeViewModel) {
        homeViewModel.eventFlow.collect { evenement ->
            when (evenement) {
                is HomeEvent.NoteCreated -> {
                    onOpenNote(evenement.note)
                    // ⚠️ `showSnackbar` SUSPEND jusqu'a la fermeture du message. L'appeler dans le
                    // `collect` bloquerait la collecte plusieurs secondes ; les evenements suivants
                    // s'empileraient dans un tampon de 4, apres quoi `emit` bloquerait le ViewModel.
                    // Releve par une relecture externe (Gemini, 2026-08-14).
                    if (evenement.inInbox) portee.launch { snackbars.showSnackbar(messageNoteEnBoiteDeReception) }
                }

                is HomeEvent.VaultLocked -> dossierAOuvrir = evenement.folder
                // ⚠️ La chaine est formatee ICI, avec son argument reel, et pas par un gabarit
                // « %s » construit a l'avance : ce dernier casserait en silence le jour ou la
                // chaine gagnerait un second placeholder. Releve par l'audit i18n du 2026-08-14.
                is HomeEvent.CreationFailed -> portee.launch {
                    snackbars.showSnackbar(
                        ressourcesDeLEcran.getString(R.string.home_vault_create_error, evenement.message),
                    )
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            FoldersDrawer(
                state = foldersState,
                currentFolderId = state.currentFolder?.id,
                onSelect = { id ->
                    homeViewModel.onFolderSelected(id)
                    portee.launch { drawerState.close() }
                },
                onOpenTrash = {
                    portee.launch { drawerState.close() }
                    onOpenTrash()
                },
                onCreateFolder = { creationDeDossier = true },
                onFolderMenu = { dossierEnMenu = it },
            )
        },
    ) {
        HomeScreen(
            state = state,
            onQueryChange = homeViewModel::onQueryChange,
            onSortSelected = homeViewModel::onSortSelected,
            onOpenNote = onOpenNote,
            onNewNote = homeViewModel::createNote,
            onOpenDrawer = { portee.launch { drawerState.open() } },
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            onDismissVaultLostBanner = homeViewModel::dismissVaultLostBanner,
        )
        SnackbarHost(hostState = snackbars)
    }

    dossierEnMenu?.let { dossier ->
        FolderActionSheet(
            folder = dossier,
            unlocked = dossier.id in foldersState.unlockedFolderIds,
            onDismiss = { dossierEnMenu = null },
            onAction = { action ->
                dossierEnMenu = null
                when (action) {
                    FolderAction.RENAME -> dossierARenommer = dossier
                    FolderAction.CONVERT_TO_VAULT -> dossierAProteger = dossier
                    FolderAction.LOCK_NOW -> foldersViewModel.lockNow(dossier.id)
                    // 🔴 **On demande AVANT de regarder la session, pas après.**
                    //
                    // La question posée est « voulez-vous déchiffrer tout ce dossier ? » ; elle ne
                    // dépend pas de l'état de la session. Vérifier d'abord et confirmer ensuite
                    // ferait apparaître une demande de secret pour un geste que l'utilisateur n'a
                    // pas encore accepté — et, sur un coffre déjà ouvert, ne demanderait rien du
                    // tout, ce qui était le défaut.
                    FolderAction.REMOVE_VAULT_PROTECTION -> dossierADeproteger = dossier

                    FolderAction.DELETE -> dossierASupprimer = dossier
                }
            },
        )
    }

    dossierARenommer?.let { dossier ->
        FolderNameDialog(
            title = stringResource(R.string.folder_rename_title),
            fieldLabel = stringResource(R.string.folder_rename_field),
            initial = dossier.name,
            onDismiss = { dossierARenommer = null },
            onConfirm = { nom ->
                dossierARenommer = null
                foldersViewModel.rename(dossier.id, nom)
            },
        )
    }

    if (creationDeDossier) {
        FolderNameDialog(
            title = stringResource(R.string.folder_create_title),
            fieldLabel = stringResource(R.string.folder_create_field),
            initial = "",
            onDismiss = { creationDeDossier = false },
            onConfirm = { nom ->
                creationDeDossier = false
                foldersViewModel.create(nom)
            },
        )
    }

    dossierASupprimer?.let { dossier ->
        ConfirmDeleteFolderDialog(
            folder = dossier,
            onDismiss = { dossierASupprimer = null },
            onChoice = { choix ->
                dossierASupprimer = null
                when (choix) {
                    FolderDeletionChoice.MOVE_TO_INBOX ->
                        if (dossier.isVault && dossier.id !in foldersState.unlockedFolderIds) {
                            dossierAOuvrir = dossier
                        } else {
                            foldersViewModel.deleteKeepingNotes(dossier)
                        }

                    FolderDeletionChoice.DELETE_EVERYTHING -> foldersViewModel.deleteWithNotes(dossier)
                }
            },
        )
    }

    dossierADeproteger?.let { dossier ->
        ConfirmRemoveVaultProtectionDialog(
            folder = dossier,
            onDismiss = { dossierADeproteger = null },
            onConfirm = {
                dossierADeproteger = null
                // ⚠️ **La session se relit ICI**, après la confirmation, jamais avant.
                //
                // Le dialogue prend le temps qu'il prend, et le verrouillage automatique peut
                // tomber pendant ce temps-là. Une décision prise à l'ouverture du dialogue serait
                // périmée à sa fermeture — c'est le même motif que l'état d'écran périmé de
                // l'éditeur, à une échelle où il coûterait un dossier entier.
                if (dossier.id in foldersState.unlockedFolderIds) {
                    foldersViewModel.removeVaultProtection(dossier.id)
                } else {
                    deprotectionEnAttente = dossier.id
                    dossierAOuvrir = dossier
                }
            },
        )
    }

    dossierAOuvrir?.let { dossier ->
        UnlockVaultSheet(
            folder = dossier,
            onDismiss = {
                dossierAOuvrir = null
                // Renoncer au secret, c'est renoncer au geste : la déprotection en attente tombe
                // avec la feuille. La laisser armée la ferait partir au prochain déverrouillage,
                // pour une tout autre raison.
                deprotectionEnAttente = null
            },
            onUnlocked = {
                dossierAOuvrir = null
                // ⚠️ Comparer l'identifiant, pas se contenter d'un booléen : la feuille peut avoir
                // été ouverte pour un autre dossier que celui dont la déprotection est en attente.
                val aDeproteger = deprotectionEnAttente
                deprotectionEnAttente = null
                if (aDeproteger == dossier.id) foldersViewModel.removeVaultProtection(dossier.id)
            },
        )
    }

    dossierAProteger?.let { dossier ->
        val mode = modeChoisi
        if (mode == null) {
            ChooseVaultModeSheet(
                onDismiss = { dossierAProteger = null },
                onChosen = { modeChoisi = it },
            )
        } else {
            CreateVaultSheet(
                folder = dossier,
                mode = mode,
                onDismiss = {
                    dossierAProteger = null
                    modeChoisi = null
                },
                onCreated = {
                    dossierAProteger = null
                    modeChoisi = null
                },
            )
        }
    }
}

/**
 * Affiche les retours des actions de dossier.
 *
 * ## ⚠️ Renommer et supprimer ne disent RIEN, et c'est repris de l'application publiée
 *
 * La tentation était d'ajouter « Dossier renommé » et « Dossier supprimé ». Ces chaînes n'existent
 * pas dans l'ARB, parce que la 2.0.3 ne les affiche pas : le tiroir se met à jour sous les yeux de
 * l'utilisateur, ce qui est le retour. Les inventer aurait créé deux clés que la version Flutter
 * n'a pas — donc une divergence d'i18n invisible au compilateur, et un écart de plus à réconcilier
 * en phase 8.
 *
 * Ce qui parle, ce sont les **échecs** et les **gestes de coffre**, parce qu'eux ne se voient pas.
 *
 * ⚠️ **Afficher PUIS consommer.** Il n'y a ici aucun porteur à consommer : le flux est collecté et
 * chaque valeur affichée à sa réception. C'est délibéré — un porteur recopié dans un `remember`
 * serait perdu à la rotation, et dans un `rememberSaveable`, il ferait un second dépositaire de la
 * même vérité.
 */
@Composable
private fun MessagesDeDossier(viewModel: FoldersDrawerViewModel, snackbars: SnackbarHostState) {
    val portee = rememberCoroutineScope()
    val contexte = androidx.compose.ui.platform.LocalContext.current
    val ressources = contexte.resources
    val erreurGenerique = stringResource(R.string.common_error)

    LaunchedEffect(viewModel, ressources) {
        viewModel.eventFlow.collect { evenement ->
            val message = when (evenement) {
                is FolderEvent.Renamed, is FolderEvent.Created, is FolderEvent.Deleted -> null

                is FolderEvent.VaultRemoved -> ressources.getQuantityString(
                    R.plurals.folder_remove_vault_done,
                    evenement.decrypted,
                    evenement.decrypted,
                )

                // 🔴 Un retrait de protection partiel laisse le dossier COFFRE et ses notes
                // rescellées. Le taire ferait croire à une réussite, donc à des notes désormais
                // lisibles sans secret — exactement l'inverse de ce qui s'est passé.
                is FolderEvent.VaultPartiallyRemoved ->
                    ressources.getString(R.string.folder_delete_decrypt_failed, evenement.failed)

                is FolderEvent.Failed ->
                    ressources.getString(R.string.folder_delete_cancelled_error, evenement.message ?: erreurGenerique)
            }
            // ⚠️ Meme raison qu'au-dessus : afficher ne doit pas suspendre la collecte.
            if (message != null) portee.launch { snackbars.showSnackbar(message) }
        }
    }
}
