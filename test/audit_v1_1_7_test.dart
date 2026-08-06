// Tests garde pour l'audit Notes Tech du 2026-08-06.
//
// Invariant verrouillé ici : **le contenu d'une note de coffre ne doit pas
// rester atteignable par la recherche sémantique.**
//
// Le défaut d'origine était un jumeau asymétrique. La mise au coffre EN
// MASSE (`FolderVaultService.encryptAllNotesInFolder`) purgeait bien
// l'embedding calculé sur le texte en clair. Le déplacement d'UNE note vers
// un coffre, depuis l'éditeur, ne le faisait pas — et aucune passe
// d'indexation ne rattrapait l'oubli (`deleteOrphans` ne vise que les notes
// supprimées, et la boucle d'indexation fait `continue` sur les notes
// verrouillées sans jamais toucher à leur ligne). Le vecteur en clair
// survivait donc indéfiniment, et `SemanticSearchService.search` ne filtrait
// que `isTrashed` : la note verrouillée remontait, classée par la similarité
// de son contenu en clair, coffre fermé.
//
// Deux gardes indépendantes, testées séparément :
//   1. la SOURCE  — purge de l'embedding sur tous les chemins de mise au
//      coffre (garde à écrire, donc vérifiée au niveau source) ;
//   2. l'USAGE    — `isEligibleHit` refuse inconditionnellement une note
//      verrouillée (garde comportementale, testée directement).
//
// La garde 2 seule suffirait à fermer la fuite visible, mais laisserait un
// vecteur dérivé du clair en base ; la garde 1 seule dépendrait du fait que
// tout futur chemin de mise au coffre pense à appeler la purge. Les deux
// sont donc nécessaires, et les deux sont verrouillées ici.

import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/data/models/folder.dart';
import 'package:notes_tech/data/models/note.dart';
import 'package:notes_tech/services/export/note_export_service.dart';
import 'package:notes_tech/services/semantic_search_service.dart';

Note _note({Uint8List? encrypted, DateTime? trashedAt}) {
  final now = DateTime(2026, 8, 6);
  return Note(
    id: 'n1',
    title: 'Banque',
    content: encrypted == null ? 'IBAN FR76 1234' : '',
    folderId: 'f1',
    createdAt: now,
    updatedAt: now,
    trashedAt: trashedAt,
    encryptedContent: encrypted,
  );
}

void main() {
  group('C1 — usage : la recherche sémantique refuse les notes de coffre', () {
    test('une note verrouillée est rejetée', () {
      final locked = _note(encrypted: Uint8List.fromList(List.filled(40, 7)));
      expect(locked.isLocked, isTrue);
      expect(
        SemanticSearchService.isEligibleHit(locked),
        isFalse,
        reason:
            'Un embedding résiduel suffirait sinon à faire remonter la note, '
            'classée par la similarité de son contenu en clair : le seul '
            'classement est un oracle sur le contenu du coffre, sans qu\'aucun '
            'texte ne soit affiché et sans passphrase saisie.',
      );
    });

    test('une note en corbeille est rejetée', () {
      final trashed = _note(trashedAt: DateTime(2026, 8, 1));
      expect(SemanticSearchService.isEligibleHit(trashed), isFalse);
    });

    test('une note ordinaire est acceptée', () {
      expect(SemanticSearchService.isEligibleHit(_note()), isTrue);
    });

    test('verrouillée ET en corbeille reste rejetée', () {
      final both = _note(
        encrypted: Uint8List.fromList(List.filled(40, 7)),
        trashedAt: DateTime(2026, 8, 1),
      );
      expect(SemanticSearchService.isEligibleHit(both), isFalse);
    });
  });

  group('C1 — source : purge de l\'embedding sur TOUS les chemins', () {
    // Garde au niveau source, comme la drift detection de `appVersion` :
    // l'appel se fait dans un `State` de widget et dans un service qui exige
    // Keystore + SQLCipher, deux dépendances qu'un test pure-Dart ne peut pas
    // instancier. Ce qu'on verrouille ici, c'est qu'un refactor ne puisse pas
    // supprimer silencieusement l'un des deux appels.
    String read(String path) => File(path).readAsStringSync();

    test('FolderVaultService expose purgePlaintextEmbedding', () {
      final src = read('lib/services/security/folder_vault_service.dart');
      expect(
        src.contains('Future<bool> purgePlaintextEmbedding('),
        isTrue,
        reason: 'Le geste doit rester centralisé et appelable par les '
            'chemins de mise au coffre.',
      );
    });

    test('la mise au coffre EN MASSE purge', () {
      final src = read('lib/services/security/folder_vault_service.dart');
      final start = src.indexOf('encryptAllNotesInFolder(');
      expect(start, greaterThan(0));
      final body = src.substring(start, start + 1400);
      expect(
        body.contains('purgePlaintextEmbedding('),
        isTrue,
        reason: 'Régression du fix F1 v1.0.3.',
      );
    });

    test('le déplacement d\'UNE note vers un coffre purge aussi', () {
      final src = read('lib/ui/screens/note_editor_screen.dart');
      expect(
        src.contains('_vault.purgePlaintextEmbedding('),
        isTrue,
        reason: 'C\'est le chemin qui manquait : sans cet appel, la note '
            'déplacée vers un coffre garde son vecteur en clair en base.',
      );
    });

    test('la passe d\'indexation répare les vecteurs orphelins', () {
      final src = read('lib/services/indexing_service.dart');
      expect(
        src.contains('_embeddings.remove('),
        isTrue,
        reason: 'La passe doit purger le vecteur des notes verrouillées '
            'qu\'elle rencontre, sinon un échec transitoire de la purge '
            'synchrone laisse le vecteur en base pour toujours.',
      );
    });
  });

  group('C2 — l\'avertissement de sortie de coffre doit être atteignable', () {
    test('isVaultExit s\'appuie sur _wasLocked, pas sur encryptedContent', () {
      final src = File(
        'lib/ui/screens/note_editor_screen.dart',
      ).readAsStringSync();
      expect(
        src.contains('isVaultExit = _wasLocked && !targetFolder.isVault'),
        isTrue,
        reason: '`_note` est l\'éphémère DÉCHIFFRÉE dès `_load` '
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
        reason: 'Chaque `_repo.create` hors de `_createSibling` est un '
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
        reason: 'Le build de release est obfusqué : un nom d\'enum n\'est pas '
            'un contrat de sérialisation stable. Une préférence écrite par '
            'une version et relue par une autre retomberait sur le défaut.',
      );
      for (final mode in NoteSortMode.values) {
        expect(
          code.contains("NoteSortMode.${mode.name} => '${mode.name}'"),
          isTrue,
          reason: 'Clé manquante pour ${mode.name}. Le `switch` exhaustif '
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
      final entries = zipEntries([
        note('a', 'Note', 'f1'),
        note('b', 'Note-2', 'f1'),
        note('c', 'Note', 'f1'),
      ], {'f1': folder});
      expect(
        entries.toSet().length,
        3,
        reason: 'Le suffixe de désambiguïsation pouvait retomber sur un nom '
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
        reason: '`safeFileName` filtrait déjà les noms réservés Windows, '
            'son jumeau `_safeFolderDirName` ne le faisait pas.',
      );
    });
  });
}
