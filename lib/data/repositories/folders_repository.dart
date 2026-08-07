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
  /// refaite, bornée à trois tours.
  ///
  /// ⚠️ ET SI LES TROIS TOURS ÉCHOUENT, ON NE REND PAS LE DERNIER INSTANTANÉ.
  /// Une première version le faisait, en le disant « correct ». C'était faux :
  /// cet instantané est seulement le plus récent PARMI CEUX TENTÉS, pas
  /// cohérent avec la génération courante. Il pouvait donc répondre `false`
  /// pour un dossier devenu coffre — et la garde d'invariant laissait passer
  /// l'écriture en clair, ce qu'elle existe précisément pour empêcher.
  /// Relevé en CRITIQUE par une relecture externe (GPT-5.5) sur le correctif
  /// de course lui-même.
  ///
  /// On retombe alors sur une lecture CIBLÉE du seul dossier concerné —
  /// elle aussi encadrée par la génération.
  ///
  /// ⚠️ « FENÊTRE DE COURSE NÉGLIGEABLE » N'EST PAS UNE PROPRIÉTÉ DE
  /// SÉCURITÉ, et c'est ce que disait la version précédente de ce
  /// commentaire pour justifier une lecture ciblée NON encadrée. Une requête
  /// asynchrone ciblée peut rendre un résultat périmé exactement comme une
  /// requête large : plus rarement, pas jamais. Et un `false` périmé ouvre la
  /// garde. Relevé en CRITIQUE par une relecture externe (GPT-5.5) sur le
  /// correctif précédent, qui corrigeait déjà une course.
  ///
  /// Dernier recours : `true`, c'est-à-dire « traite-le comme un coffre ».
  /// Au pire une écriture légitime est refusée bruyamment ; jamais un secret
  /// écrit en clair silencieusement. Un dossier introuvable tombe dans ce cas
  /// — écrire une note dans un dossier qui n'existe plus n'est de toute façon
  /// pas une opération valide.
  Future<bool> isVaultFolder(String id) async {
    final cache = _vaultIds;
    if (cache != null) return cache.contains(id);
    for (var tour = 0; tour < 3; tour++) {
      final generation = _vaultIdsGeneration;
      final frais = {
        for (final f in await _dao.listAll())
          if (f.isVault) f.id,
      };
      if (generation == _vaultIdsGeneration) {
        _vaultIds = frais;
        return frais.contains(id);
      }
    }
    try {
      final generation = _vaultIdsGeneration;
      final folder = await _dao.findById(id);
      // Une invalidation pendant CETTE lecture la rend suspecte au même titre
      // que les précédentes : on ne rend pas un `false` qu'on ne peut pas
      // garantir.
      if (generation != _vaultIdsGeneration) return true;
      return folder?.isVault ?? true;
    } catch (_) {
      return true;
    }
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
    // Même raison que dans `update` : fermer la fenêtre entre la persistance
    // et la notification.
    _invalideCacheCoffres();
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
    // Le renommage ne touche pas au statut coffre, mais il invalide comme les
    // autres : un chemin d'écriture qui oublie de le faire est exactement le
    // genre de jumeau divergent qui rouvre la fenêtre.
    _invalideCacheCoffres();
    await _dao.update(updated);
    _emit();
    return updated;
  }

  Future<void> delete(String id) async {
    _invalideCacheCoffres();
    await _dao.delete(id);
    _emit();
  }

  /// Persiste l'ensemble des champs d'un [Folder] (rename, métadonnées,
  /// colonnes vault). Émet un `changes` event pour rafraîchir l'UI.
  /// Le caller est responsable de l'`updatedAt` (typiquement
  /// `folder.copyWith(updatedAt: DateTime.now())` avant appel).
  Future<Folder> update(Folder folder) async {
    // ⚠️ INVALIDER AVANT D'ECRIRE, pas seulement apres.
    //
    // `_emit()` ne tournait qu'apres le `await` : entre le moment ou la base
    // a persiste `isVault = true` et celui ou le cache est invalide, une
    // sauvegarde de note concurrente lisait encore l'ancien cache et
    // repondait « pas un coffre ». Elle ecrivait donc en clair dans un
    // dossier devenu coffre. Fenetre etroite — un `await` — mais reelle, et
    // c'est exactement ce que la garde existe pour empecher. Relevee par une
    // relecture externe (GPT-5.5).
    //
    // Invalider des maintenant ferme la fenetre : toute lecture qui commence
    // pendant l'ecriture verra une generation differente a son terme et ne
    // mettra pas son resultat en cache.
    _invalideCacheCoffres();
    await _dao.update(folder);
    _emit();
    return folder;
  }

  /// Invalide le cache des coffres sans notifier — utilise AVANT une ecriture
  /// pour fermer la fenetre entre la persistance et la notification.
  void _invalideCacheCoffres() {
    _vaultIdsGeneration++;
    _vaultIds = null;
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
