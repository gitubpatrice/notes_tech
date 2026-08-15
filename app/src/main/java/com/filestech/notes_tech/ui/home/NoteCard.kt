package com.filestech.notes_tech.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.NoteExcerpt
import com.filestech.notes_tech.ui.common.rememberNoteDateFormatter
import com.filestech.notes_tech.ui.theme.SemanticColors

/**
 * Une note dans une liste.
 *
 * Portage de `ui/widgets/note_card.dart`.
 *
 * ## 🔴 Ce que cette carte tait quand la note est verrouillée
 *
 * **Le titre, l'extrait ET les étiquettes.** Les trois, et pas seulement les deux premiers : une
 * carte qui annonce « Note verrouillée » puis affiche `#médical #avocat #divorce` juste en dessous
 * n'a rien protégé. Le défaut a existé dans l'application publiée — le titre et l'extrait étaient
 * masqués, les étiquettes non — et a été relevé par une relecture externe.
 *
 * ⚠️ **Limite connue, à ne pas confondre avec ce masquage** : le chiffrement d'une note vide bien
 * `title` et `content`, mais **pas** `tags`, qui restent en clair dans la colonne. Ce qui est fermé
 * ici, c'est la fuite par l'**affichage**. Les faire entrer dans l'enveloppe demanderait un format
 * v3 et ferait perdre les étiquettes des notes déjà chiffrées. L'index plein texte, lui, les masque
 * déjà par ses déclencheurs.
 */
@Composable
fun NoteCard(note: Note, onClick: () -> Unit, modifier: Modifier = Modifier, folderName: String? = null) {
    val verrouillee = note.isLocked
    val couleurs = MaterialTheme.colorScheme
    val formate = rememberNoteDateFormatter()
    val extrait = remember(note.id, note.content, verrouillee) { NoteExcerpt.of(note) }

    val titreAffiche = when {
        verrouillee -> stringResource(R.string.note_card_locked)
        note.title.isEmpty() -> stringResource(R.string.note_untitled)
        else -> note.title
    }

    // Un seul nœud d'accessibilité pour toute la carte : un balayage de lecteur d'écran doit lire
    // « titre, date, dossier » d'un coup, pas égrener quatre éléments dont trois sont du contexte.
    val description = buildString {
        append(titreAffiche)
        if (!verrouillee && extrait.isNotEmpty()) append(". ").append(extrait)
        append(". ").append(formate(note.updatedAt))
        folderName?.let { append(". ").append(it) }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(12.dp),
        color = couleurs.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            // Le liseré rouge d'une note verrouillée est le même signal que le cadenas du tiroir :
            // deux repères pour la même chose, l'un lisible de loin, l'autre à la lecture.
            color = if (verrouillee) couleurs.error.copy(alpha = 0.4f) else couleurs.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (verrouillee) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = couleurs.error,
                        modifier = Modifier.size(14.dp).padding(end = 0.dp),
                    )
                }
                if (note.pinned && !verrouillee) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = titreAffiche,
                    style = MaterialTheme.typography.titleMedium,
                    fontStyle = if (verrouillee) FontStyle.Italic else null,
                    color = if (verrouillee) couleurs.onSurfaceVariant else couleurs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (verrouillee || (note.pinned && !verrouillee)) 6.dp else 0.dp),
                )
                if (note.favorite && !verrouillee) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = SemanticColors.favoriteIcon,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (!verrouillee && extrait.isNotEmpty()) {
                Text(
                    text = extrait,
                    style = MaterialTheme.typography.bodySmall,
                    color = couleurs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = formate(note.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = couleurs.onSurfaceVariant,
                )
                if (folderName != null) FolderChip(folderName)
                if (!verrouillee && note.tags.isNotEmpty()) {
                    Text(
                        text = note.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelMedium,
                        color = couleurs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** La puce discrète qui dit d'où vient une note, en vue non filtrée. */
@Composable
private fun FolderChip(label: String, modifier: Modifier = Modifier) {
    val couleurs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = couleurs.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = couleurs.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = couleurs.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
    }
}
