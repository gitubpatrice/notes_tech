// Tests de COMPORTEMENT de `NotesRepository` autour de l'invariant du coffre.
//
// « Une note d'un dossier coffre n'est jamais persistée en clair » était tenu
// par chaque appelant qui pensait à chiffrer. Deux chemins l'oubliaient, et le
// défaut ne se voyait ni à la lecture ni à l'usage :
//
//   - épingler ou mettre en favori une note de coffre OUVERTE passait
//     l'éphémère déchiffrée à un `UPDATE` pleine ligne, réécrivant `content`
//     en clair et effaçant `encrypted_content` ;
//   - la mise à la corbeille faisait la même chose.
//
// Dans les deux cas la note perdait sa protection définitivement, sur un tap
// d'icône, sans aucun signal. L'invariant est désormais porté par le
// repository, et ces tests le verrouillent.
//
// Le faux DAO n'est pas un mock de confort : il enregistre QUELLES colonnes
// chaque opération écrit. C'est exactement ce qui distingue le geste sûr du
// geste destructeur, et ce qu'aucun test de l'ancienne suite ne regardait.

import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/core/exceptions.dart';
import 'package:notes_tech/data/db/notes_dao.dart';
import 'package:notes_tech/data/models/note.dart';
import 'package:notes_tech/data/repositories/notes_repository.dart';

const _kVaultFolder = 'folder-coffre';
const _kPlainFolder = 'folder-ordinaire';

/// Enregistre les appels reçus au lieu de toucher SQLite. `noSuchMethod`
/// couvre le reste de la surface du DAO, qui n'intervient pas ici.
class _SpyNotesDao implements NotesDao {
  final calls = <String>[];
  Note? stored;
  Map<String, Object?>? lastFlags;
  Map<String, Object?>? lastTrash;

  @override
  Future<void> insert(Note note) async {
    calls.add('insert');
    stored = note;
  }

  @override
  Future<void> update(Note note) async {
    calls.add('update');
    stored = note;
  }

  @override
  Future<void> updateFlags({
    required String id,
    required DateTime updatedAt,
    bool? pinned,
    bool? favorite,
    bool? archived,
  }) async {
    calls.add('updateFlags');
    lastFlags = {
      'id': id,
      'pinned': pinned,
      'favorite': favorite,
      'archived': archived,
    };
  }

  @override
  Future<void> setTrashedAt({
    required String id,
    required DateTime updatedAt,
    DateTime? trashedAt,
  }) async {
    calls.add('setTrashedAt');
    lastTrash = {'id': id, 'trashedAt': trashedAt};
  }

  @override
  Future<Note?> findById(String id) async => stored;

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName} non attendu ici');
}

NotesRepository _repo(_SpyNotesDao dao) => NotesRepository(
  dao,
  isVaultFolder: (folderId) async => folderId == _kVaultFolder,
);

/// L'éphémère telle que `FolderVaultService.decryptNote` la rend et telle que
/// l'éditeur la détient : contenu en clair, `encryptedContent` purgé.
Note _ephemereDechiffree({bool pinned = false, bool favorite = false}) {
  final now = DateTime(2026, 8, 7);
  return Note(
    id: 'note-1',
    title: 'Banque',
    content: 'IBAN FR76 1234 5678',
    folderId: _kVaultFolder,
    createdAt: now,
    updatedAt: now,
    pinned: pinned,
    favorite: favorite,
  );
}

Note _chiffree() {
  final now = DateTime(2026, 8, 7);
  return Note(
    id: 'note-1',
    title: 'Banque',
    content: '',
    folderId: _kVaultFolder,
    createdAt: now,
    updatedAt: now,
    encryptedContent: Uint8List.fromList(List.filled(40, 7)),
  );
}

void main() {
  group('épingler / favori ne touche jamais au contenu', () {
    test(
      'togglePin sur une note de coffre ouverte passe par updateFlags',
      () async {
        final dao = _SpyNotesDao();
        await _repo(dao).togglePin(_ephemereDechiffree());

        expect(
          dao.calls,
          ['updateFlags'],
          reason:
              'Un `update` ici réécrirait la ligne entière depuis toRow() : '
              'content en clair et encrypted_content à NULL. La note perdrait '
              'sa protection au repos sur un simple tap sur l\'épingle.',
        );
        expect(dao.lastFlags?['pinned'], isTrue);
        expect(dao.stored, isNull, reason: 'aucune ligne complète écrite');
      },
    );

    test('toggleFavorite idem', () async {
      final dao = _SpyNotesDao();
      await _repo(dao).toggleFavorite(_ephemereDechiffree());
      expect(dao.calls, ['updateFlags']);
      expect(dao.lastFlags?['favorite'], isTrue);
      expect(dao.stored, isNull);
    });

    test('les drapeaux non visés gardent leur valeur', () async {
      final dao = _SpyNotesDao();
      await _repo(dao).togglePin(_ephemereDechiffree(favorite: true));
      expect(dao.lastFlags?['favorite'], isTrue);
    });
  });

  group('corbeille : pas de déchiffrement au passage', () {
    test('moveToTrash passe par setTrashedAt', () async {
      final dao = _SpyNotesDao();
      await _repo(dao).moveToTrash(_ephemereDechiffree());
      expect(
        dao.calls,
        ['setTrashedAt'],
        reason:
            'Sinon la note atterrissait EN CLAIR dans la corbeille, où '
            'trash_screen — qui masque le titre selon isLocked — l\'affichait '
            'alors en clair.',
      );
      expect(dao.lastTrash?['trashedAt'], isNotNull);
      expect(dao.stored, isNull);
    });

    test(
      'restoreFromTrash efface l\'horodatage sans réécrire la ligne',
      () async {
        final dao = _SpyNotesDao();
        await _repo(dao).restoreFromTrash(_ephemereDechiffree());
        expect(dao.calls, ['setTrashedAt']);
        expect(dao.lastTrash?['trashedAt'], isNull);
        expect(dao.stored, isNull);
      },
    );
  });

  group('la garde refuse le clair dans un coffre', () {
    test('save d\'une note en clair dans un coffre lève', () async {
      final dao = _SpyNotesDao();
      await expectLater(
        _repo(dao).save(_ephemereDechiffree()),
        throwsA(isA<VaultPlaintextWriteException>()),
      );
      expect(dao.calls, isEmpty, reason: 'rien ne doit atteindre le disque');
    });

    test('create avec du contenu dans un coffre lève', () async {
      final dao = _SpyNotesDao();
      await expectLater(
        _repo(dao).create(folderId: _kVaultFolder, content: 'secret'),
        throwsA(isA<VaultPlaintextWriteException>()),
      );
      expect(dao.calls, isEmpty);
    });

    test('l\'exception nomme la note, le dossier et l\'opération', () async {
      try {
        await _repo(_SpyNotesDao()).save(_ephemereDechiffree());
        fail('aurait dû lever');
      } on VaultPlaintextWriteException catch (e) {
        expect(e.noteId, 'note-1');
        expect(e.folderId, _kVaultFolder);
        expect(e.operation, 'save');
      }
    });
  });

  group('la garde laisse passer ce qui est légitime', () {
    test('une note déjà chiffrée passe', () async {
      final dao = _SpyNotesDao();
      await _repo(dao).save(_chiffree());
      expect(dao.calls, contains('update'));
    });

    test('une note en clair hors coffre passe', () async {
      final dao = _SpyNotesDao();
      final now = DateTime(2026, 8, 7);
      await _repo(dao).save(
        Note(
          id: 'n2',
          title: 'Courses',
          content: 'pain',
          folderId: _kPlainFolder,
          createdAt: now,
          updatedAt: now,
        ),
      );
      expect(dao.calls, contains('update'));
    });

    test(
      'la note neuve et vide d\'un coffre passe, elle est chiffrée après',
      () async {
        final dao = _SpyNotesDao();
        await _repo(dao).create(folderId: _kVaultFolder);
        expect(
          dao.calls,
          ['insert'],
          reason:
              'HomeScreen crée puis chiffre dans la foulée : bloquer ici '
              'casserait la création de note dans un coffre.',
        );
      },
    );

    test('allowPlaintextInVault ouvre la seule porte prévue', () async {
      final dao = _SpyNotesDao();
      await _repo(dao).save(_ephemereDechiffree(), allowPlaintextInVault: true);
      expect(
        dao.calls,
        contains('update'),
        reason:
            'decryptAllNotesInFolder persiste volontairement en clair '
            'avant la suppression d\'un coffre.',
      );
    });
  });

  group('sans prédicat câblé, la garde est inerte mais pas permissive', () {
    test('aucun isVaultFolder : le repository écrit sans juger', () async {
      final dao = _SpyNotesDao();
      await NotesRepository(dao).save(_ephemereDechiffree());
      expect(dao.calls, contains('update'));
    });
  });
}
