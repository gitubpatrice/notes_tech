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

import '../../core/constants.dart';
import '../../utils/vector_math.dart';
import 'embedding_provider.dart';

class LocalEmbedder implements EmbeddingProvider {
  const LocalEmbedder();

  @override
  String get modelId => AppConstants.embeddingModelId;

  @override
  int get dim => AppConstants.embeddingDim;

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
      final cu = lower.codeUnitAt(i);
      buf.writeCharCode(_stripDiacritic(cu));
    }
    return buf.toString();
  }

  /// Mapping minimal des diacritiques latins courants → ASCII.
  /// Plus rapide qu'une regex Unicode normalize().
  static int _stripDiacritic(int cu) {
    // Voyelles accentuées + ç (cas couvrant 99% du FR).
    switch (cu) {
      case 0x00E0: // à
      case 0x00E1: // á
      case 0x00E2: // â
      case 0x00E3: // ã
      case 0x00E4: // ä
      case 0x00E5: // å
        return 0x61; // a
      case 0x00E7: // ç
        return 0x63; // c
      case 0x00E8: // è
      case 0x00E9: // é
      case 0x00EA: // ê
      case 0x00EB: // ë
        return 0x65; // e
      case 0x00EC: // ì
      case 0x00ED: // í
      case 0x00EE: // î
      case 0x00EF: // ï
        return 0x69; // i
      case 0x00F1: // ñ
        return 0x6E; // n
      case 0x00F2: // ò
      case 0x00F3: // ó
      case 0x00F4: // ô
      case 0x00F5: // õ
      case 0x00F6: // ö
        return 0x6F; // o
      case 0x00F9: // ù
      case 0x00FA: // ú
      case 0x00FB: // û
      case 0x00FC: // ü
        return 0x75; // u
      case 0x00FD: // ý
      case 0x00FF: // ÿ
        return 0x79; // y
      default:
        return cu;
    }
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

  // ---------------------------------------------------------------------
  // FNV-1a 32-bit (déterministe, rapide, indépendant de Object.hashCode).
  // ---------------------------------------------------------------------

  static const int _fnvOffset = 0x811c9dc5;
  static const int _fnvPrime = 0x01000193;

  void _hashAdd(Float32List v, String key, double weight) {
    final idx = _fnv1a(key) % v.length;
    v[idx] += weight;
  }

  static int _fnv1a(String s) {
    var h = _fnvOffset;
    for (var i = 0; i < s.length; i++) {
      h ^= s.codeUnitAt(i) & 0xFF;
      h = (h * _fnvPrime) & 0xFFFFFFFF;
    }
    return h;
  }
}
