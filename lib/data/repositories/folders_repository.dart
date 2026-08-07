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

  /// `true` si [id] désigne un dossier coffre.
  ///
  /// Mémorisé, et invalidé par `_emit()` — donc à chaque création, rename,
  /// suppression ou `update` de dossier, y compris l'activation d'un coffre
  /// qui passe par `update`. Le cache existe parce que ce prédicat est
  /// consulté à **chaque écriture de note** par la garde d'invariant de
  /// `NotesRepository` : sans lui, chaque frappe auto-sauvegardée paierait
  /// une requête de plus.
  /// ⚠️ LA COURSE QUE CETTE VERSION FERME, parce qu'elle rendait la garde
  /// d'invariant AVEUGLE.
  ///
  /// L'écriture était `_vaultIds ??= { for (final f in await ...) }`. Le `??=`
  /// évalue sa partie droite, qui contient un `await` : pendant cette
  /// suspension `_vaultIds` reste `null`. Si un coffre est créé dans cet
  /// intervalle, `_emit()` remet le cache à `null` — sans effet, il l'est
  /// déjà — puis l'`await` rend une liste ANTÉRIEURE à la création, et ce
  /// jeu périmé devient le cache.
  ///
  /// Conséquence : `isVaultFolder` répondait `false` pour un coffre qui
  /// venait d'être créé. La garde de `NotesRepository` laissait alors passer
  /// les écritures en clair de ses notes — jusqu'au prochain événement qui
  /// invalide le cache. C'est exactement le défaut que la garde existe pour
  /// empêcher. Relevé en CRITIQUE par une relecture externe (Gemini 3.1 Pro).
  ///
  /// Le compteur de génération tranche : si une invalidation est survenue
  /// pendant la lecture, le résultat n'est PAS mis en cache et la lecture est
  /// refaite. Bornée à trois tours — au-delà, on répond sur la lecture la
  /// plus fraîche sans rien cacher, ce qui reste correct et coûte seulement
  /// une requête de plus.
  Future<bool> isVaultFolder(String id) async {
    final cache = _vaultIds;
    if (cache != null) return cache.contains(id);
    Set<String> frais = const <String>{};
    for (var tour = 0; tour < 3; tour++) {
      final generation = _vaultIdsGeneration;
      frais = {
        for (final f in await _dao.listAll())
          if (f.isVault) f.id,
      };
      if (generation == _vaultIdsGeneration) {
        _vaultIds = frais;
        break;
      }
    }
    return frais.contains(id);
  }

  Set<String>? _vaultIds;

  /// Incrémenté à chaque invalidation. Sert uniquement à détecter qu'un
  /// changement est survenu PENDANT une lecture asynchrone du cache.
  int _vaultIdsGeneration = 0;
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
      throw const ValidationException.coded(NotesErrorCode.folderNameRequired);
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
      throw const ValidationException.coded(NotesErrorCode.folderNameRequired);
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

  /// Persiste l'ensemble des champs d'un [Folder] (rename, métadonnées,
  /// colonnes vault). Émet un `changes` event pour rafraîchir l'UI.
  /// Le caller est responsable de l'`updatedAt` (typiquement
  /// `folder.copyWith(updatedAt: DateTime.now())` avant appel).
  Future<Folder> update(Folder folder) async {
    await _dao.update(folder);
    _emit();
    return folder;
  }

  void _emit() {
    // Invalide AVANT de notifier : un écouteur qui réagit à l'event et
    // interroge `isVaultFolder` dans la foulée doit lire l'état neuf.
    // La génération signale en plus l'invalidation aux lectures EN COURS.
    _vaultIdsGeneration++;
    _vaultIds = null;
    if (!_changes.isClosed) _changes.add(null);
  }
}
