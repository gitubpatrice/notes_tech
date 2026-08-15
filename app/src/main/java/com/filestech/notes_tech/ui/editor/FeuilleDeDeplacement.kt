package com.filestech.notes_tech.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder

/**
 * Le choix d'un dossier de destination.
 *
 * ## ⚠️ Un dossier coffre est proposé, et c'est délibéré
 *
 * Y déplacer une note la **scelle** : `NotesRepository.moveToFolder` chiffre avant d'écrire, purge
 * ses liens sortants et détache ceux qui la visaient. C'est une protection qu'on gagne, pas une
 * qu'on perd — l'inverse, sortir une note d'un coffre, est ce qui demande une confirmation, et ce
 * chemin-là n'est pas encore ouvert ici.
 *
 * L'icône de cadenas dit lesquels scellent, pour que le geste ne surprenne pas.
 */
@Composable
fun FeuilleDeDeplacement(
    dossiers: List<Folder>,
    dossierActuel: String?,
    onChoisir: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Le dossier courant n'est pas une destination : l'y « déplacer » ne ferait rien, et le dépôt
    // rend `false` sans rien changer.
    val destinations = dossiers.filterNot { it.id == dossierActuel }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.move_to_folder_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))

            if (destinations.isEmpty()) {
                Text(
                    text = stringResource(R.string.move_to_folder_empty),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = HAUTEUR_MAXIMALE)) {
                    items(items = destinations, key = { it.id }) { dossier ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = dossier.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = { Icon(iconeDe(dossier), contentDescription = null) },
                            supportingContent = if (dossier.isVault) {
                                { Text(stringResource(R.string.note_card_locked)) }
                            } else {
                                null
                            },
                            modifier = Modifier.clickable { onChoisir(dossier.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun iconeDe(dossier: Folder) = when {
    dossier.isVault -> Icons.Outlined.Lock
    dossier.id == Folder.INBOX_ID -> Icons.Outlined.Inbox
    else -> Icons.Outlined.Folder
}

private val HAUTEUR_MAXIMALE = 360.dp
