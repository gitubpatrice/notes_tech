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
  });

  final Note note;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;

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
