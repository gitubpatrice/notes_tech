/// Événement émis par `NotesRepository.changes` à chaque écriture.
///
/// Permet aux services en aval (indexation embeddings, backlinks) de cibler
/// la note réellement modifiée plutôt que de balayer toute la base — gain
/// O(N) → O(1) sur grosse collection.
library;

import 'package:flutter/foundation.dart';

/// Nature de la mutation. `bulk` couvre les opérations qui touchent
/// potentiellement plusieurs lignes d'un coup (purge corbeille).
enum NoteChangeKind { created, updated, deleted, bulk }

@immutable
class NoteChangeEvent {
  const NoteChangeEvent({
    required this.kind,
    this.id,
    this.previousTitle,
    this.currentTitle,
  }) : assert(
         kind == NoteChangeKind.bulk || id != null,
         'kind != bulk requires id',
       );

  /// Sentinelle pour les opérations massives (purge corbeille,
  /// restauration depuis backup, migration future).
  static const NoteChangeEvent bulk = NoteChangeEvent(
    kind: NoteChangeKind.bulk,
  );

  final NoteChangeKind kind;
  final String? id;
  final String? previousTitle;
  final String? currentTitle;

  bool get isBulk => kind == NoteChangeKind.bulk;
  bool get isDeletion => kind == NoteChangeKind.deleted;

  /// `true` si le titre a changé entre `previousTitle` et `currentTitle`.
  /// Comparaison brute — la normalisation est l'affaire de
  /// `BacklinksService.normalizeTitle`.
  bool get titleChanged =>
      previousTitle != null &&
      currentTitle != null &&
      previousTitle != currentTitle;
}
