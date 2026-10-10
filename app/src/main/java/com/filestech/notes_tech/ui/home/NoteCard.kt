package com.filestech.notes_tech.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.semantics.Role
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
 *
 * ## 🔴 [onClick] est NULLABLE, et ce n'est pas une commodité d'appel
 *
 * Une carte de **corbeille** ne s'ouvre pas : la note est en attente de destruction, et
 * `trash_screen.dart` la rend en `ListTile` **sans `onTap`**. Le portage passait ici une lambda
 * vide, ce qui n'est pas la même chose du tout : `clickable` pose alors une action `OnClick` dans
 * l'arbre de sémantique et un effet d'encre sous le doigt. Un lecteur d'écran annonçait donc
 * « double-touchez pour activer » sur une carte inerte, et le doigt recevait un retour visuel pour
 * un geste sans effet. Cf. `04-PIEGES.md` §74.
 *
 * `null` retire le modificateur — donc l'action, donc l'annonce.
 *
 * ## [onLongClick]: the note's actions (3.1.0)
 *
 * On the SAME node as the click, for the reason given below for the click: an action set on a child
 * does not reach the merged node, and TalkBack would never offer it. `onLongClickLabel` is what it
 * reads ("double-tap and hold to …"); without it the gesture exists only for those who see. A card
 * that cannot be opened (trash) takes no long press either: its actions are its own buttons.
 */
@Composable
fun NoteCard(
    note: Note,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    folderName: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val verrouillee = note.isLocked
    val couleurs = MaterialTheme.colorScheme
    val formate = rememberNoteDateFormatter()
    val extrait = remember(note.id, note.content, verrouillee) { NoteExcerpt.of(note) }

    val titreAffiche = titreAffiche(note)

    // Un seul nœud d'accessibilité pour toute la carte : un balayage de lecteur d'écran doit lire
    // « titre, date, dossier » d'un coup, pas égrener quatre éléments dont trois sont du contexte.
    //
    // 🔴🔴 **Ce nœud doit porter le clic AUSSI, et il ne le portait pas.** La sémantique était posée
    // sur le `Surface` et le `clickable` sur la `Column` fille : mesuré le 2026-08-17 sur le S9, le
    // nœud fusionné ne portait **aucune** action `OnClick` — les actions d'un descendant ne remontent
    // pas à la fusion, contrairement au texte. La carte s'annonçait donc comme du **texte**, sans
    // dire qu'on peut l'ouvrir, sur l'accueil comme dans la recherche. Le geste marchait quand même,
    // parce qu'un double-appui de lecteur d'écran envoie un toucher au centre du nœud, qui atteint la
    // fille cliquable : *ce que le code fait n'est pas ce que l'utilisateur entend*. Cf. §74.
    //
    // Les deux vivent donc sur le **même** nœud, celui de la `Column`. Le `Surface` reste purement
    // visuel — y déplacer le `clickable` sortirait l'effet d'encre de son propre découpage arrondi.
    //
    // 🔴 **Les étiquettes en font partie, et elles n'y étaient pas.** Un nœud fusionné qui porte une
    // `contentDescription` explicite **remplace** la lecture de ses enfants : tout ce qui n'est pas
    // dans cette chaîne n'existe pas pour un lecteur d'écran. `#urgent` était donc affiché à qui voit
    // et tu à qui écoute — mesuré sur le S9, le nœud fusionné ne portait **aucune** propriété `Text`.
    // Relevé par une relecture externe (Gemini, 2026-08-17) ; le défaut préexistait au correctif de
    // §74, il n'en découle pas.
    //
    // ⚠️ **`!verrouillee` comme partout ailleurs ici.** Annoncer les étiquettes d'une note de coffre
    // rouvrirait exactement la fuite que cette carte ferme : « Note verrouillée, #médical, #divorce »
    // n'a rien protégé. La garde suit la branche visuelle, ligne pour ligne.
    val description = buildString {
        append(titreAffiche)
        if (!verrouillee && extrait.isNotEmpty()) append(". ").append(extrait)
        append(". ").append(formate(note.updatedAt))
        folderName?.let { append(". ").append(it) }
        if (!verrouillee && note.tags.isNotEmpty()) {
            append(". ").append(note.tags.joinToString(" ") { "#$it" })
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
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
                // ⚠️ `Role.Button` : sans lui TalkBack dit la description puis « double-touchez pour
                // activer », sans jamais nommer **ce que c'est**. Relevé par la même relecture.
                .then(
                    when {
                        onClick != null && onLongClick != null -> Modifier.combinedClickable(
                            role = Role.Button,
                            onLongClickLabel = stringResource(R.string.note_actions_label),
                            onLongClick = onLongClick,
                            onClick = onClick,
                        )
                        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
                        else -> Modifier
                    },
                )
                .semantics(mergeDescendants = true) { contentDescription = description }
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

/**
 * The title a list shows for [note]: "Locked note" for a sealed one — never its title, which the
 * column holds empty anyway for `enc_v` 2 — and "Untitled" for an empty one. One rule for the card and
 * for the long-press sheet that names it, so the two cannot disagree on what a vault note reveals.
 */
@Composable
internal fun titreAffiche(note: Note): String = when {
    note.isLocked -> stringResource(R.string.note_card_locked)
    note.title.isEmpty() -> stringResource(R.string.note_untitled)
    else -> note.title
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
