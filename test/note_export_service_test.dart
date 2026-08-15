/// Assainissement des noms de fichiers de l'export.
///
/// Ce fichier naît d'un défaut trouvé le 2026-08-15 pendant le portage Kotlin :
/// `safeFileName` et `_safeFolderDirName` testaient l'égalité stricte avec la
/// liste des noms réservés de Windows, si bien qu'une note titrée `CON.txt`
/// passait et produisait `CON.txt.md`. Sous Windows, un nom de périphérique
/// reste réservé quelle que soit son extension : l'archive devenait
/// inextractible **en entier** chez le destinataire, pas seulement ce
/// fichier-là — c'est-à-dire au moment précis où l'export sert.
///
/// ⚠️ Le commentaire de `_kWindowsReserved` se félicitait d'avoir corrigé un
/// jumeau asymétrique : un site appliquait la liste, l'autre l'ignorait. Les
/// deux la partageaient bien depuis — avec le **même prédicat incomplet**.
/// Partager la donnée ne suffisait pas ; c'est la décision qu'il fallait
/// partager, et c'est maintenant `_isWindowsReserved`.
///
/// ⚠️ Seul `safeFileName` est couvert ici : `_safeFolderDirName` est privé et
/// n'est atteignable que par un export complet, qui demande une base. Les deux
/// appellent désormais le même prédicat — c'est ce partage, et non ce test, qui
/// garantit le dossier. Le portage Kotlin, lui, teste les deux.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/services/export/note_export_service.dart';

void main() {
  const service = NoteExportService();
  const fallback = '11111111-1111-1111-1111-111111111111';

  String nom(String titre) =>
      service.safeFileName(titre, fallbackId: fallback);

  group('noms réservés Windows', () {
    test('un nom réservé nu retombe sur l\'identifiant, quelle que soit la casse', () {
      expect(nom('CON'), 'note-11111111.md');
      expect(nom('con'), 'note-11111111.md');
      expect(nom('LPT9'), 'note-11111111.md');
    });

    test('il le reste avec une extension, un point final ou une espace finale', () {
      expect(nom('CON.txt'), 'note-11111111.md');
      expect(nom('nul.md'), 'note-11111111.md');
      expect(nom('COM1.tar.gz'), 'note-11111111.md');
      expect(nom('PRN.'), 'note-11111111.md');
      expect(nom('AUX '), 'note-11111111.md');
    });

    test('COM0, LPT0 et les variantes en exposants sont réservés eux aussi', () {
      // La liste qui circule commence à COM1 ; celle de Microsoft commence à
      // COM0 et comporte les exposants Unicode.
      expect(nom('COM0'), 'note-11111111.md');
      expect(nom('LPT0'), 'note-11111111.md');
      expect(nom('COM¹'), 'note-11111111.md');
      expect(nom('lpt³.txt'), 'note-11111111.md');
      // COM10 n'existe pas : la liste s'arrête à un seul chiffre.
      expect(nom('COM10'), 'COM10.md');
    });

    test('CON.TRAT.pdf EST réservé — Windows coupe au premier point', () {
      // ⚠️ Signalé comme faux positif par une relecture externe le 2026-08-15.
      // C'est la relecture qui se trompait : Windows résout un nom de
      // périphérique en coupant au PREMIER point, donc ce nom désigne CON.
      expect(nom('CON.TRAT.pdf'), 'note-11111111.md');
    });

    test('le contrôle porte sur le nom de base, pas sur un préfixe', () {
      // Une note dont le titre COMMENCE par un nom réservé est parfaitement
      // valable : la renommer serait une régression du correctif.
      expect(nom('Console'), 'Console.md');
      expect(nom('CONTRAT.pdf'), 'CONTRAT.pdf.md');
      expect(nom('Communication'), 'Communication.md');
    });
  });

  group('les garde-fous déjà en place ne bougent pas', () {
    test('« .. » ne devient jamais un nom de fichier', () {
      expect(nom('..'), 'note-11111111.md');
    });

    test('un titre vide retombe sur l\'identifiant', () {
      expect(nom('   '), 'note-11111111.md');
    });

    test('une note venue d\'un coffre ouvert porte la mention dans son nom', () {
      expect(
        service.safeFileName(
          'Secret',
          fallbackId: fallback,
          unlockedVaultSuffix: true,
        ),
        'Secret [unlocked].md',
      );
    });
  });
}
