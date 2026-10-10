package com.filestech.notes_tech.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.filestech.notes_tech.R

/**
 * Dialogue de confirmation d'un geste irréversible.
 *
 * ⚠️ **La confirmation n'est PAS le bouton d'action principal.** L'application publiée avait mis la
 * suppression définitive en `FilledButton` face à un « Annuler » discret ; son propre commentaire
 * dit pourquoi c'était faux — *« le geste irréversible était celui qu'on tape par réflexe »*
 * (`trash_screen.dart:121`). Les deux boutons sont donc du même poids, seule la couleur d'erreur
 * distingue celui qui détruit.
 *
 * Moved out of `TrashScreen.kt` in 3.1.0: the long press on a note in the list asks the same question.
 */
@Composable
internal fun DialogueDestructif(
    titre: String,
    corps: String,
    libelleConfirmation: String,
    onConfirmer: () -> Unit,
    onAnnuler: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(titre) },
        text = { CorpsDeDialogue(corps) },
        confirmButton = {
            ActionDeDialogue(
                texte = libelleConfirmation,
                onClick = onConfirmer,
                couleur = MaterialTheme.colorScheme.error,
            )
        },
        dismissButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onAnnuler)
        },
    )
}
