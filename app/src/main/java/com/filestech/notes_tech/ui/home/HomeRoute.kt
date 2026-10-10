package com.filestech.notes_tech.ui.home

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.VaultMode
import com.filestech.notes_tech.ui.common.HoteDeMessages
import com.filestech.notes_tech.ui.common.displayName
import com.filestech.notes_tech.ui.folders.ConfirmDeleteFolderDialog
import com.filestech.notes_tech.ui.folders.ConfirmRemoveVaultProtectionDialog
import com.filestech.notes_tech.ui.folders.FolderAction
import com.filestech.notes_tech.ui.folders.FolderActionSheet
import com.filestech.notes_tech.ui.folders.FolderDeletionChoice
import com.filestech.notes_tech.ui.folders.FolderEvent
import com.filestech.notes_tech.ui.folders.FolderNameDialog
import com.filestech.notes_tech.ui.folders.FoldersDrawer
import com.filestech.notes_tech.ui.folders.FoldersDrawerViewModel
import com.filestech.notes_tech.ui.folders.GesteDeDossier
import com.filestech.notes_tech.ui.folders.SortDesNotes
import com.filestech.notes_tech.ui.folders.deverrouillageRequis
import com.filestech.notes_tech.ui.vault.ChooseVaultModeSheet
import com.filestech.notes_tech.ui.vault.CreateVaultSheet
import com.filestech.notes_tech.ui.vault.UnlockVaultSheet
import com.filestech.notes_tech.ui.vault.VaultAttempt
import com.filestech.notes_tech.ui.vault.VaultViewModel
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
     * Le geste **confirmé** qui n'attend plus que le secret du coffre.
     *
     * ⚠️ Sans ce report, l'utilisateur confirmait le geste le plus destructeur de l'application,
     * saisissait sa phrase secrète… et il ne se passait rien. Un geste accepté puis abandonné en
     * silence, ce que ce dépôt refuse partout ailleurs.
     *
     * 🔴 Et ce porteur ne servait qu'au **retrait de protection** : la suppression en gardant les
     * notes ouvrait la même feuille **sans rien mémoriser**, donc reproduisait exactement le défaut
     * que le paragraphe ci-dessus décrit. Les deux gestes passent maintenant par [lancerLeGeste].
     * Cf. [GesteDeDossier].
     */
    var gesteEnAttente by remember { mutableStateOf<GesteDeDossier?>(null) }
    var creationDeDossier by remember { mutableStateOf(false) }
    var dossierAOuvrir by remember { mutableStateOf<Folder?>(null) }
    var dossierAProteger by remember { mutableStateOf<Folder?>(null) }
    var modeChoisi by remember { mutableStateOf<VaultMode?>(null) }

    // The long press on a note (3.1.0): its sheet, its confirmation, what waits for a vault's secret.
    val gestes = remember { GestesDeNoteEnCours() }

    /**
     * Exécute un geste dont le coffre est **ouvert**. Séparé de [lancerLeGeste] parce que la reprise
     * après déverrouillage ne doit **pas** repasser par le contrôle : l'ensemble des coffres ouverts
     * vient d'un flux, et il peut ne pas encore porter celui qu'on vient d'ouvrir — on rouvrirait la
     * feuille en boucle.
     *
     * ⚠️ `when` **exhaustif** : un troisième geste ne pourra pas naître sans qu'on décide de sa
     * reprise. C'est ce qui remplace la vigilance par une erreur de compilation.
     */
    fun executerLeGeste(geste: GesteDeDossier) = when (geste) {
        is GesteDeDossier.RetirerLaProtection -> foldersViewModel.removeVaultProtection(geste.dossier.id)
        is GesteDeDossier.SupprimerEnGardantLesNotes -> foldersViewModel.deleteKeepingNotes(geste.dossier)
    }

    /** Le **seul** chemin par lequel ces gestes partent : il décide, puis exécute ou fait attendre. */
    fun lancerLeGeste(geste: GesteDeDossier) {
        if (deverrouillageRequis(geste, foldersState.unlockedFolderIds)) {
            gesteEnAttente = geste
            dossierAOuvrir = geste.dossier
        } else {
            executerLeGeste(geste)
        }
    }

    // La purge de la corbeille est un rattrapage d'arrière-plan, lancé une fois par entrée sur
    // l'écran. `Unit` en clé : la relancer à chaque recomposition ferait un balayage par frappe.
    LaunchedEffect(Unit) { homeViewModel.purgeExpiredTrash() }

    MessagesDeDossier(foldersViewModel, snackbars)
    MessagesDesGestesDeNote(homeViewModel, snackbars)
    ConstatsDeCoffre(snackbars)

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
                        ressourcesDeLEcran.getString(
                            R.string.home_vault_create_error,
                            ressourcesDeLEcran.getString(evenement.message),
                        ),
                    )
                }

                is HomeEvent.GesteEnAttenteDuCoffre -> {
                    gestes.attendreLeCoffre(evenement)
                    dossierAOuvrir = evenement.folder
                }

                // Said by `MessagesDesGestesDeNote`, which collects the same events.
                is HomeEvent.MovedToTrash, HomeEvent.Restored, HomeEvent.DeletedForever, is HomeEvent.ActionFailed,
                -> Unit
            }
        }
    }

    // 🔴 **Le `SnackbarHost` est HORS du tiroir, et c'est le correctif.**
    //
    // Il était posé dans le contenu de [ModalNavigationDrawer], donc **sous** le panneau du tiroir :
    // tout message déclenché depuis le tiroir s'affichait derrière lui. Or c'est précisément de là
    // que partent les gestes qui ont le plus besoin d'être confirmés — conversion en coffre, retrait
    // de protection, et le « %d notes déchiffrées » qui dit à quelqu'un que son dossier n'est plus
    // protégé. Ils étaient tous invisibles tant que le tiroir restait ouvert, c'est-à-dire dans le
    // cas normal, puisque rien ne le ferme.
    //
    // Vérifié à l'écran sur le S9 le 2026-08-15 : après une conversion, le tiroir est encore ouvert.
    // Un `Box` suffit — l'hôte est dessiné après le tiroir, donc au-dessus.
    Box(Modifier.fillMaxSize()) {
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
                    // Le tiroir reste ouvert : le dialogue s'affiche par-dessus, et refermer le
                    // tiroir ferait disparaitre la ligne qu'on est en train de renommer.
                    onRenameInbox = { dossierARenommer = it },
                )
            },
        ) {
            HomeScreen(
                state = state,
                onQueryChange = homeViewModel::onQueryChange,
                onSortSelected = homeViewModel::onSortSelected,
                onOpenNote = onOpenNote,
                onLongPressNote = { note ->
                    val coffreAOuvrir = gestes.appuiLong(note, foldersState.folders, foldersState.unlockedFolderIds)
                    if (coffreAOuvrir != null) dossierAOuvrir = coffreAOuvrir
                },
                onNewNote = homeViewModel::createNote,
                onOpenDrawer = { portee.launch { drawerState.open() } },
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                onOpenAbout = onOpenAbout,
                onDismissVaultLostBanner = homeViewModel::dismissVaultLostBanner,
            )
        }

        HoteDeMessages(
            etat = snackbars,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
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
        // The field starts from the name the user SEES, and confirming it unchanged writes nothing
        // (notes_tech 2.0.9, `folders_drawer.dart:115-119`). For a default inbox the shown name is a
        // translation: writing it back would change the stored name for no reason — and, once in a
        // language outside the default set, turn a default into a name the user never chose.
        val nomAffiche = dossier.displayName()
        FolderNameDialog(
            title = stringResource(R.string.folder_rename_title),
            fieldLabel = stringResource(R.string.folder_rename_field),
            initial = nomAffiche,
            onDismiss = { dossierARenommer = null },
            onConfirm = { nom ->
                dossierARenommer = null
                if (nom != nomAffiche) foldersViewModel.rename(dossier.id, nom)
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
                        lancerLeGeste(GesteDeDossier.SupprimerEnGardantLesNotes(dossier))

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
                lancerLeGeste(GesteDeDossier.RetirerLaProtection(dossier))
            },
        )
    }

    dossierAOuvrir?.let { dossier ->
        UnlockVaultSheet(
            folder = dossier,
            onDismiss = {
                dossierAOuvrir = null
                // Renoncer au secret, c'est renoncer au geste : celui qui attendait tombe avec la
                // feuille. Le laisser armé le ferait partir au prochain déverrouillage, pour une
                // tout autre raison.
                gesteEnAttente = null
                gestes.abandonner()
            },
            onUnlocked = {
                dossierAOuvrir = null
                // ⚠️ Comparer l'identifiant, pas se contenter d'un booléen : la feuille peut avoir
                // été ouverte pour un autre dossier que celui dont le geste attend — le verrouillage
                // automatique en ouvre une, lui aussi.
                val geste = gesteEnAttente
                gesteEnAttente = null
                if (geste != null && geste.dossier.id == dossier.id) executerLeGeste(geste)
                gestes.reprendreApres(dossier, homeViewModel::executer)
            },
        )
    }

    FeuillesDesGestesDeNote(gestes, homeViewModel::executer)

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
                // ⚠️ **Le message est posté ICI, pas dans la feuille** : elle se referme au même
                // instant, et un message affiché dessus disparaîtrait avec elle. Le portage ne disait
                // RIEN après une conversion — ni pendant, ni après — alors que l'opération
                // re-chiffre tout le contenu du dossier. Les deux chaînes existaient des deux côtés
                // sans être lues nulle part (`folders_drawer.dart:707`).
                onCreated = { chiffrees ->
                    dossierAProteger = null
                    modeChoisi = null
                    val message = if (chiffrees == 0) {
                        ressourcesDeLEcran.getString(R.string.vault_convert_success)
                    } else {
                        ressourcesDeLEcran.getString(R.string.vault_convert_success_with_count, chiffrees)
                    }
                    portee.launch { snackbars.showSnackbar(message) }
                },
                // 🔴 L'avertissement ne doit pas mourir avec la feuille : le dossier porte un
                // cadenas et tout ou partie de son contenu reste lisible au repos.
                onConversionIncomplete = { issue ->
                    dossierAProteger = null
                    modeChoisi = null
                    val message = when (issue) {
                        is VaultAttempt.Created -> ressourcesDeLEcran.getString(
                            R.string.vault_convert_partial_fail,
                            issue.failed,
                            issue.encrypted + issue.failed,
                        )

                        // ⚠️ Le chiffrement n'a pas commencé : **toutes** les notes sont en clair,
                        // pas seulement quelques-unes. Réutiliser la phrase du partiel donnerait un
                        // décompte, donc l'illusion que le reste est protégé.
                        else -> ressourcesDeLEcran.getString(
                            R.string.vault_convert_impossible,
                            ressourcesDeLEcran.getString(
                                (issue as? VaultAttempt.CreatedButNotEncrypted)?.message ?: R.string.error_unexpected,
                            ),
                        )
                    }
                    portee.launch { snackbars.showSnackbar(message) }
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
    LaunchedEffect(viewModel, ressources) {
        viewModel.eventFlow.collect { evenement ->
            val message = when (evenement) {
                // ⚠️ Renommer et créer **ne disent rien, exprès** : le tiroir se met à jour sous les
                // yeux de l'utilisateur, et le nom qu'il vient de taper s'affiche. Ajouter
                // « Dossier renommé » serait du bruit — c'est le choix de l'application publiée, et
                // il se tient. Supprimer, non : voir [messageDeSuppression].
                is FolderEvent.Renamed, is FolderEvent.Created -> null

                is FolderEvent.Deleted -> messageDeSuppression(ressources, evenement)

                is FolderEvent.VaultRemoved -> ressources.getQuantityString(
                    R.plurals.folder_remove_vault_done,
                    evenement.decrypted,
                    evenement.decrypted,
                )

                // 🔴 Un retrait de protection partiel laisse le dossier COFFRE et ses notes
                // rescellées. Le taire ferait croire à une réussite, donc à des notes désormais
                // lisibles sans secret — exactement l'inverse de ce qui s'est passé.
                // ⚠️ Un pluriel, et non plus « %1$d note(s) » : le contournement venait de
                // l'application publiée, qui n'avait pas de pluriel là où Android en a un.
                is FolderEvent.VaultPartiallyRemoved -> ressources.getQuantityString(
                    R.plurals.folder_delete_decrypt_failed,
                    evenement.failed,
                    evenement.failed,
                )

                is FolderEvent.Failed -> ressources.getString(
                    R.string.folder_delete_cancelled_error,
                    ressources.getString(evenement.message),
                )
            }
            // ⚠️ Meme raison qu'au-dessus : afficher ne doit pas suspendre la collecte.
            if (message != null) portee.launch { snackbars.showSnackbar(message) }
        }
    }
}

/**
 * Ce que dit la suppression d'un dossier.
 *
 * ## 🔴🔴 Pourquoi celle-ci parle, quand renommer et créer se taisent
 *
 * Le critère n'est pas l'importance du geste mais **ce que l'utilisateur voit se produire**. Un
 * renommage change le nom dans le tiroir, une création y fait apparaître une ligne : le retour est
 * l'écran lui-même. Une suppression, elle, ne montre que la **disparition du dossier** — et tait
 * entièrement le sort de ses notes, qui n'étaient pas à l'écran. Elles ont été déplacées vers la
 * boîte de réception, ou détruites, et rien ne le disait.
 *
 * ⚠️⚠️ **Le cas des notes en corbeille est le plus injuste des trois** : supprimer un dossier détruit
 * aussi ses notes déjà en corbeille — `folder_id` est une clé étrangère `ON DELETE CASCADE` et la
 * mise en corbeille conserve ce lien — alors qu'elles étaient visibles depuis l'écran Corbeille et
 * qu'on pouvait encore les restaurer. C'est pourquoi le nombre annoncé vient de
 * `countAllInFolder` et non de `countInFolder`.
 *
 * ⚠️ **Zéro note n'est pas « zéro » mais « rien à dire de plus »** : `getQuantityString` avec `0`
 * rendrait « Dossier supprimé, 0 note déplacée » en français, la catégorie `one` couvrant zéro. Un
 * dossier vide n'a pas de sort de notes à annoncer, donc une phrase courte et vraie.
 *
 * ⚠️ Le `when` est exhaustif sur [SortDesNotes] : un troisième sort fera échouer la compilation
 * plutôt que d'afficher le message d'un autre geste.
 */
internal fun messageDeSuppression(ressources: Resources, evenement: FolderEvent.Deleted): String {
    if (evenement.notes == 0) return ressources.getString(R.string.folder_deleted)
    val pluriel = when (evenement.sort) {
        SortDesNotes.DEPLACEES -> R.plurals.folder_deleted_notes_moved
        SortDesNotes.SUPPRIMEES -> R.plurals.folder_deleted_notes_removed
    }
    return ressources.getQuantityString(pluriel, evenement.notes, evenement.notes)
}

/**
 * Annonce les dossiers devenus des coffres **malgré** une annulation.
 *
 * ## 🔴 Pourquoi ça ne peut pas être dit par la feuille
 *
 * Le constat arrive après que l'utilisateur a annulé, donc après que la feuille a disparu. C'est la
 * même contrainte que pour `onConversionIncomplete`, et elle a la même réponse : l'écran parle, la
 * feuille non.
 *
 * ⚠️ **Le `hiltViewModel()` d'ici est celui des feuilles.** Il s'accroche à l'entrée de navigation,
 * pas à la composition de la feuille — vérifié en phase 6 — donc cette collecte reçoit bien ce que
 * la tentative annulée a constaté après coup. Si un jour les feuilles portaient leur propre
 * instance, ce message ne s'afficherait plus jamais et **rien ne le signalerait** : c'est le genre
 * de câblage qui ne casse pas, il se tait.
 *
 * @see com.filestech.notes_tech.ui.vault.VaultViewModel.cancelAttempt
 */
@Composable
private fun ConstatsDeCoffre(snackbars: SnackbarHostState) {
    val viewModel: VaultViewModel = hiltViewModel()
    val dossier by viewModel.creationEchappee.collectAsStateWithLifecycle()
    val message = stringResource(R.string.vault_convert_escaped_cancellation)

    // ⚠️ **Afficher PUIS consommer**, et ici la règle a une conséquence utile : `showSnackbar`
    // suspend jusqu'à la fermeture du message. Si l'utilisateur quitte l'accueil avant de l'avoir
    // vu, l'effet est annulé, [VaultViewModel.constatAnnonce] n'est pas appelé, et l'avertissement
    // **revient** au retour. C'est voulu : il dit que des notes sont en clair sous un cadenas.
    LaunchedEffect(dossier) {
        if (dossier != null) {
            snackbars.showSnackbar(message)
            viewModel.constatAnnonce()
        }
    }
}
