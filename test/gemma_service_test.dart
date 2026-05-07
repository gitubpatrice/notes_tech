/// Tests unitaires sur les helpers exposés de `GemmaService`.
///
/// `safeSubstring` est testé pour son cas limite UTF-16 surrogate pair :
/// couper au milieu d'une paire (high+low) produirait un high surrogate
/// orphelin invalide → on doit reculer d'1 unité.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/services/ai/gemma_service.dart';

void main() {
  group('GemmaService.safeSubstring', () {
    test('retourne la chaîne intacte si <= max', () {
      expect(GemmaService.safeSubstring('hello', 10), 'hello');
      expect(GemmaService.safeSubstring('', 10), '');
      expect(GemmaService.safeSubstring('abc', 3), 'abc');
    });

    test('tronque proprement une chaîne ASCII', () {
      expect(GemmaService.safeSubstring('hello world', 5), 'hello');
    });

    test('ne coupe PAS au milieu d\'une surrogate pair UTF-16', () {
      // 😀 (U+1F600) = 2 code units UTF-16 (D83D DE00).
      // 'a' + 😀 = 3 code units. Couper à max=2 voudrait dire garder 'a' + D83D
      // (high surrogate orphelin) → invalide. On doit reculer à max=1.
      const s = 'a\u{1F600}'; // length == 3 en code units
      expect(s.length, 3);
      final out = GemmaService.safeSubstring(s, 2);
      expect(out, 'a');
      expect(out.length, 1);
    });

    test('garde la surrogate pair complète si max tombe pile après', () {
      // Couper à max=3 (juste après le low surrogate) : la paire est
      // entière, on ne touche à rien.
      const s = 'a\u{1F600}b'; // length == 4
      final out = GemmaService.safeSubstring(s, 3);
      expect(out, 'a\u{1F600}');
      expect(out.length, 3);
    });
  });
}
