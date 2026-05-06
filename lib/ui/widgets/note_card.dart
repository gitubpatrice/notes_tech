/// Card compacte d'une note dans la liste d'accueil ou de dossier.
///
/// Inclut son propre `Material/InkWell/Border` pour éviter le `Card` extérieur.
/// L'extrait Markdown est mémoïsé sur l'instance Note (cf. Note.excerpt).
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../data/models/note.dart';

class NoteCard extends StatelessWidget {
  const NoteCard({
    super.key,
    required this.note,
    required this.onTap,
    this.onLongPress,
    this.folderName,
  });

  final Note note;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;

  /// Nom du dossier de la note, à afficher en discrète puce dans la zone
  /// méta. Optionnel : l'appelant le passe quand il affiche une liste
  /// non scopée (mode "Toutes les notes") pour que l'utilisateur sache
  /// d'où vient chaque note. En mode filtré (un seul dossier visible),
  /// passer `null` pour ne pas surcharger l'UI.
  final String? folderName;

  static final DateFormat _df = DateFormat('dd MMM yyyy · HH:mm', 'fr_FR');

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final excerpt = note.excerpt;
    return RepaintBoundary(
      child: Material(
        color: theme.cardTheme.color,
        clipBehavior: Clip.antiAlias,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: theme.dividerColor),
        ),
        child: InkWell(
          onTap: onTap,
          onLongPress: onLongPress,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    if (note.pinned)
                      const Padding(
                        padding: EdgeInsets.only(right: 6),
                        child: Icon(Icons.push_pin, size: 14),
                      ),
                    Expanded(
                      child: Text(
                        note.title.isEmpty ? 'Sans titre' : note.title,
                        style: theme.textTheme.titleMedium,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (note.favorite)
                      const Icon(Icons.star, size: 16, color: Colors.amber),
                  ],
                ),
                if (excerpt.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text(
                    excerpt,
                    style: theme.textTheme.bodySmall,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
                const SizedBox(height: 8),
                Row(
                  children: [
                    Text(_df.format(note.updatedAt),
                        style: theme.textTheme.labelMedium),
                    if (folderName != null) ...[
                      const SizedBox(width: 8),
                      _FolderChip(label: folderName!),
                    ],
                    if (note.tags.isNotEmpty) ...[
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          note.tags.map((t) => '#$t').join(' '),
                          style: theme.textTheme.labelMedium,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Petite puce affichant le nom d'un dossier dans la zone meta. Reste
/// discrète : background sub-surface + texte petit. Conçue pour ne pas
/// rivaliser visuellement avec le titre ou les tags.
class _FolderChip extends StatelessWidget {
  const _FolderChip({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.folder_outlined, size: 12, color: cs.onSurfaceVariant),
          const SizedBox(width: 4),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 120),
            child: Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 11,
                color: cs.onSurfaceVariant,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
