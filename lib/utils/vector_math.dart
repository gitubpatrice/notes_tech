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
  /// Si la norme est nulle ou non-finie (NaN/Infinity), le vecteur est
  /// remis à zéro pour éviter de propager du poison dans le top-K.
  static Float32List normalizeInPlace(Float32List v) {
    final n = l2Norm(v);
    if (!n.isFinite || n == 0) {
      for (var i = 0; i < v.length; i++) {
        v[i] = 0;
      }
      return v;
    }
    final inv = 1.0 / n;
    for (var i = 0; i < v.length; i++) {
      v[i] *= inv;
    }
    return v;
  }

  /// Cosine entre deux vecteurs L2-normalisés (== dot product).
  /// Retourne 0 si l'un des opérandes est invalide (NaN détecté).
  static double cosineNormalized(Float32List a, Float32List b) {
    final s = dot(a, b);
    return s.isFinite ? s : 0;
  }

  /// Cosine entre deux vecteurs quelconques.
  static double cosine(Float32List a, Float32List b) {
    final na = l2Norm(a);
    final nb = l2Norm(b);
    if (na == 0 || nb == 0 || !na.isFinite || !nb.isFinite) return 0;
    final s = dot(a, b) / (na * nb);
    return s.isFinite ? s : 0;
  }

  /// Encode un Float32List en BLOB compact (little-endian).
  /// IMPORTANT : la copie n'est pas faite ici ; le buffer est partagé avec
  /// `v`. L'appelant ne doit pas muter `v` après l'appel tant que le BLOB
  /// est en transit.
  static Uint8List encodeBlob(Float32List v) =>
      Uint8List.view(v.buffer, v.offsetInBytes, v.lengthInBytes);

  /// Décode un BLOB compact en Float32List.
  ///
  /// Si `expectedDim` est fourni, valide que le BLOB représente exactement
  /// cette dimension. Une copie est systématiquement faite (sqflite peut
  /// retourner des Uint8List dont l'`offsetInBytes` n'est pas aligné 4).
  static Float32List decodeBlob(Uint8List bytes, {int? expectedDim}) {
    if (bytes.lengthInBytes % 4 != 0) {
      throw ArgumentError('Taille BLOB invalide : ${bytes.lengthInBytes}');
    }
    final dim = bytes.lengthInBytes ~/ 4;
    if (expectedDim != null && dim != expectedDim) {
      throw ArgumentError(
        'Dimension BLOB inattendue : $dim vs $expectedDim',
      );
    }
    // Copie alignée garantie. Évite les vues sur buffer non aligné qui
    // crashent sur certains backends + libère le buffer source côté GC.
    final out = Float32List(dim);
    out.buffer.asUint8List().setAll(0, bytes);
    return out;
  }
}
