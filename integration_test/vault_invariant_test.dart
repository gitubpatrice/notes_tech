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
    // Même câblage qu'en production (`main.dart`) : sans lui, le repository
    // n'a aucun moyen de chiffrer et refuse toute écriture portant du clair
    // dans un coffre. Le test monterait alors un graphe que l'application
    // n'utilise pas.
    notes.useVaultSealer(isUnlocked: vault.isUnlocked, seal: vault.encryptNote);
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

  testWidgets('3. une écriture en clair dans un coffre ouvert est SCELLÉE', (
    _,
  ) async {
    // Le contrat a changé, et en mieux. La garde REFUSAIT l'écriture ; elle
    // ne pouvait donc pas refuser une note SANS CORPS — l'auto-save d'un
    // titre seul, pendant la création, aurait levé sur un geste légitime. Le
    // titre partait alors en clair dans la colonne `title`.
    //
    // Le repository ne décide plus si une écriture en clair est légitime : il
    // scelle avant de persister. Une note de coffre ne peut plus atteindre le
    // disque en clair, corps vide ou non.
    final (v, note) = await vaultWithNote('garde', 'secret');

    final scellee = await notes.save(
      note.copyWith(
        title: 'Code PIN carte bleue',
        content: 'écrit en clair',
        clearEncrypted: true,
      ),
    );

    expect(
      scellee.encryptedContent,
      isNotNull,
      reason: 'le repository a persisté sans sceller',
    );

    final row = await rawRow(note.id);
    expect(row['encrypted_content'], isNotNull);
    expect(
      row['content'],
      '',
      reason: 'le contenu a atteint le disque en clair',
    );
    expect(
      row['title'],
      '',
      reason:
          "LE TITRE EST EN CLAIR DANS LA BASE. C'est exactement la fuite que "
          'le scellement ferme : « Code PIN carte bleue » lisible par qui '
          'ouvre le fichier, coffre fermé.',
    );
    expect(v.isVault, isTrue);
  });

  testWidgets("3 bis. coffre VERROUILLÉ : l'écriture est refusée", (_) async {
    // L'autre face du scellement. Coffre fermé, aucune clé n'existe en RAM :
    // rien ne peut être chiffré. Persister en clair serait précisément la
    // fuite qu'on empêche — donc on lève. Cas réel : un auto-lock qui tombe
    // pendant l'édition, suivi d'un auto-save.
    final (_, note) = await vaultWithNote('garde-verrou', 'secret');
    vault.lockAll();

    await expectLater(
      notes.save(note.copyWith(title: 'Sensible', clearEncrypted: true)),
      throwsA(isA<VaultPlaintextWriteException>()),
    );

    final row = await rawRow(note.id);
    expect(row['content'], '');
    expect(row['title'], '');
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

  testWidgets('8. v2 : le titre n\'est plus en clair dans la base', (_) async {
    final (_, note) = await vaultWithNote('titre-v2', 'contenu');

    final row = await rawRow(note.id);
    expect(
      row['title'],
      '',
      reason:
          'À partir du format v2 le titre vit DANS le blob. La colonne '
          '`title` le laissait sinon lisible par qui obtient la clé de la '
          'base — pas cherchable ni affiché, mais présent.',
    );
    expect(row['enc_v'], Note.kEncVersionTitleAndContent);
    final blob = row['encrypted_content']! as Uint8List;
    expect(String.fromCharCodes(blob), isNot(contains('titre-titre-v2')));
  });

  testWidgets('9. round-trip v2 : le titre revient intact', (_) async {
    const titre = 'Relevé bancaire — août, éàü 漢字';
    const corps = 'contenu associé';
    final plain = await folders.create(name: 'coffre-rt2');
    final v = await vault.createVault(folder: plain, passphrase: _kPass);
    final created = await notes.create(folderId: v.id, title: 'provisoire');
    final sealed = await vault.encryptNote(
      created.copyWith(title: titre, content: corps),
    );
    await notes.save(sealed);

    final relue = await notes.get(created.id);
    expect(relue!.title, '', reason: 'rien en clair au repos');
    final clear = await vault.decryptNote(relue);
    expect(clear.title, titre);
    expect(clear.content, corps);
  });

  testWidgets(
    '10. MIGRATION v1 → v2 : une note héritée ne perd pas son titre',
    (_) async {
      const titre = 'Ancien titre en clair';
      const corps = 'ancien contenu chiffré';
      final plain = await folders.create(name: 'coffre-migration');
      final v = await vault.createVault(folder: plain, passphrase: _kPass);
      // Créée VIDE : depuis le scellement, `create` avec un titre produirait
      // directement une note v2. Le titre est posé sur la copie v1 ci-dessous.
      final created = await notes.create(folderId: v.id);

      // Note héritée AUTHENTIQUE : chiffrée avec la vraie clé du coffre, au
      // format v1 — contenu dans le blob, titre en clair dans la colonne.
      final legacy = await vault.encryptNoteLegacyV1(
        created.copyWith(title: titre, content: corps),
      );
      await notes.save(legacy);

      final avant = await rawRow(created.id);
      expect(avant['title'], titre, reason: 'l\'état hérité est bien en place');
      expect(avant['enc_v'], Note.kEncVersionContentOnly);
      final updatedAtAvant = avant['updated_at'];

      // Le déverrouillage doit migrer.
      vault.lock(v.id);
      await vault.unlock(
        folder: (await folders.get(v.id))!,
        passphrase: _kPass,
      );

      final apres = await rawRow(created.id);
      expect(apres['enc_v'], Note.kEncVersionTitleAndContent);
      expect(apres['title'], '', reason: 'le titre a rejoint le blob');
      expect(
        apres['updated_at'],
        updatedAtAvant,
        reason:
            'une migration d\'arrière-plan ne doit pas réordonner la '
            'liste de l\'utilisateur',
      );

      // ET RIEN N'EST PERDU — c'est tout l'enjeu de ce test.
      final relue = await notes.get(created.id);
      final clear = await vault.decryptNote(relue!);
      expect(clear.title, titre);
      expect(clear.content, corps);
    },
  );

  testWidgets('11. une note v1 reste lisible tant qu\'elle n\'a pas migré', (
    _,
  ) async {
    const titre = 'Titre v1';
    const corps = 'contenu v1';
    final plain = await folders.create(name: 'coffre-v1-lisible');
    final v = await vault.createVault(folder: plain, passphrase: _kPass);
    // Créée VIDE : voir test 10, `create` avec un titre scelle en v2.
    final created = await notes.create(folderId: v.id);
    final legacy = await vault.encryptNoteLegacyV1(
      created.copyWith(title: titre, content: corps),
    );
    await notes.save(legacy);

    // Sans passer par un déverrouillage : on relit directement en v1.
    final relue = await notes.get(created.id);
    expect(relue!.encVersion, Note.kEncVersionContentOnly);
    final clear = await vault.decryptNote(relue);
    expect(clear.content, corps);
    expect(
      clear.title,
      titre,
      reason:
          'en v1 le titre vient de la colonne, pas du blob — la lecture '
          'ne doit pas dépendre de la migration.',
    );
  });

  testWidgets('12. retirer la protection conserve le dossier ET les notes', (
    _,
  ) async {
    const secret = 'contenu à conserver après retrait';
    final (v, note) = await vaultWithNote('retrait', secret);

    final res = await vault.removeVaultProtection(v);
    expect(res.failed, 0);
    expect(res.decrypted, 1);

    // Le dossier existe toujours, avec son nom, et n'est plus un coffre.
    final apresFolder = await folders.get(v.id);
    expect(apresFolder, isNotNull, reason: 'le dossier doit être CONSERVÉ');
    expect(apresFolder!.name, v.name);
    expect(apresFolder.isVault, isFalse);

    // La note est lisible en clair, contenu intact.
    final row = await rawRow(note.id);
    expect(row['encrypted_content'], isNull);
    expect(row['content'], secret);
    expect(await folders.isVaultFolder(v.id), isFalse);

    // Et la session est refermée : la clé ne traîne plus en RAM.
    expect(vault.isUnlocked(v.id), isFalse);
  });

  testWidgets('13. retirer la protection exige une session ouverte', (_) async {
    final (v, note) = await vaultWithNote('retrait-verrou', 'secret');
    vault.lock(v.id);

    await expectLater(
      vault.removeVaultProtection(v),
      throwsA(isA<VaultLockedException>()),
      reason:
          'la passphrase est la seule preuve que le demandeur a le '
          'droit de rendre ces notes lisibles.',
    );

    // ET RIEN N'A BOUGÉ : le coffre est intact, la note toujours chiffrée.
    final apresFolder = await folders.get(v.id);
    expect(apresFolder!.isVault, isTrue);
    final row = await rawRow(note.id);
    expect(row['encrypted_content'], isNotNull);
    expect(row['content'], '');
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
