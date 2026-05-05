/// Hashing déterministe partagé (FNV-1a 32 bits).
///
/// Utilisé par :
/// - LocalEmbedder (hashing trick → bucket vectoriel)
/// - IndexingService (sourceHash pour idempotence)
library;

class HashUtils {
  HashUtils._();

  static const int _fnvOffset = 0x811c9dc5;
  static const int _fnvPrime = 0x01000193;

  /// FNV-1a 32 bits sur les code units de la chaîne.
  /// Retourne un entier positif sur 32 bits.
  static int fnv1a32(String s) {
    var h = _fnvOffset;
    for (var i = 0; i < s.length; i++) {
      h ^= s.codeUnitAt(i) & 0xFF;
      h = (h * _fnvPrime) & 0xFFFFFFFF;
    }
    return h;
  }

  /// Combine deux chaînes avec un séparateur sentinelle pour éviter les
  /// collisions de type `("ab", "cd")` vs `("abcd", "")`.
  static int fnv1a32Pair(String a, String b) {
    var h = _fnvOffset;
    void mix(String s) {
      for (var i = 0; i < s.length; i++) {
        h ^= s.codeUnitAt(i) & 0xFF;
        h = (h * _fnvPrime) & 0xFFFFFFFF;
      }
    }

    mix(a);
    h ^= 0x01;
    h = (h * _fnvPrime) & 0xFFFFFFFF;
    mix(b);
    return h;
  }
}
