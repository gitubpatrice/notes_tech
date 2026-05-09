/// Accès direct aux tables `notes` et `notes_fts`.
///
/// Aucun couplage UI. Les exceptions remontent en `DatabaseException`.
library;

import 'package:sqflite_sqlcipher/sqflite.dart' hide DatabaseException;

import '../../core/exceptions.dart';
import '../models/note.dart';

class NotesDao {
  NotesDao(this._db);
  final Database _db;

  /// Récupère plusieurs notes par leur id en une seule requête.
  /// L'ordre du résultat n'est pas garanti — l'appelant doit re-trier
  /// selon ses besoins (ex. score de similarité).
  Future<List<Note>> findManyByIds(List<String> ids) async {
    if (ids.isEmpty) return const [];
    // SQLite limite SQLITE_MAX_VARIABLE_NUMBER (≈999 par défaut).
    // On chunke en 500 pour rester confortable.
    const chunkSize = 500;
    final out = <Note>[];
    try {
      for (var start = 0; start < ids.length; start += chunkSize) {
        final end = (start + chunkSize).clamp(0, ids.length);
        final chunk = ids.sublist(start, end);
        final placeholders = List.filled(chunk.length, '?').join(',');
        final rows = await _db.query(
          'notes',
          where: 'id IN ($placeholders)',
          whereArgs: chunk,
        );
        out.addAll(rows.map(Note.fromRow));
      }
      return out;
    } catch (e) {
      throw DatabaseException('findManyByIds échoué', cause: e);
    }
  }

  Future<Note?> findById(String id) async {
    try {
      final rows = await _db.query(
        'notes',
        where: 'id = ?',
        whereArgs: [id],
        limit: 1,
      );
      if (rows.isEmpty) return null;
      return Note.fromRow(rows.first);
    } catch (e) {
      throw DatabaseException('findById($id) échoué', cause: e);
    }
  }

  Future<List<Note>> listByFolder(
    String folderId, {
    required NoteSortMode sort,
    bool includeArchived = false,
    int? limit,
  }) async {
    final where = StringBuffer('folder_id = ? AND trashed_at IS NULL');
    final args = <Object?>[folderId];
    if (!includeArchived) where.write(' AND archived = 0');
    try {
      final rows = await _db.query(
        'notes',
        where: where.toString(),
        whereArgs: args,
        orderBy: sort.sqlOrderBy,
        limit: limit,
      );
      return rows.map(Note.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('listByFolder($folderId) échoué', cause: e);
    }
  }

  /// Toutes les notes hors corbeille, archives incluses.
  /// Utilisé par l'indexeur d'embeddings.
  Future<List<Note>> listAllAlive() async {
    try {
      final rows = await _db.query(
        'notes',
        where: 'trashed_at IS NULL',
        orderBy: 'updated_at DESC',
      );
      return rows.map(Note.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('listAllAlive échoué', cause: e);
    }
  }

  Future<List<Note>> listRecent({required int limit}) async {
    try {
      final rows = await _db.query(
        'notes',
        where: 'trashed_at IS NULL AND archived = 0',
        orderBy: 'updated_at DESC',
        limit: limit,
      );
      return rows.map(Note.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('listRecent échoué', cause: e);
    }
  }

  Future<List<Note>> listFavorites({int? limit}) async {
    try {
      final rows = await _db.query(
        'notes',
        where: 'favorite = 1 AND trashed_at IS NULL AND archived = 0',
        orderBy: 'updated_at DESC',
        limit: limit,
      );
      return rows.map(Note.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('listFavorites échoué', cause: e);
    }
  }

  Future<List<Note>> listTrash() async {
    try {
      final rows = await _db.query(
        'notes',
        where: 'trashed_at IS NOT NULL',
        orderBy: 'trashed_at DESC',
      );
      return rows.map(Note.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('listTrash échoué', cause: e);
    }
  }

  Future<int> count(String folderId) async {
    try {
      final r = await _db.rawQuery(
        'SELECT COUNT(*) AS c FROM notes WHERE folder_id = ? AND trashed_at IS NULL',
        [folderId],
      );
      return (r.first['c'] as int?) ?? 0;
    } catch (e) {
      throw DatabaseException('count($folderId) échoué', cause: e);
    }
  }

  Future<void> insert(Note note) async {
    try {
      await _db.insert(
        'notes',
        note.toRow(),
        conflictAlgorithm: ConflictAlgorithm.abort,
      );
    } catch (e) {
      throw DatabaseException('insert(${note.id}) échoué', cause: e);
    }
  }

  Future<void> update(Note note) async {
    try {
      final rows = await _db.update(
        'notes',
        note.toRow(),
        where: 'id = ?',
        whereArgs: [note.id],
      );
      if (rows == 0) throw NoteNotFoundException(note.id);
    } on NoteNotFoundException {
      rethrow;
    } catch (e) {
      throw DatabaseException('update(${note.id}) échoué', cause: e);
    }
  }

  Future<void> deleteHard(String id) async {
    try {
      await _db.delete('notes', where: 'id = ?', whereArgs: [id]);
    } catch (e) {
      throw DatabaseException('deleteHard($id) échoué', cause: e);
    }
  }

  /// Réassigne en une seule transaction TOUTES les notes du dossier
  /// [fromFolderId] vers [toFolderId], **y compris** les notes en
  /// corbeille (`trashed_at IS NOT NULL`) et archivées.
  ///
  /// C'est la garantie nécessaire avant `FoldersRepository.delete(id)` :
  /// le `ON DELETE CASCADE` SQL effacerait sinon définitivement les notes
  /// en corbeille, bypassant la rétention 30 jours.
  ///
  /// Met à jour `updated_at` en bloc pour que la liste reflète l'opération.
  /// Retourne le nombre de notes effectivement déplacées.
  Future<int> reassignFolder({
    required String fromFolderId,
    required String toFolderId,
  }) async {
    if (fromFolderId == toFolderId) return 0;
    try {
      return await _db.update(
        'notes',
        {
          'folder_id': toFolderId,
          'updated_at': DateTime.now().millisecondsSinceEpoch,
        },
        where: 'folder_id = ?',
        whereArgs: [fromFolderId],
      );
    } catch (e) {
      throw DatabaseException(
        'reassignFolder($fromFolderId → $toFolderId) échoué',
        cause: e,
      );
    }
  }

  Future<int> purgeTrashOlderThan(DateTime cutoff) async {
    try {
      return await _db.delete(
        'notes',
        where: 'trashed_at IS NOT NULL AND trashed_at < ?',
        whereArgs: [cutoff.millisecondsSinceEpoch],
      );
    } catch (e) {
      throw DatabaseException('purgeTrash échoué', cause: e);
    }
  }

  /// Auto-complétion par titre. Filtre case-insensitif côté SQLite
  /// (`LOWER(title) LIKE ?`). L'appelant raffine ensuite en Dart pour
  /// gérer la sensibilité aux diacritiques. `lowerNeedle` doit déjà
  /// être en lowercase ; les méta-caractères LIKE (`%` `_` `\`) sont
  /// échappés ici. Le pattern matché est `lowerNeedle%` OU `% lowerNeedle%`
  /// (préfixe de mot).
  Future<List<Note>> findByTitleLike(
    String lowerNeedle, {
    required int limit,
    String? excludeId,
  }) async {
    final cleaned = lowerNeedle.trim();
    if (cleaned.isEmpty) return const <Note>[];
    final escaped = _escapeLike(cleaned);
    final whereParts = <String>[
      "(LOWER(title) LIKE ? ESCAPE '\\' OR LOWER(title) LIKE ? ESCAPE '\\')",
      'trashed_at IS NULL',
    ];
    final args = <Object?>['$escaped%', '% $escaped%'];
    if (excludeId != null) {
      whereParts.add('id <> ?');
      args.add(excludeId);
    }
    try {
      final rows = await _db.query(
        'notes',
        where: whereParts.join(' AND '),
        whereArgs: args,
        orderBy: 'updated_at DESC',
        limit: limit,
      );
      return rows.map(Note.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('findByTitleLike échoué', cause: e);
    }
  }

  /// Échappe `%`, `_` et `\` pour usage avec `ESCAPE '\\'`.
  static String _escapeLike(String s) =>
      s.replaceAll(r'\', r'\\').replaceAll('%', r'\%').replaceAll('_', r'\_');

  // ---------------------------------------------------------------------
  // Recherche FTS5
  // ---------------------------------------------------------------------

  /// Token autorisant le suffixe `*` côté FTS5 (caractères Unicode lettres/chiffres).
  static final RegExp _ftsPrefixable = RegExp(
    r'^[\p{L}\p{N}]+$',
    unicode: true,
  );

  /// Recherche plein-texte dans les notes non corbeille / non archivées.
  /// Le préfixe `*` est ajouté au dernier token pour la recherche incrémentale.
  Future<List<Note>> search(String query, {required int limit}) async {
    final fts = _buildFtsMatch(query);
    if (fts.isEmpty) return const <Note>[];
    try {
      final rows = await _db.rawQuery(
        '''
        SELECT n.*
        FROM notes n
        JOIN notes_fts f ON f.rowid = n.rowid
        WHERE notes_fts MATCH ?
          AND n.trashed_at IS NULL
          AND n.archived = 0
        ORDER BY bm25(notes_fts), n.updated_at DESC
        LIMIT ?;
      ''',
        [fts, limit],
      );
      return rows.map(Note.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('search échoué', cause: e);
    }
  }

  /// Construit une expression MATCH FTS5 sûre.
  /// Échappe les guillemets et préfixe le dernier token avec `*`.
  static String _buildFtsMatch(String raw) {
    final cleaned = raw.trim();
    if (cleaned.isEmpty) return '';
    final tokens = cleaned
        .split(RegExp(r'\s+'))
        .where((t) => t.isNotEmpty)
        .map((t) => t.replaceAll('"', '""'))
        .toList(growable: false);
    if (tokens.isEmpty) return '';
    final quoted = <String>[];
    for (var i = 0; i < tokens.length; i++) {
      final isLast = i == tokens.length - 1;
      // Préfixe seulement si le token est alphanumérique (FTS5 limitation).
      final t = tokens[i];
      final canPrefix = isLast && _ftsPrefixable.hasMatch(t);
      quoted.add(canPrefix ? '"$t"*' : '"$t"');
    }
    return quoted.join(' ');
  }
}
