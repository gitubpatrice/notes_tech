package com.filestech.notes_tech.ui.editor

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.markdown.LinkTarget
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import com.filestech.notes_tech.ui.common.CorpsDeDialogue
import com.filestech.notes_tech.ui.common.EmptyState
import com.filestech.notes_tech.ui.common.HoteDeMessages
import com.filestech.notes_tech.ui.common.MIME_MARKDOWN
import com.filestech.notes_tech.ui.common.displayName
import com.filestech.notes_tech.ui.common.ouvrirUnLienExterne
import com.filestech.notes_tech.ui.common.partagerUnFichier
import com.filestech.notes_tech.ui.common.rememberNoteDateFormatter
import com.filestech.notes_tech.ui.secure.SaisieDeCoffre
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.theme.CouleursDeNote
import com.filestech.notes_tech.ui.theme.SemanticColors
import com.filestech.notes_tech.ui.vault.UnlockVaultSheet
import com.filestech.notes_tech.ui.voice.SurcoucheDeDictee
import com.filestech.notes_tech.ui.voice.rememberControleurDeDictee
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * L'éditeur d'une note.
 *
 * Le titre et le contenu sont deux champs sans décoration, pour que la note ressemble à du texte et
 * non à un formulaire — c'est la mise en page de la version publiée.
 */
@Composable
fun NoteEditorRoute(onBack: () -> Unit, onOpenNote: (String) -> Unit, onInstallerLaDictee: () -> Unit) {
    val viewModel: NoteEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liens by viewModel.liens.collectAsStateWithLifecycle()

    // ⚠️ **Pas de vidage explicite avant de naviguer**, contrairement à l'application publiée qui
    // appelle `_flushSave()` dans `_openLinkedNote`. Ici, ouvrir une note empile une entrée et
    // **dispose** ce composable-ci : le `DisposableEffect` ci-dessous déclenche l'enregistrement, sur
    // la portée applicative et sous `NonCancellable`, donc il aboutit quoi qu'il arrive ensuite.
    // Ajouter un second appel ne ferait que dupliquer un geste que la comparaison de texte rendrait
    // sans effet — et ferait croire, à la relecture, que le premier chemin ne suffit pas.
    val ouvrirUneAutreNote: (String) -> Unit = { cible ->
        // Une note qui se cite elle-même ne s'ouvre pas par-dessus elle-même. Le cas ne devrait pas
        // remonter — l'indexation annule le lien d'une note vers elle-même — mais la garde coûte une
        // ligne et l'empilement qu'elle évite serait déroutant.
        if (cible != state.note?.id) onOpenNote(cible)
    }

    // Reading or writing (3.1.0) — it replaces 2.0.9's Edit / Preview switch (D-024). `null` until the
    // note has loaded, then decided ONCE: an existing note opens to be read, a blank one — just created,
    // or emptied — to be written, there being nothing to read. Saved: a rotation, or the app lock taking
    // the screen away and giving it back, returns to the side the user was on.
    // ⚠️ Writing at once when the long press's "Edit" opened it: the choice is already made.
    var lectureChoisie by rememberSaveable { mutableStateOf(if (viewModel.ouvrirEnEcriture) false else null) }
    val chargee = !state.loading && state.note != null && state.loadError == null && state.lockedVault == null
    // Until the choice is stored, the screen already shows the side it will be: no frame of the other.
    val lecture = lectureChoisie ?: (chargee && state.content.text.isNotBlank())
    LaunchedEffect(chargee) {
        if (chargee && lectureChoisie == null) lectureChoisie = state.content.text.isNotBlank()
    }

    var autocompletionOuverte by rememberSaveable { mutableStateOf(false) }
    val suggestions by viewModel.suggestionsDeLien.collectAsStateWithLifecycle()

    // ⚠️ Fermer remet la recherche à zéro. Sans cela, rouvrir la feuille afficherait les résultats
    // de la fois précédente le temps du freinage — et l'utilisateur pourrait taper sur l'un d'eux.
    val fermerLAutocompletion = {
        autocompletionOuverte = false
        viewModel.reinitialiserLaRecherche()
    }

    var deplacementOuvert by rememberSaveable { mutableStateOf(false) }
    var couleurOuverte by rememberSaveable { mutableStateOf(false) }
    var infosOuvertes by rememberSaveable { mutableStateOf(false) }

    // Les deux détours du déplacement, retenus par **identifiant** pour survivre à une rotation et
    // à une mort de processus — un `Folder` ne se met pas dans un `Bundle`.
    var sortieDeCoffreCible by rememberSaveable { mutableStateOf<String?>(null) }
    var deverrouillageCible by rememberSaveable { mutableStateOf<String?>(null) }
    val dossiers by viewModel.dossiers.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }

    // ⚠️ La dictée est **entièrement déportée** : permission, lanceur, superposition, dialogue de
    // refus et sept messages distincts. Posée ici, elle a fait franchir à cette fonction les seuils
    // de longueur ET de complexité que detekt garde — et le gate avait raison. Cf.
    // `ui/voice/ControleurDeDictee.kt`.
    // ⚠️ What is inserted — dictated text, a link — goes into the text field: back to Edit, or the
    // text lands where the user cannot see it. notes_tech 2.0.9's `_insertAtCursor` does the same.
    val dictee = rememberControleurDeDictee(
        onTexte = { texte ->
            lectureChoisie = false
            viewModel.insererAuCurseur(texte)
        },
        messages = messages,
        onInstallerLeModele = onInstallerLaDictee,
    )

    val retourHaptique = LocalHapticFeedback.current
    val contexte = LocalContext.current
    val ressources = LocalResources.current
    val portee = rememberCoroutineScope()

    val lienDeLApercu: (LinkTarget) -> Unit = { cible ->
        when (cible) {
            is LinkTarget.Note -> viewModel.ouvrirOuCreerLaNote(cible.title)
            is LinkTarget.Web -> if (!ouvrirUnLienExterne(contexte, cible.url)) {
                portee.launch { messages.showSnackbar(ressources.getString(R.string.note_preview_link_no_app)) }
            }
        }
    }

    AnnonceDEnregistrement(enregistrement = state.saving, echec = state.saveFailed || state.lostToVaultLock)

    // ⚠️ **Une fonction, pas un gabarit pré-formaté.** Passer « %s » puis formater casserait en
    // silence le jour où la chaîne traduite gagne un paramètre — relevé par l'audit i18n du
    // 2026-08-14 sur l'export des réglages, et la même forme est reprise ici.
    val mentionDeCoffre: (String) -> String = { nom -> ressources.getString(R.string.export_note_from_vault, nom) }
    val titreDuSelecteur = stringResource(R.string.common_share)
    // From the activity's resources, like `mentionDeCoffre`: the export writes the inbox in the
    // language the user chose, not the phone's.
    val libelleBoiteDeReception = stringResource(R.string.home_folder_inbox)

    IssueDUneAction(
        action = action,
        contexte = contexte,
        ressources = ressources,
        titreDuSelecteur = titreDuSelecteur,
        messages = messages,
        portee = portee,
        onConsommer = viewModel::consommerLAction,
        onBack = onBack,
        onOuvrirNote = ouvrirUneAutreNote,
    )

    // Closed — not merely hidden — when the note stops being readable: a vault that locks while the sheet
    // is open would otherwise leave the sealed note's colour shown and changeable (GPT-5.6 review).
    val couleurPossible = state.note != null && state.lockedVault == null && state.loadError == null
    LaunchedEffect(couleurPossible) { if (!couleurPossible) couleurOuverte = false }
    if (couleurOuverte && couleurPossible) {
        FeuilleDeCouleur(
            actuelle = state.note?.color,
            dansUnCoffre = state.isVaultNote,
            onChoisir = { couleur ->
                couleurOuverte = false
                viewModel.setColor(couleur)
            },
            onDismiss = { couleurOuverte = false },
        )
    }

    if (deplacementOuvert) {
        FeuilleDeDeplacement(
            dossiers = dossiers,
            dossierActuel = state.note?.folderId,
            onChoisir = { cible ->
                deplacementOuvert = false
                // 🔴 **Deux détours possibles avant d'écrire, et ils s'excluent l'un l'autre** :
                // le premier ne concerne qu'une destination coffre, le second qu'une destination
                // ordinaire. Il n'y a donc rien à enchaîner — un seul des deux peut s'appliquer.
                val destination = dossiers.firstOrNull { it.id == cible }
                when {
                    // La destination est un coffre fermé : demander le secret AVANT de toucher au
                    // contenu. Sans ce détour, le scellement échoue faute de clé et l'utilisateur
                    // reçoit un message d'erreur là où il fallait lui poser une question.
                    destination != null && destination.isVault && !viewModel.estDeverrouille(cible) ->
                        deverrouillageCible = cible

                    // Sortie de coffre : la seule des quatre combinaisons qui retire une protection.
                    state.note?.isLocked == true && destination?.isVault != true -> sortieDeCoffreCible = cible

                    else -> viewModel.deplacerVers(cible)
                }
            },
            onDismiss = { deplacementOuvert = false },
        )
    }

    sortieDeCoffreCible?.let { cible ->
        DialogueDeSortieDeCoffre(
            onConfirmer = {
                sortieDeCoffreCible = null
                viewModel.deplacerVers(cible, sortieDeCoffreConfirmee = true)
            },
            onAnnuler = { sortieDeCoffreCible = null },
        )
    }

    // ⚠️ La feuille est cherchée dans la liste à l'affichage, et non mémorisée telle quelle : un
    // `Folder` n'est pas `Saveable`, et c'est son identifiant qui doit survivre à une rotation.
    // Si le dossier a disparu entre-temps, il n'y a plus de destination et l'état se referme.
    deverrouillageCible?.let { cible ->
        val destination = dossiers.firstOrNull { it.id == cible }
        if (destination == null) {
            deverrouillageCible = null
        } else {
            UnlockVaultSheet(
                folder = destination,
                onDismiss = { deverrouillageCible = null },
                onUnlocked = {
                    deverrouillageCible = null
                    viewModel.deplacerVers(cible)
                },
            )
        }
    }

    SurcoucheDeDictee(dictee)

    if (infosOuvertes) {
        // ⚠️ Rebuilt from the live state on every recomposition, never captured when the menu was
        // tapped: a rotation or a save landing while the panel is open must not show stale dates.
        // If the note stops being showable — vault relocked underneath, for instance — the panel
        // closes instead of describing a text that is no longer on screen.
        val info = noteInfoOf(state, folderName = state.folder?.displayName().orEmpty())
        if (info != null) {
            NoteInfoDialog(info = info, onDismiss = { infosOuvertes = false })
        } else {
            // Closed from an effect, not by writing the state in the middle of a composition.
            LaunchedEffect(Unit) { infosOuvertes = false }
        }
    }

    if (autocompletionOuverte) {
        SaisieDeCoffre(
            actif = state.isVaultNote,
            deposer = viewModel::deposerDansLePressePapiers,
            surEchec = viewModel::copieProtegeeRefusee,
        ) {
            FeuilleDAutocompletion(
                suggestions = suggestions,
                onRequeteChange = viewModel::chercherUnTitre,
                onChoisirUnTitre = { titre ->
                    lectureChoisie = false
                    viewModel.insererUnLien(titre)
                    fermerLAutocompletion()
                },
                onCreer = { titre ->
                    lectureChoisie = false
                    viewModel.creerPuisLier(titre)
                    fermerLAutocompletion()
                },
                onDismiss = fermerLAutocompletion,
            )
        }
    }

    // ⚠️ Le contenu déchiffré d'une note de coffre est à l'écran, en clair, pendant tout le temps
    // où on la lit. Le drapeau est donc forcé pour cet écran-là, même si le réglage est désactivé —
    // c'est le seul moment de l'application où le secret d'un coffre est lisible.
    //
    // La condition suit l'état : une note qui cesse d'être coffrée rend la demande d'elle-même.
    SecureWindowGuard(active = state.isVaultNote)

    // ⚠️ L'enregistrement au départ passe par `DisposableEffect`, pas par le bouton retour seul :
    // on quitte aussi par le geste système, par une navigation, ou parce que le processus se
    // termine. Un seul de ces chemins couvert, c'est du texte perdu par les trois autres.
    DisposableEffect(Unit) {
        onDispose { viewModel.saveNow() }
    }

    // 🔴 **And when the app leaves the screen**, which does not dispose it: the vaults close on the
    // PROCESS stop (`NotesTechApplication`), and a vault that closes now drops the editor's plaintext
    // (audit 2026-09-26, V1). The pending save must leave before, while the key still exists —
    // otherwise the last half second of typing is reported lost at the return.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveNow() }

    // ⚠️ **Un seul geste, deux chemins** : le Retour système et la flèche de la barre. Les deux vident
    // puis quittent. Until 3.1.0 the « Terminé » tick was a third path to the same gesture; it now ends
    // the WRITING and shows the note to be read (`onTerminer` below), and leaving stays these two.
    val quitter = {
        viewModel.saveNow()
        onBack()
    }
    BackHandler(onBack = quitter)

    SaisieDeCoffre(
        actif = state.isVaultNote,
        deposer = viewModel::deposerDansLePressePapiers,
        surEchec = viewModel::copieProtegeeRefusee,
    ) {
        NoteEditorScreen(
            state = state,
            liens = liens,
            messages = messages,
            dicteeActive = dictee.actif,
            onQuitter = quitter,
            onTitreChange = viewModel::onTitleChange,
            onContenuChange = viewModel::onContentChange,
            onDicter = dictee.demarrer,
            onInsererUnLien = { autocompletionOuverte = true },
            onEpingler = viewModel::setPinned,
            onFavori = viewModel::setFavorite,
            onInfos = { infosOuvertes = true },
            onDeplacer = { deplacementOuvert = true },
            onCouleur = { couleurOuverte = true },
            onExporter = { viewModel.exporterLaNote(libelleBoiteDeReception, mentionDeCoffre) },
            onCopier = {
                // Retour haptique sur un geste réussi, comme l'application publiée
                // (`note_editor_screen.dart:544`). Il part à l'appui, pas à l'issue : c'est l'accusé de
                // réception du geste, pas celui de son résultat, que le message se charge d'annoncer.
                retourHaptique.performHapticFeedback(HapticFeedbackType.ContextClick)
                viewModel.copierEnMarkdown()
            },
            // ⚠️ Pas de `onBack()` ici : la navigation part quand la suppression a REUSSI, depuis
            // l'observation de `action` ci-dessus. Quitter tout de suite laissait croire à une note
            // supprimée qui ne l'était pas.
            onCorbeille = viewModel::moveToTrash,
            onOuvrirNote = ouvrirUneAutreNote,
            // ⚠️ Un lien fantôme désigne une note annoncée et pas encore écrite : l'appuyer la crée, avec
            // le titre du lien, **puis l'ouvre** — le même chemin qu'un `[[Titre]]` touché dans l'aperçu,
            // comme dans l'application publiée. Le texte de la note, lui, ne bouge pas.
            onLienFantome = viewModel::ouvrirOuCreerLaNote,
            lecture = lecture,
            onModifier = { lectureChoisie = false },
            // Saved first: the tick is the "it is saved, you may stop" signal 2.0.9 added on user
            // feedback, and the reading that follows shows the saved note.
            onTerminer = {
                viewModel.saveNow()
                lectureChoisie = true
            },
            onLienDeLApercu = lienDeLApercu,
        )
    }

    state.lockedVault?.let { dossier ->
        UnlockVaultSheet(
            folder = dossier,
            onDismiss = onBack,
            onUnlocked = viewModel::retryAfterUnlock,
        )
    }
}

/**
 * L'éditeur d'une note, **sans état** : ce qu'il montre ne dépend que de [state] et de [liens].
 *
 * ## 🔴 Cinquième écran à recevoir ce découpage, et pour la même raison que les quatre autres
 *
 * `HomeScreen`, `TrashScreen`, `SearchScreen` et `SettingsScreen` l'ont reçu parce que les états qui
 * portaient leurs défauts sont ceux qu'on **n'atteint pas** en pilotant l'application : la fenêtre
 * entre une requête et sa réponse (§75, §76), une ligne désactivée le temps d'un effacement (§77).
 *
 * Ici, ce sont les **quatre issues de chargement** — introuvable, dossier coffre disparu, contenu
 * abîmé, coffre refermé pendant la frappe — plus l'échec d'enregistrement et sa raison. Aucune ne
 * s'obtient sur un téléphone sans abîmer une vraie base ; toutes se posent en une ligne ici.
 *
 * ⚠️ Ce qui **reste** dans [NoteEditorRoute] et n'en descendra pas : les quatre feuilles (elles
 * lisent des flux du ViewModel et ont chacune leur propre ligne de parité), `SecureWindowGuard` — qui
 * agit sur la **fenêtre** et non sur l'affichage, et dont le contrôleur manquant est une erreur
 * volontaire (§77) —, l'annonce d'enregistrement freinée, et l'enregistrement au départ.
 */
@Composable
fun NoteEditorScreen(
    state: EditorUiState,
    liens: PanneauDeLiens,
    messages: SnackbarHostState,
    dicteeActive: Boolean,
    onQuitter: () -> Unit,
    onTitreChange: (TextFieldValue) -> Unit,
    onContenuChange: (TextFieldValue) -> Unit,
    onDicter: () -> Unit,
    onInsererUnLien: () -> Unit,
    onEpingler: (Boolean) -> Unit,
    onFavori: (Boolean) -> Unit,
    // ⚠️ No default: a menu entry wired to `{}` would be a dead button that compiles.
    onInfos: () -> Unit,
    onDeplacer: () -> Unit,
    /** The ⋮ menu's "Color" (3.1.0): the sheet that colours the note's card in the lists. */
    onCouleur: () -> Unit,
    onExporter: () -> Unit,
    onCopier: () -> Unit,
    onCorbeille: () -> Unit,
    onOuvrirNote: (String) -> Unit,
    onLienFantome: (String) -> Unit,
    /**
     * `true`: the note is READ (3.1.0) — its title as a heading, its Markdown drawn (D-024), no field,
     * so no cursor and no keyboard; an "Edit" button at the bottom right writes it.
     */
    lecture: Boolean,
    /** Reading → writing: the "Edit" button. */
    onModifier: () -> Unit,
    /** Writing → reading: the ✓ tick, which saves first. */
    onTerminer: () -> Unit,
    onLienDeLApercu: (LinkTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    // Leaving the reading puts the cursor in the body, and with it the keyboard: "Edit" means "write".
    // Only after that button — a note created blank opens to be written, as before, without forcing it.
    val focusDuCorps = remember { FocusRequester() }
    var focaliserLeCorps by remember { mutableStateOf(false) }
    LaunchedEffect(lecture, focaliserLeCorps) {
        if (!lecture && focaliserLeCorps) {
            focaliserLeCorps = false
            focusDuCorps.requestFocus()
        }
    }
    val noteOuverte = state.note != null && state.lockedVault == null && state.loadError == null && !state.loading
    Scaffold(
        modifier = modifier,
        snackbarHost = { HoteDeMessages(messages) },
        floatingActionButton = {
            if (lecture && noteOuverte) {
                // 🔴 The content overload, NOT `text = …, icon = …`: measured on the emulator, the
                // latter's label did not reach the button's merged node — TalkBack announced a button
                // with no name, and the editor's sweep caught it (`actionnablesSansNom`). Here the
                // label is a plain child of the clickable surface, so it merges, as on every button.
                ExtendedFloatingActionButton(
                    containerColor = SemanticColors.primaryButton,
                    contentColor = SemanticColors.onPrimaryButton,
                    onClick = {
                        // The cursor at the END: "Edit" means "go on writing". A loaded note's value
                        // starts at 0, which put the cursor before the first word — measured by the
                        // end-to-end test of the tick. A cursor move is not an edit: the save compares
                        // the text only.
                        onContenuChange(state.content.copy(selection = TextRange(state.content.text.length)))
                        focaliserLeCorps = true
                        onModifier()
                    },
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                    Text(
                        text = stringResource(R.string.note_editor_mode_edit),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onQuitter) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_close),
                        )
                    }
                },
                title = {
                    TitreDeLEditeur(
                        dossier = state.folder?.displayName().orEmpty(),
                        enregistrement = state.saving,
                        // 🔴 **`loadError` compte comme un échec pour cette ligne.** Sans lui,
                        // l'écran affichait « Enregistré », coche comprise, **par-dessus un état
                        // vide qui dit « impossible d'ouvrir »** — deux récits concurrents, et le
                        // rassurant était le faux. Relevé CONFIRMÉ par une relecture externe
                        // (GPT-5.2, 2026-08-15).
                        echec = state.saveFailed || state.lostToVaultLock || state.loadError != null,
                    )
                },
                actions = {
                    val note = state.note
                    // Nothing to do on a note that could not be opened — see the first comment below.
                    val ouvrable = note != null && state.lockedVault == null && state.loadError == null
                    // ⚠️ **Rien à faire sur une note qu'on n'a pas pu ouvrir.** `loadError` laisse
                    // `note` renseignée — c'est utile au diagnostic — mais épingler, mettre en
                    // favori ou insérer un lien dans un contenu qu'on n'a jamais déchiffré n'a aucun
                    // sens, et « Terminé » n'aurait rien à enregistrer. Même relecture.
                    if (ouvrable && !lecture) {
                        // ⚠️ 3.1.0: the tick, the microphone and the link button are writing tools,
                        // shown while writing only. Reading keeps the ⋮ menu, below, unchanged.
                        //
                        // 🔴 **Un bouton « Terminé » visible, alors que l'enregistrement est
                        // automatique.**
                        //
                        // Ce n'est pas redondant avec la flèche de retour : l'application publiée
                        // l'a ajouté sur un retour utilisateur daté (`note_editor_screen.dart:988`),
                        // parce que *sans bouton visible, on ne sait pas qu'on peut quitter sans
                        // risque*. Une note qui s'enregistre toute seule demande un signal explicite
                        // de fin, sinon l'utilisateur reste sur l'écran à chercher « Enregistrer ».
                        //
                        // Since 3.1.0 it no longer leaves: it saves and shows the note to be read —
                        // the end of writing, which is what that feedback asked to see. Leaving is the
                        // arrow's, and the system Back's.
                        // ⚠️⚠️ **Une icône, pas un bouton libellé** — mesuré sur le S9 : un
                        // `FilledTonalButton` portant le mot « Terminé » fait cinq éléments d'action,
                        // et le titre est alors écrasé **à zéro pixel**. Le nom du dossier et l'état
                        // de l'enregistrement disparaissaient purement et simplement. L'application
                        // publiée peut se le permettre parce que son titre EST l'indicateur, sur une
                        // seule ligne étroite ; ici il en porte deux.
                        //
                        // La coche reste visible et découvrable, et sa description la nomme : c'est
                        // le « bouton visible » que le retour utilisateur réclamait, à la largeur
                        // des autres.
                        IconButton(
                            onClick = {
                                focus.clearFocus(force = true)
                                onTerminer()
                            },
                        ) {
                            // 🔴 Verte, et pas de la teinte des icônes ordinaires : relevé sur le
                            // S9 le 2026-08-16, « on la voit pas bien ». Elle avait le même poids
                            // visuel que le micro et le lien, alors qu'elle seule dit « c'est
                            // enregistré, vous pouvez partir ». Cf. [SemanticColors.validationIcon],
                            // qui explique aussi pourquoi ce n'est ni `primary` ni un vert en dur.
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.note_editor_tooltip_done),
                                tint = SemanticColors.validationIcon,
                            )
                        }
                        // 🔴 Le micro AVANT le lien : c'est le geste qui produit du texte, et il
                        // doit être atteignable sans réfléchir. ⚠️ Désarmé pendant une dictée —
                        // `DictationViewModel` refuse le second appel de toute façon, mais un
                        // bouton qui accepte un geste sans effet se réappuie.
                        IconButton(
                            onClick = onDicter,
                            enabled = !dicteeActive,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Mic,
                                // 🔴 **La chaîne de CE bouton, pas le titre d'un autre écran.** Il
                                // portait `voice_setup_title`, c'est-à-dire le titre de l'écran
                                // d'installation du modèle — un écran que ce bouton n'ouvre pas :
                                // `dictee.demarrer` lance un enregistrement. `note_editor_tooltip_dictate`
                                // existait, traduite des deux côtés, et n'était lue **nulle part** ;
                                // l'application publiée l'emploie précisément ici
                                // (`voice_record_button.dart:59`).
                                //
                                // ⚠️ **Aucun défaut audible aujourd'hui** : les deux valeurs coïncident
                                // en français comme en anglais. C'est un défaut **latent** — le jour où
                                // le titre de l'écran de réglages se distingue de l'action, ce bouton
                                // annoncerait un titre d'écran sans rapport, en silence. Même motif que
                                // le ⋮ de l'accueil (§73), relevé par un balayage de cohérence.
                                contentDescription = stringResource(R.string.note_editor_tooltip_dictate),
                            )
                        }
                        // ⚠️ « Insérer un lien » est un bouton d'icône, **pas** une entrée de menu :
                        // c'est le geste d'écriture le plus fréquent de cet écran, et l'application
                        // publiée le place au même endroit, à côté de l'épingle et du favori.
                        IconButton(onClick = onInsererUnLien) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = stringResource(R.string.note_editor_tooltip_insert_link),
                            )
                        }
                    }
                    if (ouvrable) {
                        MenuDeDebordement(
                            epinglee = note.pinned,
                            favorite = note.favorite,
                            // ⚠️ **La valeur inverse est calculée ICI**, où la note est connue, et le
                            // rappel ne reçoit qu'un booléen. Faire calculer `!note.pinned` à la
                            // `Route` l'obligerait à relire `state.note` — deux lecteurs du même champ
                            // pour un même geste, dont l'un peut être en retard d'une recomposition.
                            onEpingler = { onEpingler(!note.pinned) },
                            onFavori = { onFavori(!note.favorite) },
                            onInfos = onInfos,
                            onDeplacer = onDeplacer,
                            onCouleur = onCouleur,
                            onExporter = onExporter,
                            onCopier = onCopier,
                            onCorbeille = onCorbeille,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val chargementEnCours = stringResource(R.string.common_loading)

            // Above the Edit / Preview branch, not inside it: the branch leaving the composition took
            // the preview's position with it, and every switch put a long note back at its top
            // (GPT-5.6 review, 2026-09-25). Saveable, so a rotation keeps it too. The Edit side has no
            // such state to keep: its field scrolls itself and exposes none — as in 2.0.9, where the
            // field is rebuilt on each switch.
            val defilementDeLApercu = rememberLazyListState()

            // Recopie locale : `state` est un délégué, le lissage de type ne s'y applique pas.
            val erreurDeChargement = state.loadError

            when {
                // ⚠️ L'étiquette n'est pas décorative : sans elle, un lecteur d'écran ne dit
                // **rien** pendant le chargement — et une note de coffre peut mettre une seconde à
                // se déchiffrer sur un appareil ancien. `common_loading` existait pour ça.
                state.loading -> CircularProgressIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = chargementEnCours },
                )

                state.notFound -> EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.note_editor_error_not_found),
                )

                // ⚠️⚠️ **Correction d'un commentaire qui mentait.** Il affirmait qu'un coffre
                // auto-détruit « porte les deux états » et que l'ordre les départageait. C'est faux :
                // le ViewModel pose `loadError` **sans** `lockedVault`, les deux ne sont jamais vrais
                // ensemble. L'ordre reste, mais comme garde-fou d'écriture — le jour où un chemin
                // poserait les deux, c'est l'erreur qui doit gagner, jamais l'invitation à saisir un
                // secret. Relevé CONFIRMÉ par une relecture externe (Gemini, 2026-08-15), sur un
                // commentaire que je venais d'écrire.
                //
                // 🔴 **Icône d'avertissement, PAS un cadenas ouvert.** Un cadenas ouvert devant
                // « coffre auto-détruit » promet visuellement une récupération qui n'existe pas —
                // le message dit la vérité, l'image disait le contraire.
                erreurDeChargement != null -> EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = stringResource(erreurDeChargement),
                )

                state.lockedVault != null -> EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.note_card_locked),
                    subtitle = if (state.lostToVaultLock) {
                        stringResource(R.string.note_editor_error_vault_relocked_during_edit)
                    } else {
                        null
                    },
                )

                // 🔴 **The layout of notes_tech 2.0.9: header, body filling the rest, links panel.**
                //
                // The title and the Edit / Preview switch stay put, where 2.0.9's `SegmentedButton`
                // sits above its `Expanded` body; the body — the field, or the preview — fills what is
                // left and scrolls itself; the links panel stays under it. Two defects came from the
                // port's first layout, a column scrolling as one:
                //
                // 1. 🔴🔴 **A long note crashed the editor on opening.** The body field, measured at
                //    its full height inside the scrolling column, asked for Constraints taller than
                //    Compose can represent next to a phone's width — about 262 000 px: "Can't
                //    represent a width of 1080 and height of 360096", measured on the S9 with 5 000
                //    lines (2026-09-25), roughly 3 600 lines and up. Every opening of such a note
                //    crashed the app, since the editor opens in Edit mode; 2.0.9 opens them. A field
                //    that fills its space scrolls within it and never asks for that height.
                // 2. Leaving the preview of a long note meant scrolling back to its top to find the
                //    switch.
                //
                // ⚠️ With a floor 2.0.9 lacks: in a window too short for the header, the body and
                // the panel, the three scroll together — see `MiseEnPageDeLEditeur`.
                else -> {
                    // ⚠️ The text on screen, not the last saved one: what the preview draws is what
                    // the user has just typed — the save is 500 ms behind. Called in both modes: a
                    // reading, and so the preview's position, survives a trip to Edit when nothing
                    // was typed (see `rememberLectureDeLApercu`).
                    val rendu = rememberLectureDeLApercu(state.content.text, actif = lecture)
                    MiseEnPageDeLEditeur(
                        enTete = { EnTeteDeLEditeur(state, onTitreChange, lecture) },
                        pied = {
                            Column {
                                PiedDeLEditeur(liens, onOuvrirNote, onLienFantome)
                                // Room for the "Edit" button: without it, the button would sit over the
                                // last links of the panel, which a tap could then no longer reach.
                                if (lecture) Spacer(Modifier.height(HAUTEUR_DU_BOUTON_MODIFIER))
                            }
                        },
                        modifier = Modifier.fillMaxSize().imePadding(),
                    ) {
                        if (lecture) {
                            FicheDeLecture(state, rendu, defilementDeLApercu, onLienDeLApercu)
                        } else {
                            TextField(
                                value = state.content,
                                onValueChange = onContenuChange,
                                // ⚠️ `note_editor_content` était traduite des deux côtés et lue **nulle
                                // part** — le publié en fait le `labelText` de ce champ exactement. Même
                                // discriminant que §79, appliqué au même écran le même jour.
                                label = { Text(stringResource(R.string.note_editor_content)) },
                                placeholder = { Text(stringResource(R.string.note_editor_content_hint)) },
                                textStyle = MaterialTheme.typography.bodyLarge,
                                colors = champSansDecor(),
                                modifier = Modifier.fillMaxSize().focusRequester(focusDuCorps),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The editor's three parts, top to bottom: [enTete], [corps], [pied] — 2.0.9's layout, with a floor.
 *
 * The body fills what the header and the links panel leave, as 2.0.9's `Expanded` does, but never
 * less than [HAUTEUR_MINIMALE_DU_CORPS]; in a window too short for that, the three parts scroll
 * together, as in the port's first layout. Without the floor, measured on the S9 in landscape, the
 * body field was 40 dp tall, and **0 dp with the keyboard up**: the header took all the height the
 * keyboard left, and the user typed blind (Gemini review, 2026-09-25). 2.0.9 has the same layout,
 * and the same defect.
 *
 * 🔴 The body is always measured with a **fixed** height, never at its full one: a field measured
 * at its full height is what crashed the editor on a long note. What scrolls here is the column of
 * the three parts; the field, or the preview, still scrolls itself inside it.
 */
@Composable
private fun MiseEnPageDeLEditeur(
    enTete: @Composable () -> Unit,
    pied: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    corps: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        // The height the editor has, once `imePadding` on [modifier] has taken the keyboard off.
        val hauteurVisible = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        Layout(
            contents = listOf(enTete, corps, pied),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) { (tete, milieu, bas), contraintes ->
            val largeur = contraintes.maxWidth
            val libres = Constraints(maxWidth = largeur)
            val placesDeTete = tete.map { it.measure(libres) }
            val placesDuBas = bas.map { it.measure(libres) }
            val reste = hauteurVisible - placesDeTete.sumOf { it.height } - placesDuBas.sumOf { it.height }
            val hauteurDuCorps = maxOf(reste, HAUTEUR_MINIMALE_DU_CORPS.roundToPx())
            val placesDuCorps = milieu.map { it.measure(Constraints.fixed(largeur, hauteurDuCorps)) }
            val toutes = placesDeTete + placesDuCorps + placesDuBas
            layout(largeur, toutes.sumOf { it.height }) {
                var y = 0
                toutes.forEach { place ->
                    place.placeRelative(0, y)
                    y += place.height
                }
            }
        }
    }
}

/**
 * A note being read, drawn as a note (3.1.0, Patrice: "que la note ressemble à une note" — the
 * "Fiche" he chose): a card on the screen, in the note's own colour — the pastel background and the
 * border of its hue, 2 dp — or the plain card and a grey border without one; its title at the top, a
 * thin rule, the text, and the date of its last change at the bottom.
 *
 * ⚠️ The text keeps the colours whose contrast `CouleursDeNoteTest` measures on every background:
 * `onSurface`, `onSurfaceVariant`, and the links' `primary`. A vault note's colour shows here: reading
 * it means its vault is open.
 *
 * 🔴 Lazy, as the preview was — see `apercuMarkdown`: a note has no size limit.
 */
@Composable
private fun FicheDeLecture(
    state: EditorUiState,
    rendu: LectureDeLApercu,
    defilement: LazyListState,
    onLien: (LinkTarget) -> Unit,
) {
    val schema = MaterialTheme.colorScheme
    val teinte = state.note?.color?.let { CouleursDeNote.teinte(it, schema) }
    val bord = teinte?.bord ?: schema.outlineVariant
    val formate = rememberNoteDateFormatter()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = teinte?.fond ?: schema.surfaceContainerLow,
        border = BorderStroke(2.dp, bord),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            // The title the note has; an empty one says so, in italics, as the cards do.
            val sansTitre = state.titre.text.isEmpty()
            Text(
                text = if (sansTitre) stringResource(R.string.note_untitled) else state.titre.text,
                style = MaterialTheme.typography.headlineSmall,
                fontStyle = if (sansTitre) FontStyle.Italic else null,
                color = if (sansTitre) schema.onSurfaceVariant else schema.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)
                    .semantics { heading() },
            )
            HorizontalDivider(color = bord.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = defilement,
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            ) {
                apercuMarkdown(rendu, onLien)
            }
            state.note?.updatedAt?.let { modifiee ->
                Text(
                    text = stringResource(R.string.note_info_modified) + " " + formate(modifiee),
                    style = MaterialTheme.typography.labelMedium,
                    color = schema.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** About three lines of text under the body field's label. */
internal val HAUTEUR_MINIMALE_DU_CORPS = 120.dp

/** An extended FAB (56 dp) and its margin: what the reading leaves free at the bottom right. */
private val HAUTEUR_DU_BOUTON_MODIFIER = 80.dp

/**
 * What the editor shows above the note's body: the save failure, and the title — a field while
 * writing, a heading while reading (3.1.0; until then, the field and an Edit / Preview switch in
 * both). It stays on screen whenever the window has room for the body under it — see
 * [MiseEnPageDeLEditeur].
 */
@Composable
private fun EnTeteDeLEditeur(state: EditorUiState, onTitreChange: (TextFieldValue) -> Unit, lecture: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (state.saveFailed) BanniereEchecEnregistrement(state.saveFailureReason)
        // While reading, the title is on the note's card itself — see [FicheDeLecture].
        if (lecture) return@Column
        // 🔴🔴 **`label` et non `placeholder` : les deux champs n'avaient AUCUN nom
        // accessible dès qu'ils portaient du texte.**
        //
        // Un `placeholder` de Material3 disparaît à la première lettre, et le nom
        // accessible du champ redevient alors son seul contenu. Un lecteur d'écran
        // annonçait donc, sur une note ouverte, deux zones de saisie **anonymes** : le
        // titre lu comme du texte, puis la note entière lue comme du texte, sans que rien
        // ne dise laquelle est laquelle ni ce qu'on est censé y écrire. Sur une note
        // **vide** le défaut était invisible — le placeholder est là, il nomme le champ,
        // et c'est l'état sous lequel l'écran a toujours été relu.
        //
        // L'application publiée porte `labelText` sur les deux (`note_editor_screen.dart`
        // :1072 et :1102), en plus de son `hintText`. Un `label` Material3 fait la même
        // chose : il flotte au-dessus du champ rempli, donc il **reste** annoncé.
        //
        // ⚠️ **Le balayage `actionnablesSansNom` ne pouvait pas le voir** : il exclut
        // délibérément les nœuds qui portent un `EditableText`, au motif qu'un champ vide
        // n'est pas un défaut d'étiquetage. C'est juste, et ça laissait un motif entier
        // hors de portée — d'où `champsDeSaisieSansNom`, le troisième instrument.
        //
        // ⚠️ Pas de `placeholder` sur le titre : il vaudrait la même chaîne que le
        // `label`, et Material3 les affiche **tous les deux** sur un champ vide et
        // focalisé. Le contenu, lui, garde le sien — `note_editor_content_hint` dit
        // `[[Titre]] pour lier`, ce que son libellé ne dit pas.
        TextField(
            value = state.titre,
            // 🔴 **Le titre est plafonné À LA SAISIE, comme dans l'application publiée**
            // (`LengthLimitingTextInputFormatter(AppConstants.noteTitleMaxLength)`).
            //
            // Sans ce plafond, coller un paragraphe dans le titre faisait échouer
            // **chaque** enregistrement de la note — `saveEdits` refuse au-delà de
            // [NotesRepository.TITLE_MAX_LENGTH], et il refuse le titre **et le corps
            // ensemble**, puisque c'est un seul appel. Le texte tapé ensuite n'était donc
            // écrit nulle part. La bannière le dit tant qu'on est sur l'écran ; quitter
            // l'emportait en silence, l'enregistrement au départ échouant lui aussi.
            //
            // ⚠️ La règle elle-même vit dans [PlafondDuTitre], à part et testée sur la
            // JVM : ce qui peut s'y tromper est un rapport de **longueurs**, et une table
            // de cas le dit mieux qu'un écran. Elle rend `null` pour une saisie à
            // ignorer — un titre déjà au plafond n'accepte plus rien, plutôt que de se
            // faire manger la fin à chaque frappe.
            onValueChange = { nouveau ->
                val texte = PlafondDuTitre.applique(state.title, nouveau.text)
                when {
                    texte == null -> Unit
                    // Accepted as typed: the value goes whole — selection and composition with it.
                    texte == nouveau.text -> onTitreChange(nouveau)
                    // Cut down to the ceiling: the cursor goes to its end.
                    else -> onTitreChange(TextFieldValue(texte, TextRange(texte.length)))
                }
            },
            label = { Text(stringResource(R.string.note_editor_title)) },
            textStyle = MaterialTheme.typography.headlineSmall,
            singleLine = true,
            colors = champSansDecor(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The note's links, under its body in both modes — where 2.0.9 puts its `BacklinksPanel`.
 *
 * ⚠️ **Bounded, and scrolling itself**: a third of the screen at most. 2.0.9 does not bound it, so
 * a note citing a few hundred titles pushes its body out of the screen. Its own scroll is measured
 * with a finite height — `heightIn` bounds it first — even inside the column of
 * [MiseEnPageDeLEditeur], which scrolls in a short window: what crashed the panic mode's end screen
 * (§ of `LiensDeLaNote`) was a scroll measured with an infinite height, which this is not. A quarter
 * was tried first: two sections of 48 dp chips did not fit on the S9, and the mention was cut off.
 *
 * ⚠️ **Hidden while the keyboard is up.** Fixed under the body, it left the field a few lines to type
 * in on a phone with the keyboard open. Before the body scrolled itself, the panel sat at the end of
 * the note and was out of sight while typing: hiding it keeps that.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PiedDeLEditeur(liens: PanneauDeLiens, onOuvrirNote: (String) -> Unit, onLienFantome: (String) -> Unit) {
    if (WindowInsets.isImeVisible) return
    val hauteurMaximale = LocalConfiguration.current.screenHeightDp.dp / PART_D_ECRAN_DU_PANNEAU
    Column(Modifier.fillMaxWidth().heightIn(max = hauteurMaximale).verticalScroll(rememberScrollState())) {
        LiensDeLaNote(
            liens = liens,
            onOuvrirNote = onOuvrirNote,
            onLienFantome = onLienFantome,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** The links panel takes at most a third of the screen's height. */
private const val PART_D_ECRAN_DU_PANNEAU = 3

/**
 * L'avertissement qu'un enregistrement a échoué.
 *
 * 🔴 En tête du contenu, pas en bas : l'utilisateur doit le voir avant de continuer à taper du
 * texte qui ne sera pas conservé non plus.
 */
@Composable
private fun BanniereEchecEnregistrement(@StringRes raison: Int?) {
    val couleurs = MaterialTheme.colorScheme
    Surface(color = couleurs.errorContainer, modifier = Modifier.fillMaxWidth()) {
        // ⚠️ La raison REMPLACE le message générique quand elle est connue : les afficher tous les
        // deux ferait lire « Échec de sauvegarde. Titre trop long » — la première moitié n'apprend
        // rien que la seconde ne dise mieux.
        Text(
            text = stringResource(raison ?: R.string.note_editor_error_save_failed),
            style = MaterialTheme.typography.bodySmall,
            color = couleurs.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * La confirmation avant de sortir une note d'un coffre.
 *
 * ## 🔴 Ce que l'utilisateur doit comprendre avant de toucher au bouton
 *
 * Le contenu part en clair dans la base. Et le mot « irréversible » du corps de texte ne dit pas
 * qu'on ne peut pas remettre la note au coffre — on peut — mais qu'**elle aura transité hors
 * chiffrement** : ce qui a été écrit en clair au repos l'a été, et aucun geste ultérieur ne le
 * défait. C'est la formulation de l'application publiée, et elle est juste.
 *
 * ⚠️ **L'action de sortie est le bouton discret, en rouge**, et « Annuler » celui qu'on touche par
 * réflexe — même disposition que [com.filestech.notes_tech.ui.folders.ConfirmDeleteFolderDialog],
 * et même icône, pour la même raison : les deux retirent une protection.
 *
 * Ce dialogue est la seule chose qui sépare un tap dans un menu d'une note déprotégée. L'entrée de
 * menu, elle, n'est plus désactivée — c'était la protection tant que le dépôt ne savait pas
 * déchiffrer, et une entrée grisée n'explique rien.
 */
@Composable
private fun DialogueDeSortieDeCoffre(onConfirmer: () -> Unit, onAnnuler: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        icon = {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.note_editor_exit_vault_title)) },
        text = { CorpsDeDialogue(stringResource(R.string.note_editor_exit_vault_body)) },
        confirmButton = {
            ActionDeDialogue(
                texte = stringResource(R.string.note_editor_exit_vault_confirm),
                onClick = onConfirmer,
                couleur = MaterialTheme.colorScheme.error,
            )
        },
        dismissButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onAnnuler)
        },
    )
}

/** Deux champs de texte qui ne ressemblent pas à un formulaire. */
@Composable
private fun champSansDecor() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

/**
 * Ce qu'il faut faire de l'issue d'une action de menu : partager, quitter, ou annoncer.
 *
 * ⚠️ **Extrait de [NoteEditorRoute] parce que detekt a refusé la fonction**, arrivée au seuil de
 * complexité en gagnant une condition de plus. Le seuil n'est pas à relever : c'est le signal qui
 * dit qu'une fonction porte trop de décisions, et il a raison — cet effet-ci se lit seul.
 *
 * ⚠️ **Afficher PUIS consommer**, jamais l'inverse : un `LaunchedEffect` dont la clé change par son
 * propre effet s'annule, et le message ne s'afficherait jamais. Le partage part d'abord, le porteur
 * est vidé ensuite, et le message est posté sur une portée qui ne dépend pas de la clé.
 *
 * ⚠️ **Quatre branches faisaient l'inverse** — `onConsommer()` avant le `portee.launch` — et elles
 * fonctionnaient : la portée est celle du `remember`, donc l'annulation de cet effet-ci ne l'atteint
 * pas. C'est précisément ce qui rend la forme dangereuse : elle contredit la règle du dépôt **sans
 * rien casser**, et le jour où l'un de ces corps gagne un `await` avant la ligne d'affichage, le
 * message disparaît sans que rien n'ait l'air d'avoir changé. Relevé par une relecture externe
 * (Gemini, 2026-08-15) comme fragilité, pas comme défaut — et corrigé à ce titre.
 *
 * Les deux exceptions restantes n'affichent rien : la corbeille **navigue** (`onBack`), l'export
 * lance un sélecteur système. Consommer d'abord y est sans effet visible, et consommer après une
 * navigation toucherait un `ViewModel` dont l'écran est déjà parti.
 */
@Composable
internal fun IssueDUneAction(
    action: ActionDEditeur,
    contexte: Context,
    ressources: Resources,
    titreDuSelecteur: String,
    messages: SnackbarHostState,
    portee: CoroutineScope,
    onConsommer: () -> Unit,
    onBack: () -> Unit,
    onOuvrirNote: (String) -> Unit,
) {
    LaunchedEffect(action) {
        val export = action.export
        val erreur = action.erreur
        val aOuvrir = action.aOuvrir
        when {
            // ⚠️ Consumed BEFORE navigating: this editor stays in the back stack, and an outcome still
            // set when the user comes back would open the same note again — in a loop.
            aOuvrir != null -> {
                onConsommer()
                onOuvrirNote(aOuvrir)
            }

            export != null -> {
                partagerUnFichier(
                    context = contexte,
                    uri = export.uri,
                    mimeType = MIME_MARKDOWN,
                    sujet = export.fileName,
                    titreDuSelecteur = titreDuSelecteur,
                )
                onConsommer()
            }

            action.misAlaCorbeille -> {
                onConsommer()
                onBack()
            }

            action.deplacee -> {
                portee.launch { messages.showSnackbar(ressources.getString(R.string.note_editor_moved)) }
                onConsommer()
            }

            action.copiee -> {
                portee.launch { messages.showSnackbar(ressources.getString(R.string.note_editor_copied_to_clipboard)) }
                onConsommer()
            }

            // ⚠️ Le presse-papiers n'a PAS été touché : le dire, plutôt que laisser croire à une
            // copie vide réussie. Un geste sans effet se signale.
            action.copieVide -> {
                portee.launch { messages.showSnackbar(ressources.getString(R.string.note_editor_copy_empty)) }
                onConsommer()
            }

            erreur != null -> {
                val phrase = ressources.getString(erreur)
                // ⚠️ Exhaustive, `null` included: a new origin must decide how it is announced.
                // Export and move keep their own framing, and so does creating a linked note —
                // where notes_tech 2.0.9 says "Vault creation failed" (`note_editor_screen.dart:914`),
                // of any note, in a vault or not; trash and copy show the sentence alone — the
                // "Error: …" frame they used, `common_error_with`, left with 2.0.9 together with the
                // raw text it wrapped.
                val message = when (action.origine) {
                    ActionDEditeur.OrigineDErreur.EXPORT ->
                        ressources.getString(R.string.note_editor_export_failed, phrase)
                    ActionDEditeur.OrigineDErreur.DEPLACEMENT ->
                        ressources.getString(R.string.note_editor_move_failed, phrase)
                    ActionDEditeur.OrigineDErreur.CREATION ->
                        ressources.getString(R.string.note_editor_link_create_failed, phrase)
                    ActionDEditeur.OrigineDErreur.CORBEILLE,
                    ActionDEditeur.OrigineDErreur.COPIE,
                    null,
                    -> phrase
                }
                portee.launch { messages.showSnackbar(message) }
                onConsommer()
            }
        }
    }
}

/**
 * Annonce « Note enregistrée » au lecteur d'écran, **au plus une fois toutes les cinq secondes**.
 *
 * ⚠️ Le frein n'est pas du confort : l'enregistrement est différé de 500 ms, donc une frappe continue
 * en déclenche un toutes les secondes et demie environ. Sans limite, un lecteur d'écran ne dirait
 * plus que ça, et couvrirait le texte que l'utilisateur est en train d'écrire. Valeur reprise de
 * l'application publiée (`note_editor_screen.dart:58`).
 *
 * ⚠️ **Rien n'est annoncé quand l'enregistrement a échoué** : une bannière le dit à l'écran, et
 * annoncer une réussite par-dessus serait le pire des deux mondes.
 *
 * ⚠️ `elapsedRealtime` et non `currentTimeMillis` : un changement d'heure système ne doit pas
 * rouvrir la fenêtre — ni la fermer pour des heures.
 */
@Composable
private fun AnnonceDEnregistrement(enregistrement: Boolean, echec: Boolean) {
    val vue = LocalView.current
    val texte = stringResource(R.string.note_editor_announce_saved_success)
    var derniereAnnonce by rememberSaveable { mutableLongStateOf(0L) }
    var enregistrementPrecedent by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(enregistrement) {
        val vientDeFinir = enregistrementPrecedent && !enregistrement
        enregistrementPrecedent = enregistrement
        if (!vientDeFinir || echec) return@LaunchedEffect

        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - derniereAnnonce < DELAI_ENTRE_ANNONCES_MS) return@LaunchedEffect
        derniereAnnonce = maintenant
        @Suppress("DEPRECATION")
        vue.announceForAccessibility(texte)
    }
}

/** Cinq secondes, comme l'application publiée. */
private const val DELAI_ENTRE_ANNONCES_MS = 5_000L

/**
 * Le nom du dossier, et **l'état de l'enregistrement**.
 *
 * ## 🔴 Le portage n'affichait RIEN sur l'enregistrement
 *
 * Ni pendant, ni après : seule une bannière apparaissait en cas d'échec. `note_editor_saving` et
 * `note_editor_saved` étaient traduites des deux côtés et lues nulle part. Or l'enregistrement est
 * **différé** ici — quitter l'écran juste après avoir tapé demande de savoir si le texte est parti.
 * L'absence de retour laissait ce doute entier.
 *
 * ⚠️ **Le dossier est CONSERVÉ.** L'application publiée met l'indicateur à la place du titre et
 * n'affiche donc le dossier nulle part (`note_editor_screen.dart:945`). Le portage l'affichait :
 * le remplacer aurait retiré une information pour en ajouter une autre. Les deux tiennent.
 *
 * ⚠️⚠️ **PAS de `liveRegion` ici**, contrairement au premier jet. Une région active annonce chaque
 * changement : l'enregistrement étant différé de 500 ms, une frappe continue fait basculer
 * « Enregistrement… ⇄ Enregistré » toutes les secondes et demie environ, et **sature le lecteur
 * d'écran**. L'annonce est donc explicite et **limitée à une toutes les cinq secondes**, dans
 * [NoteEditorRoute] — c'est le mécanisme de l'application publiée, et le commentaire qui l'accompagne
 * là-bas dit exactement pourquoi.
 *
 * L'indicateur visuel, lui, reste exact à tout instant : c'est l'annonce qui est freinée, pas
 * l'affichage.
 */
@Composable
private fun TitreDeLEditeur(dossier: String, enregistrement: Boolean, echec: Boolean) {
    val etat = stringResource(if (enregistrement) R.string.note_editor_saving else R.string.note_editor_saved)

    Column {
        Text(
            text = dossier,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
        )
        // 🔴 **Rien du tout quand l'enregistrement a ÉCHOUÉ.**
        //
        // `saving` retombe à `false` sur un échec comme sur une réussite : la ligne affichait donc
        // « Enregistré », avec sa coche, **au-dessus de la bannière rouge qui dit le contraire**.
        // Deux affirmations opposées sur le même écran, et c'est la rassurante qui est fausse.
        // Relevé CONFIRMÉ par une relecture externe (Gemini, 2026-08-15).
        //
        // La bannière porte déjà le message ; cette ligne se tait plutôt que d'en ajouter un second.
        if (echec) return@Column

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (enregistrement) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            } else {
                Icon(
                    imageVector = Icons.Outlined.CloudDone,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = etat,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * Les actions moins fréquentes de l'éditeur.
 *
 * ⚠️ **La corbeille est ici, pas en icône.** C'est la disposition de l'application publiée, et elle
 * est meilleure : une icône de suppression à côté de l'épingle et du favori s'atteint par erreur, et
 * ce geste-là part sans confirmation.
 *
 * ⚠️ **« Déplacer » est active même pour une note de coffre**, et ne l'était pas tant que le dépôt
 * ne savait pas déchiffrer. Ce qui protège l'utilisateur n'est pas une entrée grisée mais la
 * confirmation que l'écran pose avant de sortir une note d'un coffre — cf. [DialogueDeSortieDeCoffre].
 */
@Composable
private fun MenuDeDebordement(
    epinglee: Boolean,
    favorite: Boolean,
    onEpingler: () -> Unit,
    onFavori: () -> Unit,
    onInfos: () -> Unit,
    onDeplacer: () -> Unit,
    onCouleur: () -> Unit,
    onExporter: () -> Unit,
    onCopier: () -> Unit,
    onCorbeille: () -> Unit,
) {
    var ouvert by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = { ouvert = true }) {
        Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = stringResource(R.string.note_editor_tooltip_more),
        )
    }
    DropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
        // 🔴🔴 **Épingle et favori sont ICI, et non dans la barre d'actions.**
        //
        // Ils y étaient. Mesuré sur le S9 après l'ajout du micro : six actions dans la barre
        // écrasent le titre **à 24 pixels** — le nom du dossier et l'état de l'enregistrement
        // réduits à des points de suspension. C'est le piège que ce fichier avait DÉJÀ payé une
        // fois, quand « Terminé » était un bouton libellé ; le commentaire qui le raconte est
        // quelques lignes plus haut, et il n'a pas suffi à m'empêcher de le rouvrir.
        //
        // ⚠️ Ce sont ces deux-là qui partent, et pas le micro ni le lien : les seconds sont des
        // gestes d'**écriture**, faits pendant qu'on compose ; épingler et mettre en favori sont
        // des gestes sur la **fiche** de la note, occasionnels, et ils voisinent naturellement avec
        // déplacer et exporter.
        //
        // ⚠️ Écart assumé avec l'application publiée, qui les garde dans la barre. Elle peut se le
        // permettre parce que son titre EST l'indicateur d'enregistrement, sur une seule ligne
        // étroite ; ici il en porte deux, dont le nom du dossier qu'elle n'affiche nulle part.
        //
        // ⚠️ Le libellé suit l'état — `home_unpin` / `home_unfav` — comme le faisait la description
        // du bouton d'icône. Un menu qui propose « Épingler » sur une note déjà épinglée annonce
        // l'inverse de ce qu'il va faire.
        DropdownMenuItem(
            text = {
                Text(stringResource(if (epinglee) R.string.home_unpin else R.string.note_editor_tooltip_pin))
            },
            leadingIcon = {
                Icon(if (epinglee) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = null)
            },
            onClick = {
                ouvert = false
                onEpingler()
            },
        )
        DropdownMenuItem(
            text = {
                Text(stringResource(if (favorite) R.string.home_unfav else R.string.note_editor_tooltip_fav))
            },
            leadingIcon = {
                Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null)
            },
            onClick = {
                ouvert = false
                onFavori()
            },
        )
        // The colour is about the note's card in the lists, as pin and favourite are (3.1.0).
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_color)) },
            leadingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
            onClick = {
                ouvert = false
                onCouleur()
            },
        )
        // The info panel sits with pin and favourite: all three are about the note's record card,
        // not about its text — reading the dates or the word count is not an editing gesture.
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_info)) },
            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            onClick = {
                ouvert = false
                onInfos()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_move)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
            onClick = {
                ouvert = false
                onDeplacer()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_export)) },
            leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
            onClick = {
                ouvert = false
                onExporter()
            },
        )
        // Entre l'export et la corbeille, comme dans l'application publiée
        // (`note_editor_screen.dart:1035`) : les deux gestes qui sortent le texte de l'application
        // se suivent, et le geste destructif reste seul en bas.
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_copy_markdown)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            onClick = {
                ouvert = false
                onCopier()
            },
        )
        // In red, as every gesture that removes a note (Patrice, 2026-10-10): `error`, the theme's own red,
        // readable on both menus' surfaces.
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_trash), color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            onClick = {
                ouvert = false
                onCorbeille()
            },
        )
    }
}
