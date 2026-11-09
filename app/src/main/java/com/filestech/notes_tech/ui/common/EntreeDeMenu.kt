package com.filestech.notes_tech.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One entry of an action sheet: icon, title, optional subtitle.
 *
 * Moved out of `FolderDialogs.kt` when the notes got their own action sheet (long press, 3.1.0), so
 * that two sheets would not draw their entries each in its own way. That sheet became the selection's
 * bar in 3.3.0: the folders' sheet is its one user now.
 *
 * [destructive]: an entry that removes something — its icon AND its title in the theme's red, as the
 * ⋮ menu's "Move to trash" (Patrice, 2026-10-10). [tint] alone colours the icon only: the vault entries
 * carry a red padlock without removing anything.
 */
@Composable
internal fun EntreeDeMenu(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color? = null,
    destructive: Boolean = false,
) {
    val rouge = MaterialTheme.colorScheme.error
    ListItem(
        headlineContent = { Text(title, color = if (destructive) rouge else Color.Unspecified) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) rouge else tint ?: LocalContentColor.current,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clickable(onClick = onClick),
    )
}
