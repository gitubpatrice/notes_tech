// Tests SUR APPAREIL de l'invariant du coffre.
//
// Pourquoi sur appareil et pas en pur Dart : la chaîne de sécurité de cette
// application ne s'instancie nulle part ailleurs. SQLCipher, le Keystore
// Android, Argon2id, les triggers FTS5 — rien de tout ça n'existe dans un
// `flutter test`. Or c'est exactement là que se logent les défauts : le bug
// qui déchiffrait une note de coffre à l'épinglage écrivait dans la VRAIE
// base, et aucun des 120 tests pure-Dart ne pouvait le voir.
//
// Le test 5 est celui qui compte le plus : il écrit à la main une note en
// clair dans un coffre — l'état exact que produisait le bug — puis vérifie
// que le déverrouillage la reprotège.
//
// ISOLATION : `useFileNameOverride` fait travailler ces tests dans un fichier
// de base distinct. Ils ne lisent ni n'écrivent jamais les données réelles de
// l'appareil.
//
// 🔴 DANGER — CES TESTS PEUVENT EFFACER LES DONNÉES DE L'APPLICATION.
//
// Le build de test est un `assembleDebug`. Si l'appareil porte une version
// dont le versionCode est supérieur (typiquement un APK de release), Flutter
// échoue en `INSTALL_FAILED_VERSION_DOWNGRADE` puis **DÉSINSTALLE la version
// en place** pour pouvoir continuer — emportant toutes les notes avec elle.
// Constaté le 2026-08-07 sur le S9.
//
// ⇒ À lancer UNIQUEMENT sur un appareil de test, JAMAIS sur un téléphone
//   porteur de données réelles, et TOUJOURS avec `-d <deviceId>` explicite :
//   sans `-d`, la commande vise tous les appareils connectés.
//
//   flutter test integration_test/vault_invariant_test.dart -d 22dbb7390a057ece
//
// (`22dbb7390a057ece` = Galaxy S9 de test. Le S24 FE `RZCY41EGKYL` est le
//  téléphone réel : ne jamais le viser ici.)

import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:notes_tech/core/exceptions.dart';
import 'package:notes_tech/data/db/database.dart';
import 'package:notes_tech/data/db/folders_dao.dart';
import 'package:notes_tech/data/db/notes_dao.dart';
import 'package:notes_tech/data/models/folder.dart';
import 'package:notes_tech/data/models/note.dart';
import 'package:notes_tech/data/repositories/folders_repository.dart';
import 'package:notes_tech/data/repositories/notes_repository.dart';
import 'package:notes_tech/services/security/folder_vault_service.dart';
import 'package:sqflite_sqlcipher/sqflite.dart';

/// Passphrase de test. Argon2id coûte 3-5 s sur un S9 : on la réutilise pour
/// tout le fichier plutôt que d'en dériver une par test.
const _kPass = 'passphrase-de-test-solide-42';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  late Database db;
  late NotesDao notesDao;
  late NotesRepository notes;
  late FoldersRepository folders;
  late FolderVaultService vault;

  setUpAll(() async {
    AppDatabase.instance.useFileNameOverride('notes_tech_integration_test.db');
    db = await AppDatabase.instance.db;
    notesDao = NotesDao(db);
    folders = FoldersRepository(FoldersDao(db));
    notes = NotesRepository(notesDao, isVaultFolder: folders.isVaultFolder);
    vault = FolderVaultService(folders: folders, notes: notes);
  });

  /// Lit la LIGNE BRUTE, sans passer par les repositories : c'est le seul
  /// point de vue qui dit la vérité sur ce qui est écrit au repos.
  Future<Map<String, Object?>> rawRow(String noteId) async {
    final rows = await db.query('notes', where: 'id = ?', whereArgs: [noteId]);
    expect(rows, hasLength(1), reason: 'note $noteId introuvable en base');
    return rows.first;
  }

  /// Crée un coffre déverrouillé contenant une note chiffrée.
  Future<(Folder, Note)> vaultWithNote(String label, String secret) async {
    final plain = await folders.create(name: 'coffre-$label');
    final v = await vault.createVault(folder: plain, passphrase: _kPass);
    final created = await notes.create(folderId: v.id, title: 'titre-$label');
    final encrypted = await vault.encryptNote(
      created.copyWith(content: secret),
    );
    await notes.save(encrypted);
    return (v, encrypted);
  }

  testWidgets('1. épingler ne déchiffre pas la note au repos', (_) async {
    final (_, note) = await vaultWithNote('pin', 'IBAN FR76 1111 2222');

    await notes.togglePin(note);

    final row = await rawRow(note.id);
    expect(
      row['encrypted_content'],
      isNotNull,
      reason:
          'LE bug : `_dao.update` écrivait la ligne entière depuis '
          'l\'éphémère déchiffrée et effaçait le blob. La note perdait sa '
          'protection au repos sur un tap d\'icône.',
    );
    expect(row['content'], '');
    expect(
      row['pinned'],
      1,
      reason: 'l\'épinglage doit quand même avoir eu lieu',
    );
  });

  testWidgets('2. mettre à la corbeille ne déchiffre pas non plus', (_) async {
    final (_, note) = await vaultWithNote('trash', 'code carte 4321');

    await notes.moveToTrash(note);

    final row = await rawRow(note.id);
    expect(row['encrypted_content'], isNotNull);
    expect(row['content'], '');
    expect(row['trashed_at'], isNotNull);
  });

  testWidgets('3. la garde refuse une écriture en clair dans un coffre', (
    _,
  ) async {
    final (v, note) = await vaultWithNote('garde', 'secret');

    await expectLater(
      notes.save(
        note.copyWith(content: 'écrit en clair', clearEncrypted: true),
      ),
      throwsA(isA<VaultPlaintextWriteException>()),
    );

    // Et la base n'a pas bougé.
    final row = await rawRow(note.id);
    expect(row['encrypted_content'], isNotNull);
    expect(row['content'], '');
    expect(v.isVault, isTrue);
  });

  testWidgets('4. round-trip réel : ce qui est chiffré se relit', (_) async {
    const secret = 'phrase secrète avec accents éàü et 漢字';
    final (_, note) = await vaultWithNote('roundtrip', secret);

    final fromDb = await notes.get(note.id);
    expect(fromDb!.isLocked, isTrue);
    final clear = await vault.decryptNote(fromDb);
    expect(clear.content, secret);
  });

  testWidgets('5. le déverrouillage reprotège une note laissée en clair', (
    _,
  ) async {
    const secret = 'contenu exposé par le bug d\'épinglage';
    final (v, note) = await vaultWithNote('repair', secret);

    // Reproduit EXACTEMENT l'état que produisait le bug : contenu en clair
    // dans la colonne, blob effacé. Écrit au niveau DAO pour contourner la
    // garde — c'est une base héritée qu'on simule, pas un chemin de code.
    await notesDao.replaceContentPayload(
      id: note.id,
      content: secret,
      encryptedContent: null,
    );
    final avant = await rawRow(note.id);
    expect(avant['content'], secret, reason: 'l\'état abîmé est bien en place');
    expect(avant['encrypted_content'], isNull);

    final updatedAtAvant = avant['updated_at'];

    vault.lock(v.id);
    await vault.unlock(folder: (await folders.get(v.id))!, passphrase: _kPass);

    final apres = await rawRow(note.id);
    expect(
      apres['content'],
      '',
      reason: 'la réparation silencieuse doit avoir effacé le clair',
    );
    expect(apres['encrypted_content'], isNotNull);
    expect(
      apres['updated_at'],
      updatedAtAvant,
      reason:
          'une réparation silencieuse ne doit pas réordonner la liste '
          '« modifiées récemment » de l\'utilisateur',
    );

    // Et le contenu réparé est bien le bon.
    final relue = await notes.get(note.id);
    final clear = await vault.decryptNote(relue!);
    expect(clear.content, secret);
  });

  testWidgets('6. coffre verrouillé : le contenu est illisible', (_) async {
    final (v, note) = await vaultWithNote('lock', 'top secret');

    vault.lock(v.id);

    final fromDb = await notes.get(note.id);
    expect(fromDb!.content, '');
    expect(fromDb.isLocked, isTrue);
    await expectLater(
      vault.decryptNote(fromDb),
      throwsA(isA<VaultLockedException>()),
    );
    // Le blob ne doit rien laisser filtrer en clair.
    final blob = fromDb.encryptedContent!;
    expect(blob.length, greaterThan(12 + 16));
    expect(
      String.fromCharCodes(Uint8List.sublistView(blob)),
      isNot(contains('top secret')),
    );
  });

  testWidgets('7. l\'index FTS5 ne contient pas le contenu du coffre', (
    _,
  ) async {
    const secret = 'motcledecherchetrestrescaracteristique';
    await vaultWithNote('fts', secret);

    final hits = await notesDao.search(secret, limit: 10);
    expect(
      hits,
      isEmpty,
      reason:
          'les triggers FTS masquent titre, contenu et tags des notes '
          'verrouillées — une recherche plein-texte ne doit rien remonter.',
    );
  });
}
