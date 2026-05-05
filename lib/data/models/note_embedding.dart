/// Embedding vectoriel rattaché à une note.
///
/// `sourceHash` est un hash léger du texte source (titre + corps) au moment
/// du calcul — permet à l'indexeur de skipper si la note n'a pas changé,
/// même si son `updated_at` est plus récent.
library;

import 'package:flutter/foundation.dart';

@immutable
class NoteEmbedding {
  const NoteEmbedding({
    required this.noteId,
    required this.vector,
    required this.dim,
    required this.modelId,
    required this.sourceHash,
    required this.updatedAt,
  });

  final String noteId;
  final Float32List vector;
  final int dim;
  final String modelId;
  final int sourceHash;
  final DateTime updatedAt;
}
