import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/utils/vector_math.dart';

void main() {
  group('VectorMath', () {
    test('dot product', () {
      final a = Float32List.fromList([1, 2, 3]);
      final b = Float32List.fromList([4, 5, 6]);
      expect(VectorMath.dot(a, b), closeTo(32.0, 1e-6));
    });

    test('l2Norm', () {
      final v = Float32List.fromList([3, 4]);
      expect(VectorMath.l2Norm(v), closeTo(5.0, 1e-6));
    });

    test('normalizeInPlace produit un vecteur unitaire', () {
      final v = Float32List.fromList([3, 4]);
      VectorMath.normalizeInPlace(v);
      expect(VectorMath.l2Norm(v), closeTo(1.0, 1e-6));
    });

    test('normalizeInPlace tolère le vecteur nul', () {
      final v = Float32List.fromList([0, 0, 0]);
      VectorMath.normalizeInPlace(v);
      expect(v, [0, 0, 0]);
    });

    test('cosine sur vecteurs non-normalisés', () {
      final a = Float32List.fromList([1, 0, 0]);
      final b = Float32List.fromList([1, 0, 0]);
      expect(VectorMath.cosine(a, b), closeTo(1.0, 1e-6));
      final c = Float32List.fromList([0, 1, 0]);
      expect(VectorMath.cosine(a, c), closeTo(0.0, 1e-6));
    });

    test('encodeBlob/decodeBlob round-trip', () {
      final v = Float32List.fromList([0.1, -0.2, 3.14, 42.0]);
      final blob = VectorMath.encodeBlob(v);
      final back = VectorMath.decodeBlob(Uint8List.fromList(blob));
      expect(back.length, v.length);
      for (var i = 0; i < v.length; i++) {
        expect(back[i], closeTo(v[i], 1e-6));
      }
    });

    test('decodeBlob valide expectedDim', () {
      final v = Float32List.fromList([1, 2, 3, 4]);
      final blob = Uint8List.fromList(VectorMath.encodeBlob(v));
      expect(
        () => VectorMath.decodeBlob(blob, expectedDim: 5),
        throwsArgumentError,
      );
    });

    test('cosine NaN-safe', () {
      final a = Float32List.fromList([double.nan, 0, 0]);
      final b = Float32List.fromList([1, 0, 0]);
      expect(VectorMath.cosineNormalized(a, b), 0);
      expect(VectorMath.cosine(a, b), 0);
    });

    test('normalizeInPlace remet à zéro un vecteur NaN', () {
      final v = Float32List.fromList([double.nan, 1, 1]);
      VectorMath.normalizeInPlace(v);
      expect(v.every((x) => x == 0), isTrue);
    });
  });
}
