/// Accès direct à la table `folders`.
library;

import 'package:sqflite_sqlcipher/sqflite.dart' hide DatabaseException;

import '../../core/exceptions.dart';
import '../models/folder.dart';

class FoldersDao {
  FoldersDao(this._db);
  final Database _db;

  Future<Folder?> findById(String id) async {
    try {
      final rows = await _db.query(
        'folders',
        where: 'id = ?',
        whereArgs: [id],
        limit: 1,
      );
      if (rows.isEmpty) return null;
      return Folder.fromRow(rows.first);
    } catch (e) {
      throw DatabaseException('folder.findById échoué', cause: e);
    }
  }

  Future<List<Folder>> listAll() async {
    try {
      final rows =
          await _db.query('folders', orderBy: 'name COLLATE NOCASE ASC');
      return rows.map(Folder.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('folder.listAll échoué', cause: e);
    }
  }

  Future<List<Folder>> listChildren(String? parentId) async {
    try {
      final rows = await _db.query(
        'folders',
        where: parentId == null ? 'parent_id IS NULL' : 'parent_id = ?',
        whereArgs: parentId == null ? null : [parentId],
        orderBy: 'name COLLATE NOCASE ASC',
      );
      return rows.map(Folder.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('folder.listChildren échoué', cause: e);
    }
  }

  Future<void> insert(Folder folder) async {
    try {
      await _db.insert(
        'folders',
        folder.toRow(),
        conflictAlgorithm: ConflictAlgorithm.abort,
      );
    } catch (e) {
      throw DatabaseException('folder.insert échoué', cause: e);
    }
  }

  Future<void> update(Folder folder) async {
    try {
      final rows = await _db.update(
        'folders',
        folder.toRow(),
        where: 'id = ?',
        whereArgs: [folder.id],
      );
      if (rows == 0) throw FolderNotFoundException(folder.id);
    } on FolderNotFoundException {
      rethrow;
    } catch (e) {
      throw DatabaseException('folder.update échoué', cause: e);
    }
  }

  /// Suppression d'un dossier. Cascade SQL sur ses notes.
  /// Le dossier `inbox` est protégé.
  Future<void> delete(String id) async {
    if (id == 'inbox') {
      throw const ValidationException.coded(
        NotesErrorCode.inboxNotDeletable,
      );
    }
    try {
      await _db.delete('folders', where: 'id = ?', whereArgs: [id]);
    } catch (e) {
      throw DatabaseException('folder.delete échoué', cause: e);
    }
  }
}
