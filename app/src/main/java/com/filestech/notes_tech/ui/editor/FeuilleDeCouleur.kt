package com.filestech.notes_tech.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.NoteColor
import com.filestech.notes_tech.ui.common.nom
import com.filestech.notes_tech.ui.theme.CouleursDeNote

/**
 * The note's colour in the lists (3.1.0): "no colour" and the eight of [NoteColor], drawn as they will
 * be drawn on the card — same background, same border — under the theme in use.
 *
 * ## Accessibility
 *
 * Each swatch is a radio button in a selectable group: TalkBack says its colour's name, "selected" on
 * the current one, and the group's size. The tick on the current swatch is drawn for those who see; its
 * meaning is carried by `selected`, so the icon itself says nothing.
 *
 * ⚠️ [dansUnCoffre]: the sheet says that the colour shows only while the vault is open — option A,
 * see `couleurVisible`. Not a warning to scare, a fact the user would otherwise discover by surprise.
 */
@Composable
internal fun FeuilleDeCouleur(
    actuelle: NoteColor?,
    dansUnCoffre: Boolean,
    onChoisir: (NoteColor?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.note_color_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
            )
            if (dansUnCoffre) {
                Text(
                    text = stringResource(R.string.note_color_vault_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 12.dp).selectableGroup(),
            ) {
                PastilleDeCouleur(couleur = null, choisie = actuelle == null, onChoisir = onChoisir)
                NoteColor.entries.forEach { couleur ->
                    PastilleDeCouleur(couleur = couleur, choisie = actuelle == couleur, onChoisir = onChoisir)
                }
            }
        }
    }
}

/** One swatch: the card's own pair for [couleur], or the plain card with a "no colour" mark for `null`. */
@Composable
private fun PastilleDeCouleur(couleur: NoteColor?, choisie: Boolean, onChoisir: (NoteColor?) -> Unit) {
    val schema = MaterialTheme.colorScheme
    val teinte = couleur?.let { CouleursDeNote.teinte(it, schema) }
    val nom = stringResource(couleur?.nom() ?: R.string.note_color_none)
    Surface(
        shape = CircleShape,
        color = teinte?.fond ?: schema.surfaceContainerLow,
        border = BorderStroke(if (choisie) 3.dp else 1.5.dp, teinte?.bord ?: schema.outline),
        modifier = Modifier
            .size(TAILLE_DE_PASTILLE)
            .selectable(selected = choisie, role = Role.RadioButton, onClick = { onChoisir(couleur) })
            .semantics { contentDescription = nom },
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                choisie -> Icon(Icons.Filled.Check, contentDescription = null, tint = teinte?.bord ?: schema.onSurface)
                couleur == null -> Icon(
                    Icons.Outlined.FormatColorReset,
                    contentDescription = null,
                    tint = schema.onSurfaceVariant,
                )
            }
        }
    }
}

/** 48 dp: the minimum touch target, the swatch being the target itself. */
private val TAILLE_DE_PASTILLE = 48.dp
