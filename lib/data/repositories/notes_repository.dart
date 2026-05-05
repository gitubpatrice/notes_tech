/// Repository façade au-dessus de NotesDao.
///
/// Centralise validation, génération d'ID, horodatage, et notifications.
/// Les écouteurs (UI providers, indexation, backlinks) s'abonnent à
/// `changes` pour rafraîchir.
///
/// Le stream émet désormais des `NoteChangeEvent` typés (id + kind +
/// titre avant/après) afin que les services aval puissent cibler la
/// seule note modifiée.
library;

import 'dart:async';

import 'package:uuid/uuid.dart';

import '../../core/constants.dart';
import '../../core/exceptions.dart';
import '../db/notes_dao.dart';
import '../models/note.dart';
import '../models/note_change.dart';

class NotesRepository {
  NotesRepository(this._dao);

  final NotesDao _dao;
  static const _uuid = Uuid();
  final _changes = StreamController<NoteChangeEvent>.broadcast();

  /// S'abonner pour être notifié à chaque écriture.
  Stream<NoteChangeEvent> get changes => _changes.stream;

  void dispose() => _changes.close();

  // ---------------------------------------------------------------------
  // Lecture
  // ---------------------------------------------------------------------

  Future<Note?> get(String id) => _dao.findById(id);

  Future<List<Note>> getMany(List<String> ids) => _dao.findManyByIds(ids);

  Future<List<Note>> listByFolder(
    String folderId, {
    NoteSortMode sort = NoteSortMode.updatedDesc,
    bool includeArchived = false,
  }) =>
      _dao.listByFolder(folderId, sort: sort, includeArchived: includeArchived);

  Future<List<Note>> recent() =>
      _dao.listRecent(limit: AppConstants.recentNotesLimit);

  /// Toutes les notes vivantes (hors corbeille). Utilisé par l'indexation
  /// d'embeddings — pas par l'UI.
  Future<List<Note>> listAllAlive() => _dao.listAllAlive();

  Future<List<Note>> favorites() => _dao.listFavorites();

  Future<List<Note>> trash() => _dao.listTrash();

  Future<List<Note>> search(String query) =>
      _dao.search(query, limit: AppConstants.searchResultsLimit);

  /// Auto-complétion : pré-filtre côté SQLite (insensible à la casse,
  /// pas aux diacritiques — affinage Dart à charge de l'appelant).
  /// Pousser le filtrage en SQL évite de charger toutes les notes
  /// pour chaque keystroke.
  Future<List<Note>> findByTitleLike(
    String lowerNeedle, {
    int limit = 32,
    String? excludeId,
  }) =>
      _dao.findByTitleLike(
        lowerNeedle,
        limit: limit,
        excludeId: excludeId,
      );

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
    _emit(NoteChangeEvent(
      kind: NoteChangeKind.created,
      id: note.id,
      currentTitle: note.title,
    ));
    return note;
  }

  /// Sauvegarde idempotente : récupère le titre précédent pour permettre
  /// aux services aval (backlinks) de détecter un renommage.
  Future<Note> save(Note note) async {
    _validateTitle(note.title);
    final previous = await _dao.findById(note.id);
    final updated = note.copyWith(updatedAt: DateTime.now());
    await _dao.update(updated);
    _emit(NoteChangeEvent(
      kind: NoteChangeKind.updated,
      id: updated.id,
      previousTitle: previous?.title,
      currentTitle: updated.title,
    ));
    return updated;
  }

  Future<Note> togglePin(Note note) =>
      _toggleFlag(note, note.copyWith(pinned: !note.pinned));

  Future<Note> toggleFavorite(Note note) =>
      _toggleFlag(note, note.copyWith(favorite: !note.favorite));

  Future<Note> toggleArchive(Note note) =>
      _toggleFlag(note, note.copyWith(archived: !note.archived));

  Future<Note> _toggleFlag(Note original, Note candidate) async {
    final updated = candidate.copyWith(updatedAt: DateTime.now());
    await _dao.update(updated);
    _emit(NoteChangeEvent(
      kind: NoteChangeKind.updated,
      id: updated.id,
      previousTitle: original.title,
      currentTitle: updated.title,
    ));
    return updated;
  }

  Future<void> moveToTrash(Note note) async {
    await _dao.update(note.copyWith(
      trashedAt: DateTime.now(),
      updatedAt: DateTime.now(),
    ));
    // Une note en corbeille disparaît de toutes les vues vivantes :
    // on la traite comme une suppression côté indexation/backlinks.
    _emit(NoteChangeEvent(
      kind: NoteChangeKind.deleted,
      id: note.id,
      previousTitle: note.title,
    ));
  }

  Future<void> restoreFromTrash(Note note) async {
    await _dao.update(note.copyWith(
      clearTrashedAt: true,
      updatedAt: DateTime.now(),
    ));
    _emit(NoteChangeEvent(
      kind: NoteChangeKind.created,
      id: note.id,
      currentTitle: note.title,
    ));
  }

  Future<void> deletePermanently(String id) async {
    final note = await _dao.findById(id);
    await _dao.deleteHard(id);
    _emit(NoteChangeEvent(
      kind: NoteChangeKind.deleted,
      id: id,
      previousTitle: note?.title,
    ));
  }

  /// Purge automatique de la corbeille au-delà de la rétention.
  Future<int> purgeOldTrash() async {
    final cutoff = DateTime.now().subtract(
      const Duration(days: AppConstants.trashRetentionDays),
    );
    final n = await _dao.purgeTrashOlderThan(cutoff);
    if (n > 0) _emit(NoteChangeEvent.bulk);
    return n;
  }

  // ---------------------------------------------------------------------

  void _validateTitle(String title) {
    if (title.length > AppConstants.noteTitleMaxLength) {
      throw const ValidationException('Titre trop long');
    }
  }

  void _emit(NoteChangeEvent event) {
    if (!_changes.isClosed) _changes.add(event);
  }
}
