package com.filestech.notes_tech.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.local.dao.NoteLinkRow
import com.filestech.notes_tech.ui.theme.Formes

/**
 * Les liens d'une note : ce qu'elle cite, et ce qui la cite.
 *
 * ## 🔴 Aucun défilement propre, et ce n'est pas un choix esthétique
 *
 * Ce panneau vit **à l'intérieur** de la colonne défilante de l'éditeur
 * (`NoteEditorScreen`). Y poser un `LazyColumn`, un `LazyRow` ou un second `verticalScroll` place un
 * composant défilant dans une contrainte de hauteur infinie, et Compose ne s'en tire pas par une
 * dégradation : il **plante**, avec « Vertically scrollable component was measured with an infinity
 * maximum height constraints ».
 *
 * Ce n'est pas une crainte théorique. C'est exactement ce qui est arrivé à l'écran de fin du mode
 * panique le 2026-08-14, et le coût y était bien pire qu'ici : la destruction avait réussi, mais
 * l'utilisateur se retrouvait sur le lanceur sans savoir si ses notes avaient été effacées.
 *
 * D'où [FlowRow], qui se mesure sans borne verticale. Le nombre de liens d'une note est de toute
 * façon borné par l'extraction elle-même — 256 au maximum, et en pratique quelques-uns.
 *
 * ## Ce que l'affichage ne doit jamais révéler
 *
 * Une note d'un coffre **verrouillé** n'apparaît ni dans les liens sortants ni dans les mentions.
 * Cette garantie ne se tient pas ici : elle se tient dans la base, par trois chemins vérifiés le
 * 2026-08-15 (cf. `NoteEditorViewModel.liens`). Ce composant se contente d'afficher ce qu'on lui
 * donne — et c'est pour cela qu'il ne prend aucune décision de filtrage : deux endroits qui filtrent
 * finissent par filtrer différemment.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiensDeLaNote(
    liens: PanneauDeLiens,
    onOuvrirNote: (String) -> Unit,
    onLienFantome: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Une note sans lien ne paie pas de place à l'écran pour l'annoncer.
    if (liens.estVide) return

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (liens.sortants.isNotEmpty()) {
            Section(
                icone = Icons.Outlined.NorthEast,
                libelle = stringResource(R.string.note_editor_outgoing_links, liens.sortants.size),
            ) {
                liens.sortants.forEach { lien ->
                    PuceDeLienSortant(lien = lien, onOuvrirNote = onOuvrirNote, onLienFantome = onLienFantome)
                }
            }
        }
        if (liens.mentions.isNotEmpty()) {
            Section(
                icone = Icons.Outlined.SouthWest,
                libelle = "${stringResource(R.string.note_editor_backlinks)} (${liens.mentions.size})",
            ) {
                liens.mentions.forEach { note ->
                    PuceDeNote(
                        titre = note.title.ifEmpty { stringResource(R.string.note_untitled) },
                        onClick = { onOuvrirNote(note.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(icone: ImageVector, libelle: String, contenu: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // L'icône double le libellé qui suit : la lire ferait entendre deux fois la même chose.
            Icon(
                imageVector = icone,
                contentDescription = null,
                modifier = Modifier.size(14.dp).clearAndSetSemantics { },
            )
            Spacer(Modifier.width(6.dp))
            Text(text = libelle, style = MaterialTheme.typography.labelMedium)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            contenu()
        }
    }
}

/**
 * Un lien partant de la note.
 *
 * ⚠️ Un lien **fantôme** — qui vise un titre pour lequel aucune note n'existe — n'est pas un lien
 * cassé à cacher : c'est une note que l'utilisateur a annoncée et pas encore écrite. Il est donc
 * montré, distinctement, et son appui propose de la créer.
 */
@Composable
private fun PuceDeLienSortant(lien: NoteLinkRow, onOuvrirNote: (String) -> Unit, onLienFantome: (String) -> Unit) {
    val cible = lien.targetId
    if (cible != null) {
        PuceDeNote(titre = lien.targetTitle, onClick = { onOuvrirNote(cible) })
    } else {
        AssistChip(
            shape = Formes.bouton,
            onClick = { onLienFantome(lien.targetTitle) },
            label = {
                Text(
                    text = lien.targetTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.AddLink,
                    // ⚠️ Ici la description **ne double pas** le libellé : elle dit ce que le
                    // libellé ne dit pas, à savoir que cette note n'existe pas encore.
                    contentDescription = stringResource(R.string.note_editor_backlink_dangling, lien.targetTitle),
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
    }
}

@Composable
private fun PuceDeNote(titre: String, onClick: () -> Unit) {
    AssistChip(
        shape = Formes.bouton,
        onClick = onClick,
        label = { Text(text = titre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Notes,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize).clearAndSetSemantics { },
            )
        },
    )
}
