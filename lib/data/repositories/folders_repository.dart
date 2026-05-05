/// Repository façade au-dessus de FoldersDao.
library;

import 'dart:async';

import 'package:uuid/uuid.dart';

import '../../core/exceptions.dart';
import '../db/folders_dao.dart';
import '../models/folder.dart';

class FoldersRepository {
  FoldersRepository(this._dao);
  final FoldersDao _dao;
  static const _uuid = Uuid();
  final _changes = StreamController<void>.broadcast();

  Stream<void> get changes => _changes.stream;
  void dispose() => _changes.close();

  Future<Folder?> get(String id) => _dao.findById(id);
  Future<List<Folder>> listAll() => _dao.listAll();
  Future<List<Folder>> children(String? parentId) =>
      _dao.listChildren(parentId);

  Future<Folder> create({
    required String name,
    String? parentId,
    int? color,
    String? icon,
  }) async {
    final trimmed = name.trim();
    if (trimmed.isEmpty) {
      throw const ValidationException('Le nom du dossier est requis');
    }
    final now = DateTime.now();
    final folder = Folder(
      id: _uuid.v4(),
      name: trimmed,
      parentId: parentId,
      color: color,
      icon: icon,
      createdAt: now,
      updatedAt: now,
    );
    await _dao.insert(folder);
    _emit();
    return folder;
  }

  Future<Folder> rename(Folder folder, String newName) async {
    final trimmed = newName.trim();
    if (trimmed.isEmpty) {
      throw const ValidationException('Le nom du dossier est requis');
    }
    final updated = folder.copyWith(name: trimmed, updatedAt: DateTime.now());
    await _dao.update(updated);
    _emit();
    return updated;
  }

  Future<void> delete(String id) async {
    await _dao.delete(id);
    _emit();
  }

  void _emit() {
    if (!_changes.isClosed) _changes.add(null);
  }
}
