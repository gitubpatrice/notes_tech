// Tests garde pour l'audit Notes Tech du 2026-08-06.
//
// Ces gardes verrouillent des invariants du COFFRE que la suite d'origine ne
// voyait pas. Deux d'entre elles portaient sur la recherche sémantique et ont
// disparu avec elle lors du retrait de l'IA embarquée : ce qu'elles
// protégeaient (un vecteur dérivé du clair survivant à la mise au coffre)
// n'existe plus, puisqu'il n'y a plus de vecteurs du tout.
//
// Ce qui reste porte sur des chemins toujours vivants : réparation des notes
// laissées en clair, atteignabilité de l'avertissement de sortie de coffre,
// chiffrement à la création, stabilité des clés de tri, collisions à l'export.

import 'dart:io';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/data/models/folder.dart';
import 'package:notes_tech/data/models/note.dart';
import 'package:notes_tech/services/export/note_export_service.dart';

void main() {
  group(
    'C6 — réparation des notes laissées en clair par le bug d\'épinglage',
    () {
      final src = File(
        'lib/services/security/folder_vault_service.dart',
      ).readAsStringSync();

      test('les DEUX chemins de déverrouillage passent par le même point', () {
        final appels = RegExp(
          r'await _onSessionOpened\(',
        ).allMatches(src).length;
        expect(
          appels,
          2,
          reason:
              'Un seul appel = un jumeau divergent de plus. Le coffre PIN et '
              'le coffre passphrase doivent faire les mêmes travaux à '
              'l\'ouverture, sinon les notes de l\'un des deux types restent '
              'exposées.',
        );
      });

      test('le point d\'entrée fait réparation ET migration de format', () {
        final debut = src.indexOf('_onSessionOpened(String folderId)');
        expect(debut, greaterThan(0));
        final corps = src.substring(debut, debut + 400);
        expect(corps.contains('_reprotectPlaintextNotes('), isTrue);
        expect(
          corps.contains('_migrateLegacyEncryptedNotes('),
          isTrue,
          reason:
              'Brancher un nouveau traitement sur un seul des deux '
              'chemins de déverrouillage est exactement la façon dont un '
              'jumeau divergent naît. Tout passe par ce point unique.',
        );
      });

      test('la réparation cible l\'exposition, corbeille comprise', () {
        expect(
          src.contains('listPlaintextInFolder('),
          isTrue,
          reason:
              'Le critère doit être `content` non vide — l\'exposition '
              'elle-même — et non `!isLocked` : une ligne portant à la fois '
              'un blob et du clair serait tout aussi lisible et passerait '
              'entre les mailles. La requête est en outre sans filtre sur '
              '`trashed_at` : une note laissée en clair puis jetée séjourne '
              '30 jours dans la corbeille.',
        );
      });

      test('la réparation ne réordonne pas la liste de l\'utilisateur', () {
        expect(
          src.contains('replaceContentPayload('),
          isTrue,
          reason:
              'Passer par `save()` remettrait `updatedAt` à maintenant : les '
              'notes reprotégées remonteraient en tête de « modifiées '
              'récemment » à chaque ouverture du coffre. Une réparation '
              'silencieuse qui réordonne l\'écran n\'est pas silencieuse.',
        );
      });
    },
  );

  group('C2 — l\'avertissement de sortie de coffre doit être atteignable', () {
    test('isVaultExit s\'appuie sur _wasLocked, pas sur encryptedContent', () {
      final src = File(
        'lib/ui/screens/note_editor_screen.dart',
      ).readAsStringSync();
      expect(
        src.contains('isVaultExit = _wasLocked && !targetFolder.isVault'),
        isTrue,
        reason:
            '`_note` est l\'éphémère DÉCHIFFRÉE dès `_load` '
            '(`clearEncrypted: true`) : tester `encryptedContent != null` '
            'rendait la condition toujours fausse et le dialog destructif '
            'inatteignable.',
      );
    });
  });

  group('C3 — une note créée dans un coffre naît chiffrée', () {
    test('les deux créations de l\'éditeur passent par _createSibling', () {
      final src = File(
        'lib/ui/screens/note_editor_screen.dart',
      ).readAsStringSync();
      // Une seule création brute doit subsister : celle DANS `_createSibling`.
      final rawCreates = RegExp(r'_repo\.create\(').allMatches(src).length;
      expect(
        rawCreates,
        1,
        reason:
            'Chaque `_repo.create` hors de `_createSibling` est un '
            'chemin qui crée une note NON chiffrée, y compris dans un '
            'dossier coffre : tout ce que l\'utilisateur y tape ensuite '
            'est auto-sauvegardé en clair.',
      );
      expect(src.contains('_vault.encryptNote(created)'), isTrue);
    });
  });

  group('C5 — clés de tri persistées indépendantes de l\'obfuscation', () {
    test('les 6 modes ont une clé littérale et stable', () {
      final src = File('lib/services/settings_service.dart').readAsStringSync();
      // Les commentaires sont retirés avant la recherche : ils citent
      // justement le motif proscrit pour expliquer pourquoi il l'est.
      final code = src
          .split('\n')
          .where((l) => !l.trimLeft().startsWith('//'))
          .join('\n');
      expect(
        code.contains('mode.name'),
        isFalse,
        reason:
            'Le build de release est obfusqué : un nom d\'enum n\'est pas '
            'un contrat de sérialisation stable. Une préférence écrite par '
            'une version et relue par une autre retomberait sur le défaut.',
      );
      for (final mode in NoteSortMode.values) {
        expect(
          code.contains("NoteSortMode.${mode.name} => '${mode.name}'"),
          isTrue,
          reason:
              'Clé manquante pour ${mode.name}. Le `switch` exhaustif '
              'doit couvrir tout l\'enum — un oubli casse à la compilation, '
              'pas au premier changement de tri sur le téléphone.',
        );
      }
    });
  });

  group('C4 — export ZIP : collisions de noms', () {
    final now = DateTime(2026, 8, 6);

    Note note(String id, String title, String folderId) => Note(
      id: id,
      title: title,
      content: 'corps',
      folderId: folderId,
      createdAt: now,
      updatedAt: now,
    );

    List<String> zipEntries(List<Note> notes, Map<String, Folder> folders) {
      final bytes = const NoteExportService().exportAsZip(
        notes: notes,
        foldersById: folders,
      );
      return ZipDecoder()
          .decodeBytes(bytes)
          .files
          .map((f) => f.name)
          .where((n) => n != 'README.md')
          .toList();
    }

    test('« Note », « Note-2 », « Note » produisent 3 entrées distinctes', () {
      final folder = Folder(
        id: 'f1',
        name: 'Dossier',
        createdAt: now,
        updatedAt: now,
      );
      final entries = zipEntries(
        [
          note('a', 'Note', 'f1'),
          note('b', 'Note-2', 'f1'),
          note('c', 'Note', 'f1'),
        ],
        {'f1': folder},
      );
      expect(
        entries.toSet().length,
        3,
        reason:
            'Le suffixe de désambiguïsation pouvait retomber sur un nom '
            'déjà pris : le ZIP contenait deux `Note-2.md` et le second '
            'écrasait le premier au dézippage, silencieusement.',
      );
    });

    test('un dossier nommé « CON » ne casse pas l\'extraction Windows', () {
      final folder = Folder(
        id: 'f1',
        name: 'CON',
        createdAt: now,
        updatedAt: now,
      );
      final entries = zipEntries([note('a', 'Note', 'f1')], {'f1': folder});
      expect(entries.single.startsWith('CON/'), isFalse);
      expect(
        entries.single.startsWith('sans-dossier/'),
        isTrue,
        reason:
            '`safeFileName` filtrait déjà les noms réservés Windows, '
            'son jumeau `_safeFolderDirName` ne le faisait pas.',
      );
    });
  });
}
