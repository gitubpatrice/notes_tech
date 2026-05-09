/// Lien `[[Titre]]` extrait d'une note.
///
/// - `targetId` est `null` si le titre cible ne correspond à aucune note
///   existante (lien fantôme / dangling).
/// - `targetTitle` est le texte exact tapé entre `[[ ]]` (préserve la casse).
/// - `targetTitleNorm` est la forme normalisée (lowercase + accents dépouillés)
///   utilisée pour matcher d'autres notes par titre.
library;

import 'package:flutter/foundation.dart';

@immutable
class NoteLink {
  const NoteLink({
    required this.sourceId,
    required this.targetTitle,
    required this.targetTitleNorm,
    required this.position,
    this.targetId,
  });

  final String sourceId;
  final String? targetId;
  final String targetTitle;
  final String targetTitleNorm;
  final int position;

  bool get isResolved => targetId != null;

  Map<String, Object?> toRow() => {
    'source_id': sourceId,
    'target_id': targetId,
    'target_title': targetTitle,
    'target_title_norm': targetTitleNorm,
    'position': position,
  };

  factory NoteLink.fromRow(Map<String, Object?> row) => NoteLink(
    sourceId: row['source_id']! as String,
    targetId: row['target_id'] as String?,
    targetTitle: (row['target_title'] as String?) ?? '',
    targetTitleNorm: (row['target_title_norm'] as String?) ?? '',
    position: (row['position'] as int?) ?? 0,
  );
}
