package com.filestech.notes_tech.ui.folders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder

/**
 * Le tiroir des dossiers.
 *
 * Portage de `ui/widgets/folders_drawer.dart`. Deux choix de l'original sont conservés parce qu'ils
 * ne sont pas décoratifs :
 *
 * - **Le cadenas rouge d'un coffre**, au lieu de l'icône de dossier. Le même signal que le liseré
 *   d'une note verrouillée : reconnaissable sans lire.
 * - **Le bouton `⋮` explicite** à côté de chaque dossier. L'appui long fait la même chose, mais
 *   personne ne le découvre — et sans lui, renommer, protéger ou supprimer un dossier n'existe pas
 *   pour l'utilisateur.
 */
@Composable
fun FoldersDrawer(
    state: FoldersUiState,
    currentFolderId: String?,
    onSelect: (String?) -> Unit,
    onOpenTrash: () -> Unit,
    onCreateFolder: () -> Unit,
    onFolderMenu: (Folder) -> Unit,
    /**
     * 🔴 **Renommer la boîte de réception.**
     *
     * Elle n'avait **aucun** chemin de renommage : ni bouton, ni geste. L'application publiée le
     * permet par appui long (`folders_drawer.dart:324`), un geste que ce portage n'a nulle part —
     * et qui, de l'aveu même du commentaire d'à côté dans le publié, *« n'est pas découvrable »*.
     * D'où un bouton visible, comme pour les autres dossiers.
     *
     * ⚠️ **Renommer seulement.** Le menu complet proposerait « Supprimer », que le dépôt refuse
     * (`require(id != INBOX_ID)`), et une conversion en coffre que l'application publiée n'offre pas
     * sur ce dossier-là. Une entrée qui échoue à coup sûr est pire que pas d'entrée.
     *
     * Manque trouvé par un relevé des **gestes** — appuis longs et balayages — que la comparaison
     * des chaînes ne pouvait pas révéler : un appui long n'a pas de texte.
     */
    onRenameInbox: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(modifier = modifier) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.home_folders),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp).semantics { heading() },
            )
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                EntreeDeTiroir(
                    icon = Icons.AutoMirrored.Outlined.Notes,
                    label = stringResource(R.string.home_all_notes),
                    selected = currentFolderId == null,
                    onClick = { onSelect(null) },
                )
            }
            item {
                EntreeDeTiroir(
                    icon = Icons.Outlined.Inbox,
                    // La boîte de réception peut manquer d'une base abîmée : son nom traduit sert
                    // alors de repli, plutôt qu'une ligne absente qui rendrait l'écran incohérent.
                    label = state.inbox?.name ?: stringResource(R.string.home_folder_inbox),
                    selected = currentFolderId == Folder.INBOX_ID,
                    onClick = { onSelect(Folder.INBOX_ID) },
                    // ⚠️ Pas de bouton si la boîte manque de la base : il n'y aurait rien à
                    // renommer, et le libellé affiché serait alors une traduction de repli.
                    trailing = state.inbox?.let { boite ->
                        {
                            IconButton(onClick = { onRenameInbox(boite) }) {
                                Icon(
                                    imageVector = Icons.Outlined.DriveFileRenameOutline,
                                    contentDescription = stringResource(R.string.common_rename),
                                )
                            }
                        }
                    },
                )
            }
            if (state.userFolders.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.drawer_header_folders),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.1.sp,
                        modifier = Modifier
                            .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 6.dp)
                            .semantics { heading() },
                    )
                }
                items(state.userFolders, key = { it.id }) { folder ->
                    EntreeDeTiroir(
                        icon = if (folder.isVault) Icons.Outlined.Lock else Icons.Outlined.Folder,
                        iconTint = if (folder.isVault) MaterialTheme.colorScheme.error else null,
                        label = folder.name,
                        selected = currentFolderId == folder.id,
                        onClick = { onSelect(folder.id) },
                        trailing = {
                            IconButton(onClick = { onFolderMenu(folder) }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.drawer_folder_options),
                                )
                            }
                        },
                    )
                }
            }
        }

        HorizontalDivider()
        EntreeDeTiroir(
            icon = Icons.Outlined.DeleteOutline,
            label = stringResource(R.string.drawer_trash),
            selected = false,
            onClick = onOpenTrash,
        )
        Column(modifier = Modifier.padding(12.dp)) {
            FilledTonalButton(onClick = onCreateFolder, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = Icons.Outlined.CreateNewFolder, contentDescription = null)
                Text(
                    text = stringResource(R.string.drawer_new_folder),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EntreeDeTiroir(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: androidx.compose.ui.graphics.Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint ?: androidx.compose.material3.LocalContentColor.current,
            )
        },
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        badge = trailing,
        modifier = modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
