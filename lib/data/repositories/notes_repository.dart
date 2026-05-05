/// Repository façade au-dessus de NotesDao.
///
/// Centralise validation, génération d'ID, horodatage, et notifications.
/// Les écouteurs (UI providers) s'abonnent à `changes` pour rafraîchir.
library;

import 'dart:async';

import 'package:uuid/uuid.dart';

import '../../core/constants.dart';
import '../../core/exceptions.dart';
import '../db/notes_dao.dart';
import '../models/note.dart';

class NotesRepository {
  NotesRepository(this._dao);

  final NotesDao _dao;
  static const _uuid = Uuid();
  final _changes = StreamController<void>.broadcast();

  /// S'abonner pour être notifié à chaque écriture.
  Stream<void> get changes => _changes.stream;

  void dispose() => _changes.close();

  // ---------------------------------------------------------------------
  // Lecture
  // ---------------------------------------------------------------------

  Future<Note?> get(String id) => _dao.findById(id);

  Future<List<Note>> listByFolder(
    String folderId, {
    NoteSortMode sort = NoteSortMode.updatedDesc,
    bool includeArchived = false,
  }) =>
      _dao.listByFolder(folderId, sort: sort, includeArchived: includeArchived);

  Future<List<Note>> recent() =>
      _dao.listRecent(limit: AppConstants.recentNotesLimit);

  // -------- Méthodes préparées pour v0.2 (vue dossiers / corbeille / favoris). --------

  Future<List<Note>> favorites() => _dao.listFavorites();

  Future<List<Note>> trash() => _dao.listTrash();

  Future<List<Note>> search(String query) =>
      _dao.search(query, limit: AppConstants.searchResultsLimit);

  // ---------------------------------------------------------------------
  // Écriture
  // ---------------------------------------------------------------------

  Future<Note> create({
    required String folderId,
    String title = '',
    String content = '',
    List<String> tags = const [],
  }) async {
    _validateTitle(title);
    final now = DateTime.now();
    final note = Note(
      id: _uuid.v4(),
      title: title,
      content: content,
      folderId: folderId,
      tags: tags,
      createdAt: now,
      updatedAt: now,
    );
    await _dao.insert(note);
    _emit();
    return note;
  }

  Future<Note> save(Note note) async {
    _validateTitle(note.title);
    final updated = note.copyWith(updatedAt: DateTime.now());
    await _dao.update(updated);
    _emit();
    return updated;
  }

  Future<Note> togglePin(Note note) async {
    final updated = note.copyWith(
      pinned: !note.pinned,
      updatedAt: DateTime.now(),
    );
    await _dao.update(updated);
    _emit();
    return updated;
  }

  Future<Note> toggleFavorite(Note note) async {
    final updated = note.copyWith(
      favorite: !note.favorite,
      updatedAt: DateTime.now(),
    );
    await _dao.update(updated);
    _emit();
    return updated;
  }

  Future<Note> toggleArchive(Note note) async {
    final updated = note.copyWith(
      archived: !note.archived,
      updatedAt: DateTime.now(),
    );
    await _dao.update(updated);
    _emit();
    return updated;
  }

  Future<void> moveToTrash(Note note) async {
    await _dao.update(note.copyWith(
      trashedAt: DateTime.now(),
      updatedAt: DateTime.now(),
    ));
    _emit();
  }

  Future<void> restoreFromTrash(Note note) async {
    await _dao.update(note.copyWith(
      clearTrashedAt: true,
      updatedAt: DateTime.now(),
    ));
    _emit();
  }

  Future<void> deletePermanently(String id) async {
    await _dao.deleteHard(id);
    _emit();
  }

  /// Purge automatique de la corbeille au-delà de la rétention.
  Future<int> purgeOldTrash() async {
    final cutoff = DateTime.now().subtract(
      const Duration(days: AppConstants.trashRetentionDays),
    );
    final n = await _dao.purgeTrashOlderThan(cutoff);
    if (n > 0) _emit();
    return n;
  }

  // ---------------------------------------------------------------------

  void _validateTitle(String title) {
    if (title.length > AppConstants.noteTitleMaxLength) {
      throw const ValidationException('Titre trop long');
    }
  }

  void _emit() {
    if (!_changes.isClosed) _changes.add(null);
  }
}
