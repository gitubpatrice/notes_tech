package com.filestech.notes_tech.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.EmptyState
import com.filestech.notes_tech.ui.common.MIME_MARKDOWN
import com.filestech.notes_tech.ui.common.partagerUnFichier
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.vault.UnlockVaultSheet
import kotlinx.coroutines.launch

/**
 * L'éditeur d'une note.
 *
 * Le titre et le contenu sont deux champs sans décoration, pour que la note ressemble à du texte et
 * non à un formulaire — c'est la mise en page de la version publiée.
 */
@Composable
fun NoteEditorRoute(onBack: () -> Unit, onOpenNote: (String) -> Unit) {
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

    var autocompletionOuverte by rememberSaveable { mutableStateOf(false) }
    val suggestions by viewModel.suggestionsDeLien.collectAsStateWithLifecycle()

    // ⚠️ Fermer remet la recherche à zéro. Sans cela, rouvrir la feuille afficherait les résultats
    // de la fois précédente le temps du freinage — et l'utilisateur pourrait taper sur l'un d'eux.
    val fermerLAutocompletion = {
        autocompletionOuverte = false
        viewModel.reinitialiserLaRecherche()
    }

    var deplacementOuvert by rememberSaveable { mutableStateOf(false) }
    val dossiers by viewModel.dossiers.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }
    val contexte = LocalContext.current
    val ressources = LocalResources.current
    val portee = rememberCoroutineScope()

    // ⚠️ **Une fonction, pas un gabarit pré-formaté.** Passer « %s » puis formater casserait en
    // silence le jour où la chaîne traduite gagne un paramètre — relevé par l'audit i18n du
    // 2026-08-14 sur l'export des réglages, et la même forme est reprise ici.
    val mentionDeCoffre: (String) -> String = { nom -> ressources.getString(R.string.export_note_from_vault, nom) }
    val titreDuSelecteur = stringResource(R.string.common_share)

    // ⚠️ **Afficher PUIS consommer**, jamais l'inverse : un `LaunchedEffect` dont la clé change par
    // son propre effet s'annule, et le message ne s'afficherait jamais. Le partage part d'abord, le
    // porteur est vidé ensuite, et le message est posté sur une portée qui ne dépend pas de la clé.
    LaunchedEffect(action) {
        val export = action.export
        val erreur = action.erreur
        when {
            export != null -> {
                partagerUnFichier(
                    context = contexte,
                    uri = export.uri,
                    mimeType = MIME_MARKDOWN,
                    sujet = export.fileName,
                    titreDuSelecteur = titreDuSelecteur,
                )
                viewModel.consommerLAction()
            }

            action.misAlaCorbeille -> {
                viewModel.consommerLAction()
                onBack()
            }

            action.deplacee -> {
                viewModel.consommerLAction()
                portee.launch { messages.showSnackbar(ressources.getString(R.string.note_editor_moved)) }
            }

            erreur != null -> {
                val gabarit = when (action.origine) {
                    ActionDEditeur.OrigineDErreur.EXPORT -> R.string.note_editor_export_failed
                    ActionDEditeur.OrigineDErreur.DEPLACEMENT -> R.string.note_editor_move_failed
                    // Création et corbeille n'ont pas de phrase dédiée : « Erreur : … » dit ce
                    // qu'il faut sans inventer une chaîne qui n'existe dans aucune des deux langues.
                    else -> R.string.common_error_with
                }
                viewModel.consommerLAction()
                portee.launch { messages.showSnackbar(ressources.getString(gabarit, erreur)) }
            }
        }
    }

    if (deplacementOuvert) {
        FeuilleDeDeplacement(
            dossiers = dossiers,
            dossierActuel = state.note?.folderId,
            onChoisir = { cible ->
                deplacementOuvert = false
                viewModel.deplacerVers(cible)
            },
            onDismiss = { deplacementOuvert = false },
        )
    }

    if (autocompletionOuverte) {
        FeuilleDAutocompletion(
            suggestions = suggestions,
            onRequeteChange = viewModel::chercherUnTitre,
            onChoisirUnTitre = { titre ->
                viewModel.insererUnLien(titre)
                fermerLAutocompletion()
            },
            onCreer = { titre ->
                viewModel.creerPuisLier(titre)
                fermerLAutocompletion()
            },
            onDismiss = fermerLAutocompletion,
        )
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
    BackHandler {
        viewModel.saveNow()
        onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(messages) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.saveNow()
                            onBack()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_close),
                        )
                    }
                },
                title = {
                    Text(
                        text = state.folder?.name.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    val note = state.note
                    if (note != null && state.lockedVault == null) {
                        IconButton(onClick = { viewModel.setPinned(!note.pinned) }) {
                            Icon(
                                imageVector = if (note.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = stringResource(R.string.note_editor_tooltip_pin),
                            )
                        }
                        IconButton(onClick = { viewModel.setFavorite(!note.favorite) }) {
                            Icon(
                                imageVector = if (note.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(R.string.note_editor_tooltip_fav),
                            )
                        }
                        // ⚠️ « Insérer un lien » est un bouton d'icône, **pas** une entrée de menu :
                        // c'est le geste d'écriture le plus fréquent de cet écran, et l'application
                        // publiée le place au même endroit, à côté de l'épingle et du favori.
                        IconButton(onClick = { autocompletionOuverte = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = stringResource(R.string.note_editor_tooltip_insert_link),
                            )
                        }
                        MenuDeDebordement(
                            deplacementPossible = !state.isVaultNote,
                            onDeplacer = { deplacementOuvert = true },
                            onExporter = { viewModel.exporterLaNote(mentionDeCoffre) },
                            // ⚠️ Pas de `onBack()` ici : la navigation part quand la suppression a
                            // REUSSI, depuis l'observation de `action` ci-dessus. Quitter tout de
                            // suite laissait croire à une note supprimée qui ne l'était pas.
                            onCorbeille = viewModel::moveToTrash,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.notFound -> EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.note_editor_error_not_found),
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

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                ) {
                    if (state.saveFailed) BanniereEchecEnregistrement()
                    TextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChange,
                        placeholder = { Text(stringResource(R.string.note_editor_title)) },
                        textStyle = MaterialTheme.typography.headlineSmall,
                        singleLine = true,
                        colors = champSansDecor(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = state.content,
                        onValueChange = viewModel::onContentChange,
                        placeholder = { Text(stringResource(R.string.note_editor_content_hint)) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = champSansDecor(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // ⚠️ Le panneau est DANS la colonne défilante : il ne doit donc porter aucun
                    // défilement propre. Cf. son KDoc — c'est la configuration qui a fait planter
                    // l'écran de fin du mode panique.
                    LiensDeLaNote(
                        liens = liens,
                        onOuvrirNote = ouvrirUneAutreNote,
                        // ⚠️ Un lien fantôme désigne une note annoncée et pas encore écrite :
                        // l'appuyer la crée, avec le titre du lien. Le texte de la note, lui, ne
                        // bouge pas — le `[[Titre]]` y est déjà, et c'est l'indexation qui
                        // rattachera le lien à sa cible une fois la note née.
                        onLienFantome = viewModel::creerLaNoteManquante,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
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
 * L'avertissement qu'un enregistrement a échoué.
 *
 * 🔴 En tête du contenu, pas en bas : l'utilisateur doit le voir avant de continuer à taper du
 * texte qui ne sera pas conservé non plus.
 */
@Composable
private fun BanniereEchecEnregistrement() {
    val couleurs = MaterialTheme.colorScheme
    Surface(color = couleurs.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.note_editor_error_save_failed),
            style = MaterialTheme.typography.bodySmall,
            color = couleurs.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
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
 * Les actions moins fréquentes de l'éditeur.
 *
 * ⚠️ **La corbeille est ici, pas en icône.** C'est la disposition de l'application publiée, et elle
 * est meilleure : une icône de suppression à côté de l'épingle et du favori s'atteint par erreur, et
 * ce geste-là part sans confirmation.
 *
 * ⚠️ [deplacementPossible] est faux pour une note de coffre. Sortir une note d'un coffre écrit son
 * contenu en clair dans la base — irréversible au sens qui compte, la note ayant transité hors
 * chiffrement — et l'application publiée fait précéder ce geste d'une confirmation dédiée
 * (`note_editor_exit_vault_*`). Ni cette confirmation ni l'opération de dépôt qui la suit n'existent
 * encore : l'entrée est donc **désactivée**, plutôt que de mener à une exception ou à un dialogue
 * sans effet.
 */
@Composable
private fun MenuDeDebordement(
    deplacementPossible: Boolean,
    onDeplacer: () -> Unit,
    onExporter: () -> Unit,
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
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_move)) },
            enabled = deplacementPossible,
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
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_trash)) },
            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            onClick = {
                ouvert = false
                onCorbeille()
            },
        )
    }
}
