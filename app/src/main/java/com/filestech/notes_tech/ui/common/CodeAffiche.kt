package com.filestech.notes_tech.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily

/**
 * A generated code on screen — the backup's (3.2.0), the sealed note's (3.4.0): two lines of three groups,
 * monospaced, spelled for TalkBack. One place, so the two show it alike. The screen around it is a secured
 * window, as the code is.
 */
@Composable
fun CodeAffiche(code: String, modifier: Modifier = Modifier) {
    Text(
        // Two lines of three groups: left to the text layout, a line broke inside a group.
        code.split('-').chunked(GROUPES_PAR_LIGNE).joinToString("\n") { it.joinToString("-") },
        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
        // Spelled, group by group: read as text, TalkBack said "K7QM" as a word (audit 3 axes of 3.2.0, U2).
        modifier = modifier.semantics { contentDescription = codeEpele(code) },
    )
}

/** [code] for TalkBack: its signs one by one, a pause between groups — "K, 7, Q, M. T, 2, X, B…". */
internal fun codeEpele(code: String): String =
    code.split('-').joinToString(". ") { groupe -> groupe.toList().joinToString(", ") }

/** How many of the code's groups a line shows. */
private const val GROUPES_PAR_LIGNE = 3
