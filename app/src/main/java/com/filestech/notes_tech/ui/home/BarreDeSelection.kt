package com.filestech.notes_tech.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Note

/**
 * The top bar of a list that selects (3.3.0) — it replaces the list's own bar, as in every Android app
 * that selects: the count, and what can be done to the selection.
 *
 * ## Three actions in the bar, the rest in ⋮
 *
 * Archive, move and trash are the gestures of issue #48, and those of a tidy-up: in the bar, one tap.
 * The others — edit, pin, favourite, colour — and the one that cannot be undone are in the menu, the
 * destructive one LAST and in red, as in the editor's ⋮ (Patrice, 2026-10-10). Five icons would crowd
 * the count off the bar, as the editor's bar once was (see `MenuDeDebordement`).
 *
 * ⚠️ **Labels that follow the state**: "Unpin" when every selected note is pinned, "Pin" otherwise —
 * a mixed selection is pinned, all of it, as the user can then see. Same for the favourite; and
 * "Unarchive" in the archive's view, where nothing is not archived.
 */
@Composable
internal fun BarreDeSelection(
    selection: List<Note>,
    archive: Boolean,
    toutEstSelectionne: Boolean,
    actions: ActionsDeSelection,
) {
    var menuOuvert by rememberSaveable { mutableStateOf(false) }
    val toutesEpinglees = selection.all { it.pinned }
    val toutesFavorites = selection.all { it.favorite }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        navigationIcon = {
            IconButton(onClick = actions.onFermer) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_close))
            }
        },
        title = {
            Text(
                text = pluralStringResource(R.plurals.selection_count, selection.size, selection.size),
                modifier = Modifier.semantics { heading() },
            )
        },
        actions = {
            BoutonDeSelection(
                icone = if (archive) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                libelle = stringResource(if (archive) R.string.note_unarchive else R.string.note_archive),
                onClick = actions.onArchiver,
            )
            BoutonDeSelection(
                icone = Icons.AutoMirrored.Outlined.DriveFileMove,
                libelle = stringResource(R.string.note_editor_menu_move),
                onClick = actions.onDeplacer,
            )
            BoutonDeSelection(
                icone = Icons.Outlined.DeleteOutline,
                libelle = stringResource(R.string.note_editor_menu_trash),
                onClick = actions.onCorbeille,
            )
            IconButton(onClick = { menuOuvert = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more_options))
            }
            DropdownMenu(expanded = menuOuvert, onDismissRequest = { menuOuvert = false }) {
                val choisir: (() -> Unit) -> () -> Unit = { action ->
                    {
                        menuOuvert = false
                        action()
                    }
                }
                if (!toutEstSelectionne) {
                    EntreeDuMenu(
                        Icons.Outlined.SelectAll,
                        stringResource(R.string.selection_select_all),
                        choisir(actions.onToutSelectionner),
                    )
                }
                if (selection.size == 1) {
                    EntreeDuMenu(
                        Icons.Outlined.Edit,
                        stringResource(R.string.note_editor_mode_edit),
                        choisir(actions.onEditer),
                    )
                }
                EntreeDuMenu(
                    icone = if (toutesEpinglees) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    libelle = stringResource(if (toutesEpinglees) R.string.home_unpin else R.string.selection_pin),
                    onClick = choisir { actions.onEpingler(!toutesEpinglees) },
                )
                EntreeDuMenu(
                    icone = if (toutesFavorites) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    libelle = stringResource(
                        if (toutesFavorites) R.string.home_unfav else R.string.note_editor_tooltip_fav,
                    ),
                    onClick = choisir { actions.onFavori(!toutesFavorites) },
                )
                EntreeDuMenu(
                    Icons.Outlined.Palette,
                    stringResource(R.string.note_editor_menu_color),
                    choisir(actions.onCouleur),
                )
                EntreeDuMenu(
                    icone = Icons.Outlined.DeleteForever,
                    libelle = stringResource(R.string.trash_delete_forever),
                    onClick = choisir(actions.onSupprimerDefinitivement),
                    destructive = true,
                )
            }
        },
    )
}

/** An icon button named for TalkBack by its action — the bar has no text but the count. */
@Composable
private fun BoutonDeSelection(icone: ImageVector, libelle: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icone, contentDescription = libelle)
    }
}

@Composable
private fun EntreeDuMenu(icone: ImageVector, libelle: String, onClick: () -> Unit, destructive: Boolean = false) {
    val couleur = if (destructive) MaterialTheme.colorScheme.error else null
    DropdownMenuItem(
        text = { if (couleur != null) Text(libelle, color = couleur) else Text(libelle) },
        leadingIcon = {
            if (couleur !=
                null
            ) {
                Icon(icone, contentDescription = null, tint = couleur)
            } else {
                Icon(icone, contentDescription = null)
            }
        },
        onClick = onClick,
    )
}
