/// Façade au-dessus de EmbeddingsDao.
library;

import '../db/embeddings_dao.dart';
import '../models/note_embedding.dart';

class EmbeddingsRepository {
  EmbeddingsRepository(this._dao);
  final EmbeddingsDao _dao;

  Future<NoteEmbedding?> get(String noteId) => _dao.findByNoteId(noteId);
  Future<List<NoteEmbedding>> listByModel(String modelId) =>
      _dao.listByModel(modelId);
  Future<Map<String, int>> sourceHashes(String modelId) =>
      _dao.listSourceHashes(modelId);
  Future<void> save(NoteEmbedding e) => _dao.upsert(e);
  Future<void> saveAll(Iterable<NoteEmbedding> items) =>
      _dao.upsertBatch(items);
  Future<void> remove(String noteId) => _dao.deleteByNoteId(noteId);
  Future<int> deleteOrphans(Set<String> aliveIds) =>
      _dao.deleteOrphans(aliveIds);
  Future<int> purgeOtherModels(String currentModelId) =>
      _dao.deleteWhereModelNot(currentModelId);
  Future<int> count(String modelId) => _dao.count(modelId);
}
