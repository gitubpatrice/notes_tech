/// Opérations vectorielles primitives sur Float32List.
///
/// Pas de dépendance externe : pures boucles serrées, aptes à être appelées
/// dans une isolate. Tous les vecteurs sont supposés déjà L2-normalisés
/// pour `cosine` (auquel cas cosine = dot product).
library;

import 'dart:math' as math;
import 'dart:typed_data';

class VectorMath {
  VectorMath._();

  /// Produit scalaire. Les deux vecteurs doivent avoir la même longueur.
  static double dot(Float32List a, Float32List b) {
    assert(a.length == b.length, 'Dimensions différentes');
    final n = a.length;
    var sum = 0.0;
    for (var i = 0; i < n; i++) {
      sum += a[i] * b[i];
    }
    return sum;
  }

  /// Norme L2.
  static double l2Norm(Float32List v) {
    var sum = 0.0;
    for (var i = 0; i < v.length; i++) {
      final x = v[i];
      sum += x * x;
    }
    return math.sqrt(sum);
  }

  /// Normalise `v` en place. Retourne la même instance.
  /// Si la norme est nulle, le vecteur est laissé tel quel.
  static Float32List normalizeInPlace(Float32List v) {
    final n = l2Norm(v);
    if (n == 0) return v;
    final inv = 1.0 / n;
    for (var i = 0; i < v.length; i++) {
      v[i] *= inv;
    }
    return v;
  }

  /// Cosine entre deux vecteurs L2-normalisés (== dot product).
  /// Si les vecteurs ne sont pas normalisés, le résultat est un produit scalaire.
  static double cosineNormalized(Float32List a, Float32List b) => dot(a, b);

  /// Cosine entre deux vecteurs quelconques.
  static double cosine(Float32List a, Float32List b) {
    final na = l2Norm(a);
    final nb = l2Norm(b);
    if (na == 0 || nb == 0) return 0;
    return dot(a, b) / (na * nb);
  }

  /// Encode un Float32List en BLOB compact (little-endian).
  static Uint8List encodeBlob(Float32List v) =>
      Uint8List.view(v.buffer, v.offsetInBytes, v.lengthInBytes);

  /// Décode un BLOB compact en Float32List (zéro-copie quand possible).
  static Float32List decodeBlob(Uint8List bytes) {
    if (bytes.lengthInBytes % 4 != 0) {
      throw ArgumentError('Taille BLOB invalide : ${bytes.lengthInBytes}');
    }
    // Si le BLOB n'est pas aligné sur 4 octets, on copie. Sinon zéro-copie.
    if (bytes.offsetInBytes % 4 == 0) {
      return Float32List.view(
        bytes.buffer,
        bytes.offsetInBytes,
        bytes.lengthInBytes ~/ 4,
      );
    }
    final aligned = Uint8List.fromList(bytes);
    return Float32List.view(aligned.buffer);
  }
}
