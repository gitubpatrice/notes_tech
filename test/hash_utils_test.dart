import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/utils/hash_utils.dart';

void main() {
  group('HashUtils.fnv1a32', () {
    test('valeurs canoniques (vecteurs FNV connus)', () {
      // FNV-1a 32 sur "" → offset basis.
      expect(HashUtils.fnv1a32(''), 0x811c9dc5);
      // Vecteurs connus.
      expect(HashUtils.fnv1a32('a'), 0xe40c292c);
      expect(HashUtils.fnv1a32('foobar'), 0xbf9cf968);
    });

    test('déterministe', () {
      expect(HashUtils.fnv1a32('hello'), HashUtils.fnv1a32('hello'));
    });
  });

  group('HashUtils.fnv1a32Pair', () {
    test('séparateur évite la collision (a,bc) vs (ab,c)', () {
      expect(
        HashUtils.fnv1a32Pair('a', 'bc'),
        isNot(HashUtils.fnv1a32Pair('ab', 'c')),
      );
      expect(
        HashUtils.fnv1a32Pair('', 'abc'),
        isNot(HashUtils.fnv1a32Pair('abc', '')),
      );
    });

    test('déterministe', () {
      expect(
        HashUtils.fnv1a32Pair('foo', 'bar'),
        HashUtils.fnv1a32Pair('foo', 'bar'),
      );
    });
  });
}
