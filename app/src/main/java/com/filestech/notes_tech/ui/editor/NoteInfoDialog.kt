package com.filestech.notes_tech.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import com.filestech.notes_tech.ui.common.rememberNoteDateFormatter
import java.text.NumberFormat

/**
 * The info panel: folder, dates and counts. Read-only, no action but closing.
 *
 * ⚠️ An `AlertDialog` and not a bottom sheet: a `ModalBottomSheet` drag handle leaves an unnamed
 * actionable node that every accessibility sweep of this repository has to special-case (see
 * `AutocompletionTest`). Five read-only lines do not need a draggable surface.
 *
 * ⚠️ Each line is ONE accessibility node — label and value merged — so a screen reader says
 * "Words, 1 234" instead of reading two unrelated fragments.
 */
@Composable
fun NoteInfoDialog(info: NoteInfo, onDismiss: () -> Unit) {
    val formatDate = rememberNoteDateFormatter()
    val locale = LocalConfiguration.current.locales.takeIf { !it.isEmpty }?.get(0)
    // Grouped digits in the reader's language: "1 234" in French (narrow no-break space), "1,234"
    // in English. A raw `toString()` would print "1234" in both.
    val formatNumber = remember(locale) {
        val format = if (locale != null) NumberFormat.getIntegerInstance(locale) else NumberFormat.getIntegerInstance()
        ({ n: Int -> format.format(n.toLong()) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
        title = { Text(stringResource(R.string.note_info_title)) },
        text = {
            // Scrollable for the same reason as `CorpsDeDialogue`: at a large font scale five lines
            // plus a hint must never push the close button off the screen.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoLine(R.string.note_info_folder, info.folderName)
                InfoLine(R.string.note_info_created, formatDate(info.createdAt))
                InfoLine(R.string.note_info_modified, formatDate(info.updatedAt))
                InfoLine(R.string.note_info_words, formatNumber(info.words))
                InfoLine(R.string.note_info_characters, formatNumber(info.characters))
                Text(
                    text = stringResource(R.string.note_info_characters_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_close), onClick = onDismiss)
        },
    )
}

@Composable
private fun InfoLine(@StringRes label: Int, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            // A long folder name wraps under its label instead of squeezing it to nothing.
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
