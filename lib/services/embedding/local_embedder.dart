/// Encodeur local "hashing trick" — déterministe, zéro fichier externe.
///
/// Stratégie :
/// - Normalisation : lowercase + dépouillement diacritiques.
/// - Tokens : mots (séparés par non-alphanumériques unicode).
/// - Features supplémentaires : bigrammes et trigrammes de caractères
///   (capture la morphologie FR : conjugaisons, pluriels, racines).
/// - Hashing trick : chaque feature est hachée (FNV-1a 32 bits) modulo `dim`,
///   sa valeur dans le vecteur est incrémentée pondérément.
/// - Pondération : sub-linear `1 + log(count)` pour atténuer les fréquents.
/// - Boost titre : bonus ×3 pour les tokens du titre.
/// - L2-normalisation finale → cosine = dot product.
///
/// Garanties : pure fonction, pas d'allocation cachée hors Float32List,
/// déterministe (testable), thread-safe (pas d'état).
library;

import 'dart:math' as math;
import 'dart:typed_data';

import '../../utils/hash_utils.dart';
import '../../utils/text_utils.dart';
import '../../utils/vector_math.dart';
import 'embedding_provider.dart';

class LocalEmbedder implements EmbeddingProvider {
  const LocalEmbedder();

  static const String _modelId = 'local-hash-v1';
  static const int _dim = 256;

  @override
  String get modelId => _modelId;

  @override
  int get dim => _dim;

  // ---------------------------------------------------------------------
  // API publique
  // ---------------------------------------------------------------------

  @override
  Future<void> warmUp() async {}

  @override
  Future<void> dispose() async {}

  @override
  Float32List embed(String text) => embedTitleAndBody(title: '', body: text);

  /// Encode un (titre, corps). Le titre reçoit un poids ×3.
  Float32List embedTitleAndBody({
    required String title,
    required String body,
  }) {
    final v = Float32List(dim);
    if (title.isNotEmpty) _accumulate(v, title, weight: 3.0);
    if (body.isNotEmpty) _accumulate(v, body, weight: 1.0);
    // Compression sub-linéaire pour atténuer les pics de tokens fréquents.
    for (var i = 0; i < v.length; i++) {
      final x = v[i];
      if (x > 0) v[i] = math.log(1 + x);
    }
    return VectorMath.normalizeInPlace(v);
  }

  // ---------------------------------------------------------------------
  // Implémentation
  // ---------------------------------------------------------------------

  void _accumulate(Float32List v, String text, {required double weight}) {
    final cleaned = _normalize(text);
    if (cleaned.isEmpty) return;
    final tokens = _tokenize(cleaned);
    for (final tok in tokens) {
      _hashAdd(v, 'w:$tok', weight);
      _addCharNGrams(v, tok, weight: weight * 0.5);
    }
  }

  static String _normalize(String s) {
    final lower = s.toLowerCase();
    final buf = StringBuffer();
    for (var i = 0; i < lower.length; i++) {
      buf.writeCharCode(TextUtils.stripLatinDiacritic(lower.codeUnitAt(i)));
    }
    return buf.toString();
  }

  static final RegExp _splitNonWord = RegExp(r'[^a-z0-9]+');

  static List<String> _tokenize(String normalized) {
    return normalized
        .split(_splitNonWord)
        .where((t) => t.length >= 2 && t.length <= 32)
        .toList(growable: false);
  }

  /// Bigrammes + trigrammes de caractères, sans padding (suffit en pratique).
  static const int _minNGram = 3;
  static const int _maxNGram = 4;

  void _addCharNGrams(Float32List v, String token, {required double weight}) {
    final len = token.length;
    if (len < _minNGram) return;
    for (var n = _minNGram; n <= _maxNGram; n++) {
      if (len < n) break;
      for (var i = 0; i <= len - n; i++) {
        _hashAdd(v, 'g$n:${token.substring(i, i + n)}', weight);
      }
    }
  }

  void _hashAdd(Float32List v, String key, double weight) {
    final idx = HashUtils.fnv1a32(key) % v.length;
    v[idx] += weight;
  }
}
