package com.filestech.notes_tech.ui.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import com.filestech.notes_tech.ui.common.CorpsDeDialogue
import com.filestech.notes_tech.ui.common.EntreeDeMenu

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
                destructive = true,
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
            ActionDeDialogue(
                texte = stringResource(R.string.common_validate),
                onClick = { onConfirm(nom) },
                enabled = nom.isNotBlank(),
            )
        },
        dismissButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onDismiss)
        },
    )
}

/**
 * La confirmation avant de **retirer la protection d'un coffre entier**.
 *
 * ## 🔴 Ce dialogue n'existait pas, et son absence était une régression
 *
 * Ses trois chaînes (`folder_remove_vault_title/body/confirm`) étaient traduites dans les deux
 * langues et **référencées nulle part** — le signal exact qui avait déjà révélé le manque « sortir
 * une note d'un coffre ». L'application publiée, elle, pose bien cette question
 * (`folders_drawer.dart:545-571`) : le dialogue avait été oublié en portant, pas les mots.
 *
 * ⚠️ **C'est le geste le plus destructeur du portefeuille de coffres** : il déchiffre **toutes** les
 * notes du dossier et écrit leur clair au repos. Il partageait, dans un menu à cinq entrées, la même
 * teinte rouge que « Verrouiller maintenant » — deux entrées voisines, l'une qui protège, l'autre
 * qui déprotège définitivement, et aucune des deux ne demandait rien.
 *
 * Le jumeau de ce geste, `ConfirmDeleteFolderDialog` juste en dessous, avait reçu ce soin ; celui-ci
 * passe par un chemin différent (`FolderAction.REMOVE_VAULT_PROTECTION`) et ne l'avait jamais reçu.
 * Jumeau asymétrique, relevé par l'audit par motifs du 2026-08-15.
 *
 * Même disposition que [ConfirmDeleteFolderDialog] et que le dialogue de sortie d'une note :
 * l'action destructrice est le bouton discret en rouge, « Annuler » celui qu'on touche par réflexe.
 */
@Composable
fun ConfirmRemoveVaultProtectionDialog(folder: Folder, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.folder_remove_vault_title)) },
        text = { CorpsDeDialogue(stringResource(R.string.folder_remove_vault_body, folder.name)) },
        confirmButton = {
            ActionDeDialogue(
                texte = stringResource(R.string.folder_remove_vault_confirm),
                onClick = onConfirm,
                couleur = MaterialTheme.colorScheme.error,
            )
        },
        dismissButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onDismiss)
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
        // 🔴 **Les deux CHOIX sont dans le corps, pas dans les emplacements de boutons.**
        //
        // Ils y étaient, empilés dans une `Column` posée en `dismissButton`. Mesuré sur le S9 le
        // 2026-08-15 : la rangée d'actions d'un `AlertDialog` est bornée en hauteur, la pile
        // réclamait 288 px et n'en recevait que 216. Le dernier bouton était **coupé net à la
        // limite du dialogue** — 72 px au lieu de 144, son libellé tronqué à mi-hauteur.
        //
        // ⚠️⚠️ Et c'était « Supprimer définitivement » : **l'action irréversible était celle qu'on
        // ne voyait pas**. Un dialogue qui cache l'option qui détruit tout est pire que pas de
        // dialogue — il fait croire qu'on a choisi en connaissance de cause.
        //
        // ⚠️ Rendre le corps défilant n'y changeait rien, et réduire le remplissage non plus : la
        // borne ne venait pas du texte mais de l'emplacement lui-même. **Trois actions ne rentrent
        // pas dans les deux emplacements d'un `AlertDialog`**, et Material dit la même chose — au
        // delà de deux, on présente une liste de choix, pas une rangée de boutons.
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (folder.isVault) {
                        stringResource(R.string.folder_delete_vault_choice_body, folder.name)
                    } else {
                        stringResource(R.string.folder_delete_choice_body, folder.name)
                    },
                )
                ActionDeDialogue(
                    texte = stringResource(
                        if (folder.isVault) {
                            R.string.folder_delete_move_to_inbox_vault
                        } else {
                            R.string.folder_delete_move_to_inbox
                        },
                    ),
                    onClick = { onChoice(FolderDeletionChoice.MOVE_TO_INBOX) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // L'action irréversible reste en rouge et **en dernier** : elle ne doit jamais être
                // celle qu'on touche par réflexe. Elle est maintenant entièrement visible, ce qui
                // est la condition pour qu'on puisse dire qu'on l'a choisie.
                ActionDeDialogue(
                    texte = stringResource(R.string.folder_delete_permanent),
                    onClick = { onChoice(FolderDeletionChoice.DELETE_EVERYTHING) },
                    modifier = Modifier.fillMaxWidth(),
                    couleur = MaterialTheme.colorScheme.error,
                )
            }
        },
        // Il ne reste qu'une action au sens du dialogue : ne rien faire. Elle occupe l'emplacement
        // de confirmation parce que c'est celui que Material place en dernier, sous le pouce.
        confirmButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onDismiss)
        },
    )
}
