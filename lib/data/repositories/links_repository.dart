/// Façade au-dessus de LinksDao + stream de notification de changements.
library;

import 'dart:async';

import '../db/links_dao.dart';
import '../models/note.dart';
import '../models/note_link.dart';

class LinksRepository {
  LinksRepository(this._dao);
  final LinksDao _dao;
  final _changes = StreamController<void>.broadcast();

  /// Émet à chaque écriture (replace / resolve / unresolve).
  Stream<void> get changes => _changes.stream;

  Future<void> dispose() async {
    if (!_changes.isClosed) await _changes.close();
  }

  Future<void> replaceLinksForSource(
    String sourceId,
    List<NoteLink> links,
  ) async {
    await _dao.replaceLinksForSource(sourceId, links);
    _emit();
  }

  Future<List<NoteLink>> outgoing(String sourceId) => _dao.outgoing(sourceId);

  Future<List<Note>> backlinkSources({
    required String targetId,
    required String targetTitleNorm,
  }) => _dao.backlinkSources(
    targetId: targetId,
    targetTitleNorm: targetTitleNorm,
  );

  Future<int> resolveDangling({
    required String noteId,
    required String titleNorm,
  }) async {
    final n = await _dao.resolveDangling(noteId: noteId, titleNorm: titleNorm);
    if (n > 0) _emit();
    return n;
  }

  Future<int> unresolveByMismatch({
    required String noteId,
    required String newTitleNorm,
  }) async {
    final n = await _dao.unresolveByMismatch(
      noteId: noteId,
      newTitleNorm: newTitleNorm,
    );
    if (n > 0) _emit();
    return n;
  }

  Future<int> count() => _dao.count();

  void _emit() {
    if (!_changes.isClosed) _changes.add(null);
  }
}
