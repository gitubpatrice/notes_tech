package com.filestech.notes_tech.ui.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder

/** Ce qu'on peut faire d'un dossier depuis le tiroir. */
enum class FolderAction { RENAME, CONVERT_TO_VAULT, LOCK_NOW, REMOVE_VAULT_PROTECTION, DELETE }

/** Les deux façons de supprimer un dossier. */
enum class FolderDeletionChoice { MOVE_TO_INBOX, DELETE_EVERYTHING }

/**
 * Le menu d'un dossier.
 *
 * Les entrées de coffre s'excluent : un dossier ordinaire propose la conversion, un coffre ouvert
 * propose de le refermer, un coffre en général propose le retrait de protection. Les afficher
 * toutes ferait proposer « verrouiller maintenant » sur un dossier qui ne l'est pas.
 */
@Composable
fun FolderActionSheet(folder: Folder, unlocked: Boolean, onDismiss: () -> Unit, onAction: (FolderAction) -> Unit) {
    val couleurs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(bottom = 12.dp).navigationBarsPadding()) {
            EntreeDeMenu(
                icon = Icons.Outlined.DriveFileRenameOutline,
                title = stringResource(R.string.common_rename),
                onClick = { onAction(FolderAction.RENAME) },
            )
            if (!folder.isVault) {
                EntreeDeMenu(
                    icon = Icons.Outlined.Lock,
                    tint = couleurs.error,
                    title = stringResource(R.string.drawer_convert_to_vault),
                    subtitle = stringResource(R.string.drawer_convert_to_vault_subtitle),
                    onClick = { onAction(FolderAction.CONVERT_TO_VAULT) },
                )
            } else if (unlocked) {
                EntreeDeMenu(
                    icon = Icons.Outlined.Lock,
                    tint = couleurs.error,
                    title = stringResource(R.string.drawer_lock_now),
                    subtitle = stringResource(R.string.drawer_lock_now_subtitle),
                    onClick = { onAction(FolderAction.LOCK_NOW) },
                )
            }
            if (folder.isVault) {
                EntreeDeMenu(
                    icon = Icons.Outlined.LockOpen,
                    tint = couleurs.error,
                    title = stringResource(R.string.drawer_remove_vault_protection),
                    subtitle = stringResource(R.string.drawer_remove_vault_protection_subtitle),
                    onClick = { onAction(FolderAction.REMOVE_VAULT_PROTECTION) },
                )
            }
            EntreeDeMenu(
                icon = Icons.Outlined.DeleteOutline,
                tint = couleurs.error,
                title = stringResource(R.string.common_delete),
                onClick = { onAction(FolderAction.DELETE) },
            )
        }
    }
}

/** Le dialogue de nom, pour la création comme pour le renommage. */
@Composable
fun FolderNameDialog(
    title: String,
    fieldLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var nom by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = nom,
                onValueChange = { nom = it },
                label = { Text(fieldLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            // Un nom vide est refusé par le dépôt ; le bouton l'anticipe pour que le refus ne se
            // manifeste pas par un message d'erreur après coup.
            TextButton(onClick = { onConfirm(nom) }, enabled = nom.isNotBlank()) {
                Text(stringResource(R.string.common_validate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * La confirmation de suppression d'un dossier.
 *
 * ## 🔴 Pour un coffre, l'option « déplacer » est la plus destructrice pour la confidentialité
 *
 * Elle déchiffre toutes les notes et les écrit en clair. Le dialogue de l'application publiée
 * demandait « Que faire des notes de X ? » **sans le dire**, et présentait ce choix comme l'option
 * sûre. Sortir UNE note d'un coffre était confirmé explicitement depuis la v1.1.0 ; en vider un
 * entier ne l'était pas — jumeau asymétrique, et du mauvais côté.
 *
 * D'où le texte distinct de [R.string.folder_delete_vault_choice_body] et l'intitulé
 * « Déchiffrer et déplacer », qui nomme ce qui va se passer.
 */
@Composable
fun ConfirmDeleteFolderDialog(folder: Folder, onDismiss: () -> Unit, onChoice: (FolderDeletionChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (folder.isVault) Icons.Outlined.LockOpen else Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = if (folder.isVault) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        title = { Text(stringResource(R.string.folder_delete_title)) },
        text = {
            Text(
                if (folder.isVault) {
                    stringResource(R.string.folder_delete_vault_choice_body, folder.name)
                } else {
                    stringResource(R.string.folder_delete_choice_body, folder.name)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onChoice(FolderDeletionChoice.MOVE_TO_INBOX) }) {
                Text(
                    stringResource(
                        if (folder.isVault) {
                            R.string.folder_delete_move_to_inbox_vault
                        } else {
                            R.string.folder_delete_move_to_inbox
                        },
                    ),
                )
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                // L'action irréversible est un bouton discret, en rouge, qu'il faut viser. Elle ne
                // doit jamais être celle qu'on touche par réflexe.
                TextButton(onClick = { onChoice(FolderDeletionChoice.DELETE_EVERYTHING) }) {
                    Text(
                        text = stringResource(R.string.folder_delete_permanent),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
private fun EntreeDeMenu(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint ?: androidx.compose.material3.LocalContentColor.current,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clickable(onClick = onClick),
    )
}
