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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.EmptyState
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.vault.UnlockVaultSheet

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
                        IconButton(
                            onClick = {
                                viewModel.moveToTrash()
                                onBack()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.common_delete),
                            )
                        }
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
                        onLienFantome = { /* la création arrive avec l'autocomplétion */ },
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
