import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/services/backlinks_service.dart';

void main() {
  group('BacklinksService.normalizeTitle', () {
    test('lowercase + accents dépouillés', () {
      expect(BacklinksService.normalizeTitle('Été à Paris'), 'ete a paris');
      // Note : œ → o (ligature dépouillée à un seul caractère).
      expect(BacklinksService.normalizeTitle('Œuvre'), 'ouvre');
    });

    test('whitespace réduits et trimés', () {
      expect(BacklinksService.normalizeTitle('  hello   world  '), 'hello world');
    });

    test('idempotente', () {
      final once = BacklinksService.normalizeTitle('Café');
      final twice = BacklinksService.normalizeTitle(once);
      expect(once, twice);
    });
  });

  group('BacklinksService.extractFromContent', () {
    test('extraction simple', () {
      final out = BacklinksService.extractFromContent(
        'Voir [[Note A]] et aussi [[Note B]].',
      );
      expect(out.length, 2);
      expect(out[0].title, 'Note A');
      expect(out[1].title, 'Note B');
      expect(out[0].position < out[1].position, isTrue);
    });

    test('doublons éliminés (même titre normalisé)', () {
      final out = BacklinksService.extractFromContent(
        '[[Note A]] [[note a]] [[NOTE A]] [[Note B]]',
      );
      expect(out.length, 2);
      expect(out[0].titleNorm, 'note a');
      expect(out[1].titleNorm, 'note b');
    });

    test('refuse les imbriqués et multilignes', () {
      final out = BacklinksService.extractFromContent(
        'avant [[a\nb]] après [[OK]]',
      );
      expect(out.length, 1);
      expect(out.first.title, 'OK');
    });

    test('titre vide ignoré', () {
      final out = BacklinksService.extractFromContent('[[]] [[   ]] [[X]]');
      expect(out.length, 1);
      expect(out.first.title, 'X');
    });

    test('borné à 256 liens', () {
      final buf = StringBuffer();
      for (var i = 0; i < 500; i++) {
        buf.write('[[N$i]] ');
      }
      final out = BacklinksService.extractFromContent(buf.toString());
      expect(out.length, 256);
    });

    test('titre tronqué à 200 caractères par la regex', () {
      final long = 'x' * 250;
      final out = BacklinksService.extractFromContent('[[$long]]');
      // La regex limite la capture à 200 chars → ne matche pas si > 200.
      expect(out, isEmpty);
    });
  });
}
