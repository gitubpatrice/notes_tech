/// Accès direct à la table `note_links`.
library;

import 'package:sqflite_sqlcipher/sqflite.dart' hide DatabaseException;

import '../../core/exceptions.dart';
import '../models/note.dart';
import '../models/note_link.dart';

class LinksDao {
  LinksDao(this._db);
  final Database _db;

  // ---------------------------------------------------------------------
  // Réécriture atomique des liens d'une note (delete + insert dans 1 txn).
  // ---------------------------------------------------------------------

  /// Remplace tous les liens sortant de `sourceId` par `links`.
  /// Atomique : si l'insertion échoue, l'ancien set reste en place.
  Future<void> replaceLinksForSource(
    String sourceId,
    List<NoteLink> links,
  ) async {
    try {
      await _db.transaction((txn) async {
        await txn.delete(
          'note_links',
          where: 'source_id = ?',
          whereArgs: [sourceId],
        );
        if (links.isEmpty) return;
        final batch = txn.batch();
        for (final l in links) {
          batch.insert('note_links', l.toRow());
        }
        await batch.commit(noResult: true);
      });
    } catch (e) {
      throw DatabaseException('links.replaceForSource échoué', cause: e);
    }
  }

  // ---------------------------------------------------------------------
  // Lectures
  // ---------------------------------------------------------------------

  /// Liens sortants d'une note (ce qu'elle cite).
  Future<List<NoteLink>> outgoing(String sourceId) async {
    try {
      final rows = await _db.query(
        'note_links',
        where: 'source_id = ?',
        whereArgs: [sourceId],
        orderBy: 'position ASC',
      );
      return rows.map(NoteLink.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('links.outgoing échoué', cause: e);
    }
  }

  /// Notes qui mentionnent `targetId` (résolu) ou `targetTitleNorm`
  /// (non encore résolu mais matchant ce titre normalisé).
  Future<List<Note>> backlinkSources({
    required String targetId,
    required String targetTitleNorm,
  }) async {
    try {
      final rows = await _db.rawQuery(
        '''
        SELECT DISTINCT n.*
        FROM notes n
        JOIN note_links l ON l.source_id = n.id
        WHERE n.trashed_at IS NULL
          AND (l.target_id = ? OR
               (l.target_id IS NULL AND l.target_title_norm = ?))
        ORDER BY n.updated_at DESC
      ''',
        [targetId, targetTitleNorm],
      );
      return rows.map(Note.fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('links.backlinkSources échoué', cause: e);
    }
  }

  // ---------------------------------------------------------------------
  // Re-résolution sur création / renommage / suppression d'une note cible.
  // ---------------------------------------------------------------------

  /// Quand une note `noteId` apparaît avec le titre normalisé
  /// `titleNorm`, on attache son id à tous les liens fantômes qui
  /// pointaient vers ce titre.
  Future<int> resolveDangling({
    required String noteId,
    required String titleNorm,
  }) async {
    try {
      return await _db.update(
        'note_links',
        {'target_id': noteId},
        where: 'target_id IS NULL AND target_title_norm = ?',
        whereArgs: [titleNorm],
      );
    } catch (e) {
      throw DatabaseException('links.resolveDangling échoué', cause: e);
    }
  }

  /// Inverse : quand une note disparaît OU change de titre, on remet
  /// à NULL les target_id qui pointaient vers elle ET dont le
  /// target_title_norm ne matche plus son nouveau titre.
  /// (Le ON DELETE SET NULL côté FK couvre déjà la suppression directe ;
  /// cette méthode gère le cas du renommage.)
  Future<int> unresolveByMismatch({
    required String noteId,
    required String newTitleNorm,
  }) async {
    try {
      return await _db.update(
        'note_links',
        {'target_id': null},
        where: 'target_id = ? AND target_title_norm != ?',
        whereArgs: [noteId, newTitleNorm],
      );
    } catch (e) {
      throw DatabaseException('links.unresolveByMismatch échoué', cause: e);
    }
  }

  /// Compte total de liens pour debug / about.
  Future<int> count() async {
    try {
      final r = await _db.rawQuery('SELECT COUNT(*) AS c FROM note_links');
      return (r.first['c'] as int?) ?? 0;
    } catch (e) {
      throw DatabaseException('links.count échoué', cause: e);
    }
  }
}
