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
import 'dart:typed_data';

import 'package:uuid/uuid.dart';

import '../../core/constants.dart';
import '../../core/exceptions.dart';
import '../db/notes_dao.dart';
import '../models/note.dart';
import '../models/note_change.dart';

class NotesRepository {
  /// [isVaultFolder] porte l'invariant du coffre au point d'écriture. Câblé
  /// depuis `main.dart` sur `FoldersRepository.isVaultFolder`. Laissé nul
  /// dans les tests qui ne mettent aucun coffre en jeu — la garde est alors
  /// inerte, jamais permissive par accident sur un chemin de production.
  NotesRepository(
    this._dao, {
    Future<bool> Function(String folderId)? isVaultFolder,
  }) : _isVaultFolder = isVaultFolder;

  final NotesDao _dao;
  final Future<bool> Function(String folderId)? _isVaultFolder;
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

  /// Toutes les notes vivantes (hors corbeille). Utilisé par l'indexation
  /// d'embeddings — pas par l'UI.
  Future<List<Note>> listAllAlive() => _dao.listAllAlive();

  Future<Set<String>> listSemanticIneligibleIds() =>
      _dao.listSemanticIneligibleIds();

  Future<List<Note>> listPlaintextInFolder(String folderId) =>
      _dao.listPlaintextInFolder(folderId);

  /// Réparation d'arrière-plan uniquement — voir
  /// `NotesDao.replaceContentPayload`. Ne passe volontairement ni par la
  /// garde d'invariant (le blob est fourni par l'appelant, donc l'écriture
  /// est chiffrée par construction) ni par `updatedAt` (une réparation ne
  /// doit pas réordonner la liste de l'utilisateur) ni par le stream de
  /// changements (l'appelant purge lui-même l'embedding concerné).
  Future<void> replaceContentPayload({
    required String id,
    required String content,
    required Uint8List? encryptedContent,
  }) => _dao.replaceContentPayload(
    id: id,
    content: content,
    encryptedContent: encryptedContent,
  );

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
  }) => _dao.findByTitleLike(lowerNeedle, limit: limit, excludeId: excludeId);

  // ---------------------------------------------------------------------
  // Écriture
  // ---------------------------------------------------------------------

  /// Refuse de persister le contenu EN CLAIR d'une note appartenant à un
  /// dossier coffre.
  ///
  /// C'est LE point où l'invariant est tenu. Avant, il reposait sur cinq
  /// sites d'appel qui devaient penser à `encryptNote` — il en manquait
  /// deux, et le défaut ne se voyait ni à la lecture ni à l'usage.
  ///
  /// Trois sorties immédiates avant tout accès disque, pour que la garde ne
  /// coûte rien sur le chemin chaud de l'auto-save :
  ///   1. la note porte déjà un blob chiffré → rien à protéger ;
  ///   2. son contenu est vide → rien à fuiter (cas de la note neuve, qui
  ///      est créée puis chiffrée dans la foulée) ;
  ///   3. aucun prédicat câblé → garde inerte (tests hors coffre).
  Future<void> _guardVaultPlaintext(Note note, String operation) async {
    if (note.encryptedContent != null) return;
    if (note.content.isEmpty) return;
    final isVault = _isVaultFolder;
    if (isVault == null) return;
    if (!await isVault(note.folderId)) return;
    throw VaultPlaintextWriteException(
      noteId: note.id,
      folderId: note.folderId,
      operation: operation,
    );
  }

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
    await _guardVaultPlaintext(note, 'create');
    await _dao.insert(note);
    _emit(
      NoteChangeEvent(
        kind: NoteChangeKind.created,
        id: note.id,
        currentTitle: note.title,
      ),
    );
    return note;
  }

  /// Sauvegarde idempotente : récupère le titre précédent pour permettre
  /// aux services aval (backlinks) de détecter un renommage.
  ///
  /// [allowPlaintextInVault] lève la garde d'invariant. **Un seul appelant
  /// légitime** : `FolderVaultService.decryptAllNotesInFolder`, qui persiste
  /// délibérément en clair avant la suppression d'un coffre — sans quoi ses
  /// notes atterriraient dans la boîte de réception sans la clé qui les
  /// déchiffrait. Tout nouvel usage de ce drapeau doit être justifié ici.
  Future<Note> save(Note note, {bool allowPlaintextInVault = false}) async {
    _validateTitle(note.title);
    if (!allowPlaintextInVault) await _guardVaultPlaintext(note, 'save');
    final previous = await _dao.findById(note.id);
    final updated = note.copyWith(updatedAt: DateTime.now());
    await _dao.update(updated);
    _emit(
      NoteChangeEvent(
        kind: NoteChangeKind.updated,
        id: updated.id,
        previousTitle: previous?.title,
        currentTitle: updated.title,
      ),
    );
    return updated;
  }

  Future<Note> togglePin(Note note) =>
      _toggleFlag(note, note.copyWith(pinned: !note.pinned));

  Future<Note> toggleFavorite(Note note) =>
      _toggleFlag(note, note.copyWith(favorite: !note.favorite));

  /// ⚠️ Écrit UNIQUEMENT les drapeaux via `updateFlags`, jamais la ligne
  /// entière. Épingler ou mettre en favori une note de coffre ouverte
  /// passait ici avec l'éphémère déchiffrée et réécrivait son `content` en
  /// clair en effaçant `encrypted_content` : la note perdait sa protection
  /// pour de bon, silencieusement.
  Future<Note> _toggleFlag(Note original, Note candidate) async {
    final updated = candidate.copyWith(updatedAt: DateTime.now());
    await _dao.updateFlags(
      id: updated.id,
      updatedAt: updated.updatedAt,
      pinned: updated.pinned,
      favorite: updated.favorite,
      archived: updated.archived,
    );
    _emit(
      NoteChangeEvent(
        kind: NoteChangeKind.updated,
        id: updated.id,
        previousTitle: original.title,
        currentTitle: updated.title,
      ),
    );
    return updated;
  }

  /// Même précaution que `_toggleFlag` : l'éditeur appelle ceci avec
  /// l'éphémère déchiffrée, une réécriture pleine ligne déposait la note
  /// EN CLAIR dans la corbeille — où `trash_screen`, qui masque le titre
  /// selon `isLocked`, l'affichait alors en clair.
  Future<void> moveToTrash(Note note) async {
    final now = DateTime.now();
    await _dao.setTrashedAt(id: note.id, updatedAt: now, trashedAt: now);
    // Une note en corbeille disparaît de toutes les vues vivantes :
    // on la traite comme une suppression côté indexation/backlinks.
    _emit(
      NoteChangeEvent(
        kind: NoteChangeKind.deleted,
        id: note.id,
        previousTitle: note.title,
      ),
    );
  }

  Future<void> restoreFromTrash(Note note) async {
    await _dao.setTrashedAt(id: note.id, updatedAt: DateTime.now());
    _emit(
      NoteChangeEvent(
        kind: NoteChangeKind.created,
        id: note.id,
        currentTitle: note.title,
      ),
    );
  }

  Future<void> deletePermanently(String id) async {
    final note = await _dao.findById(id);
    await _dao.deleteHard(id);
    _emit(
      NoteChangeEvent(
        kind: NoteChangeKind.deleted,
        id: id,
        previousTitle: note?.title,
      ),
    );
  }

  /// Réassigne en une seule transaction toutes les notes du dossier
  /// [fromFolderId] vers [toFolderId] — y compris notes archivées et en
  /// corbeille. Préserve la rétention 30 jours en évitant le
  /// `ON DELETE CASCADE` qui suivrait la suppression du dossier source.
  ///
  /// Émet un seul `NoteChangeEvent.bulk` (déclenche réindexation MiniLM
  /// + reload UI sans surcharger le coordinateur d'embeddings avec N
  /// events successifs).
  Future<int> reassignFolder({
    required String fromFolderId,
    required String toFolderId,
  }) async {
    final n = await _dao.reassignFolder(
      fromFolderId: fromFolderId,
      toFolderId: toFolderId,
    );
    if (n > 0) _emit(NoteChangeEvent.bulk);
    return n;
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
      throw const ValidationException.coded(NotesErrorCode.noteTitleTooLong);
    }
  }

  void _emit(NoteChangeEvent event) {
    if (!_changes.isClosed) _changes.add(event);
  }
}
