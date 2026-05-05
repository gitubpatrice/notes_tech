/// Accès direct à la table `note_embeddings`.
library;

import 'dart:typed_data';

import 'package:sqflite_sqlcipher/sqflite.dart' hide DatabaseException;

import '../../core/exceptions.dart';
import '../../utils/vector_math.dart';
import '../models/note_embedding.dart';

class EmbeddingsDao {
  EmbeddingsDao(this._db);
  final Database _db;

  Future<NoteEmbedding?> findByNoteId(String noteId) async {
    try {
      final rows = await _db.query(
        'note_embeddings',
        where: 'note_id = ?',
        whereArgs: [noteId],
        limit: 1,
      );
      if (rows.isEmpty) return null;
      return _fromRow(rows.first);
    } catch (e) {
      throw DatabaseException('emb.findByNoteId échoué', cause: e);
    }
  }

  /// Charge tous les embeddings d'un modèle donné.
  /// Pour 10 000 notes × 256 dim × 4 octets = ~10 Mo — OK en mémoire.
  Future<List<NoteEmbedding>> listByModel(String modelId) async {
    try {
      final rows = await _db.query(
        'note_embeddings',
        where: 'model_id = ?',
        whereArgs: [modelId],
      );
      return rows.map(_fromRow).toList(growable: false);
    } catch (e) {
      throw DatabaseException('emb.listByModel échoué', cause: e);
    }
  }

  /// Liste les `(note_id, source_hash)` connus, pour décider quoi (re)calculer.
  Future<Map<String, int>> listSourceHashes(String modelId) async {
    try {
      final rows = await _db.query(
        'note_embeddings',
        columns: ['note_id', 'source_hash'],
        where: 'model_id = ?',
        whereArgs: [modelId],
      );
      final out = <String, int>{};
      for (final r in rows) {
        out[r['note_id']! as String] = r['source_hash']! as int;
      }
      return out;
    } catch (e) {
      throw DatabaseException('emb.listSourceHashes échoué', cause: e);
    }
  }

  Future<void> upsert(NoteEmbedding e) async {
    try {
      await _db.insert(
        'note_embeddings',
        {
          'note_id': e.noteId,
          'vector': VectorMath.encodeBlob(e.vector),
          'dim': e.dim,
          'model_id': e.modelId,
          'source_hash': e.sourceHash,
          'updated_at': e.updatedAt.millisecondsSinceEpoch,
        },
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
    } catch (err) {
      throw DatabaseException('emb.upsert échoué', cause: err);
    }
  }

  /// Insertion en lot dans une transaction unique.
  Future<void> upsertBatch(Iterable<NoteEmbedding> items) async {
    if (items.isEmpty) return;
    try {
      await _db.transaction((txn) async {
        final batch = txn.batch();
        for (final e in items) {
          batch.insert(
            'note_embeddings',
            {
              'note_id': e.noteId,
              'vector': VectorMath.encodeBlob(e.vector),
              'dim': e.dim,
              'model_id': e.modelId,
              'source_hash': e.sourceHash,
              'updated_at': e.updatedAt.millisecondsSinceEpoch,
            },
            conflictAlgorithm: ConflictAlgorithm.replace,
          );
        }
        await batch.commit(noResult: true);
      });
    } catch (err) {
      throw DatabaseException('emb.upsertBatch échoué', cause: err);
    }
  }

  Future<int> deleteByNoteId(String noteId) async {
    try {
      return await _db.delete(
        'note_embeddings',
        where: 'note_id = ?',
        whereArgs: [noteId],
      );
    } catch (e) {
      throw DatabaseException('emb.deleteByNoteId échoué', cause: e);
    }
  }

  /// Supprime les embeddings dont le `note_id` n'est plus dans la liste
  /// d'IDs vivants. Chunke pour respecter la limite de variables SQLite.
  Future<int> deleteOrphans(Set<String> aliveIds) async {
    try {
      if (aliveIds.isEmpty) {
        // Toutes les notes ont disparu → on vide la table.
        return await _db.delete('note_embeddings');
      }
      // Implémentation via différence : on récupère les note_id existants
      // puis on supprime ceux absents de `aliveIds`.
      final existing = await _db.query(
        'note_embeddings',
        columns: ['note_id'],
      );
      final toDelete = <String>[];
      for (final r in existing) {
        final id = r['note_id']! as String;
        if (!aliveIds.contains(id)) toDelete.add(id);
      }
      if (toDelete.isEmpty) return 0;
      const chunkSize = 500;
      var total = 0;
      for (var start = 0; start < toDelete.length; start += chunkSize) {
        final end = (start + chunkSize).clamp(0, toDelete.length);
        final chunk = toDelete.sublist(start, end);
        final placeholders = List.filled(chunk.length, '?').join(',');
        total += await _db.delete(
          'note_embeddings',
          where: 'note_id IN ($placeholders)',
          whereArgs: chunk,
        );
      }
      return total;
    } catch (e) {
      throw DatabaseException('emb.deleteOrphans échoué', cause: e);
    }
  }

  /// Purge tout embedding qui n'appartient pas au modèle courant
  /// (utile lorsqu'on bascule LocalEmbedder → MiniLm).
  Future<int> deleteWhereModelNot(String currentModelId) async {
    try {
      return await _db.delete(
        'note_embeddings',
        where: 'model_id != ?',
        whereArgs: [currentModelId],
      );
    } catch (e) {
      throw DatabaseException('emb.deleteWhereModelNot échoué', cause: e);
    }
  }

  Future<int> count(String modelId) async {
    try {
      final r = await _db.rawQuery(
        'SELECT COUNT(*) AS c FROM note_embeddings WHERE model_id = ?',
        [modelId],
      );
      return (r.first['c'] as int?) ?? 0;
    } catch (e) {
      throw DatabaseException('emb.count échoué', cause: e);
    }
  }

  // ---------------------------------------------------------------------

  static NoteEmbedding _fromRow(Map<String, Object?> row) {
    final blob = row['vector']! as Uint8List;
    final dim = row['dim']! as int;
    final vector = VectorMath.decodeBlob(blob, expectedDim: dim);
    return NoteEmbedding(
      noteId: row['note_id']! as String,
      vector: vector,
      dim: dim,
      modelId: row['model_id']! as String,
      sourceHash: row['source_hash']! as int,
      updatedAt:
          DateTime.fromMillisecondsSinceEpoch(row['updated_at']! as int),
    );
  }
}
