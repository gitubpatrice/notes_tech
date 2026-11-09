package com.filestech.notes_tech.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.filestech.notes_tech.R

/**
 * La confirmation avant de sortir une note d'un coffre — ou [nombre] notes, depuis la sélection de la
 * liste (3.3.0). Moved here from the editor so that both ask the SAME question: a sealed note leaving
 * its vault from the list must not be asked less than from the editor.
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
 *
 * ⚠️ One note keeps the editor's own sentences, word for word; several get the plural ones.
 */
@Composable
internal fun DialogueDeSortieDeCoffre(onConfirmer: () -> Unit, onAnnuler: () -> Unit, nombre: Int = 1) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        icon = {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(selonLeNombre(nombre, R.string.note_editor_exit_vault_title, R.plurals.notes_exit_vault_title))
        },
        text = {
            CorpsDeDialogue(
                selonLeNombre(nombre, R.string.note_editor_exit_vault_body, R.plurals.notes_exit_vault_body),
            )
        },
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
