/// Tokenizer BERT WordPiece minimal mais correct, alimenté par un
/// `tokenizer.json` au format Hugging Face (sentence-transformers).
///
/// Couvre :
/// - Normalisation BertNormalizer : strip accents (latin) + lowercase + clean.
/// - Pré-tokenisation BertPreTokenizer : split sur espaces + ponctuation.
/// - Modèle WordPiece : greedy longest match avec préfixe `##` pour subwords.
/// - Tokens spéciaux : `[CLS]` (102 généralement → on lit le vocab), `[SEP]`,
///   `[UNK]`, `[PAD]`.
///
/// Hors scope (pas nécessaire pour MiniLM L6 v2 sur FR+EN) :
/// - Handle chinese chars (segmentation CJK char-par-char) — on tombe en `[UNK]`
///   sur idéogrammes, c'est acceptable pour la cible de cette app.
library;

import 'dart:convert';

import 'package:flutter/services.dart' show rootBundle;

class BertEncoded {
  const BertEncoded({
    required this.inputIds,
    required this.attentionMask,
    required this.tokenTypeIds,
  });

  final List<int> inputIds;
  final List<int> attentionMask;
  final List<int> tokenTypeIds;

  int get length => inputIds.length;
}

class BertTokenizer {
  BertTokenizer._({
    required this.vocab,
    required this.unkToken,
    required this.clsToken,
    required this.sepToken,
    required this.padToken,
    required this.continuingPrefix,
    required this.maxInputCharsPerWord,
  });

  final Map<String, int> vocab;
  final int unkToken;
  final int clsToken;
  final int sepToken;
  final int padToken;
  final String continuingPrefix;
  final int maxInputCharsPerWord;

  /// Charge le tokenizer depuis un asset bundle (`tokenizer.json`).
  static Future<BertTokenizer> loadFromAsset(String assetPath) async {
    final raw = await rootBundle.loadString(assetPath);
    final data = json.decode(raw) as Map<String, dynamic>;
    final model = data['model'] as Map<String, dynamic>;
    final vocabJson = model['vocab'] as Map<String, dynamic>;
    final vocab = <String, int>{
      for (final e in vocabJson.entries) e.key: (e.value as num).toInt(),
    };
    final unk = (model['unk_token'] as String?) ?? '[UNK]';
    final continuing =
        (model['continuing_subword_prefix'] as String?) ?? '##';
    final maxChars =
        (model['max_input_chars_per_word'] as num?)?.toInt() ?? 100;

    int idOf(String tok, {int fallback = 0}) =>
        vocab[tok] ?? (fallback >= 0 ? fallback : 0);

    return BertTokenizer._(
      vocab: vocab,
      unkToken: idOf(unk, fallback: 100),
      clsToken: idOf('[CLS]', fallback: 101),
      sepToken: idOf('[SEP]', fallback: 102),
      padToken: idOf('[PAD]', fallback: 0),
      continuingPrefix: continuing,
      maxInputCharsPerWord: maxChars,
    );
  }

  // ---------------------------------------------------------------------
  // API
  // ---------------------------------------------------------------------

  /// Encode un texte en `BertEncoded` borné à `maxLength` (incl. [CLS]/[SEP]).
  BertEncoded encode(String text, {int maxLength = 128}) {
    final cleaned = _normalize(text);
    final words = _preTokenize(cleaned);
    final ids = <int>[clsToken];
    for (final w in words) {
      final pieces = _wordPiece(w);
      for (final id in pieces) {
        if (ids.length >= maxLength - 1) break;
        ids.add(id);
      }
      if (ids.length >= maxLength - 1) break;
    }
    ids.add(sepToken);
    final attentionMask = List<int>.filled(ids.length, 1);
    final tokenTypeIds = List<int>.filled(ids.length, 0);
    return BertEncoded(
      inputIds: ids,
      attentionMask: attentionMask,
      tokenTypeIds: tokenTypeIds,
    );
  }

  // ---------------------------------------------------------------------
  // Normalisation
  // ---------------------------------------------------------------------

  /// Nettoyage + suppression des diacritiques latines + lowercase.
  /// Évite la coûteuse `Unicode.normalize` (non disponible nativement)
  /// en mappant explicitement les caractères composés courants.
  static String _normalize(String s) {
    final lower = s.toLowerCase();
    final buf = StringBuffer();
    for (var i = 0; i < lower.length; i++) {
      final cu = lower.codeUnitAt(i);
      if (_isControl(cu)) {
        buf.writeCharCode(0x20); // espace
        continue;
      }
      if (_isWhitespace(cu)) {
        buf.writeCharCode(0x20);
        continue;
      }
      buf.writeCharCode(_stripAccent(cu));
    }
    return buf.toString();
  }

  static bool _isWhitespace(int cu) =>
      cu == 0x20 ||
      cu == 0x09 ||
      cu == 0x0A ||
      cu == 0x0D ||
      cu == 0xA0; // non-breaking space

  static bool _isControl(int cu) =>
      (cu >= 0 && cu < 0x20 && cu != 0x09 && cu != 0x0A && cu != 0x0D) ||
      cu == 0x7F;

  static int _stripAccent(int cu) {
    // Latin-1 supplement & Latin Extended-A : table de mapping minimaliste FR.
    switch (cu) {
      case 0x00E0:
      case 0x00E1:
      case 0x00E2:
      case 0x00E3:
      case 0x00E4:
      case 0x00E5:
        return 0x61;
      case 0x00E7:
        return 0x63;
      case 0x00E8:
      case 0x00E9:
      case 0x00EA:
      case 0x00EB:
        return 0x65;
      case 0x00EC:
      case 0x00ED:
      case 0x00EE:
      case 0x00EF:
        return 0x69;
      case 0x00F1:
        return 0x6E;
      case 0x00F2:
      case 0x00F3:
      case 0x00F4:
      case 0x00F5:
      case 0x00F6:
        return 0x6F;
      case 0x00F9:
      case 0x00FA:
      case 0x00FB:
      case 0x00FC:
        return 0x75;
      case 0x00FD:
      case 0x00FF:
        return 0x79;
      // œ → oe, æ → ae (approximations utiles)
      case 0x0153:
        return 0x6F; // on garde 1 caractère, BERT trouvera "oe" via subword
      case 0x00E6:
        return 0x61;
      default:
        return cu;
    }
  }

  // ---------------------------------------------------------------------
  // Pré-tokenisation : split sur whitespace + isolement de la ponctuation.
  // ---------------------------------------------------------------------

  static List<String> _preTokenize(String s) {
    final tokens = <String>[];
    final buf = StringBuffer();
    void flush() {
      if (buf.isNotEmpty) {
        tokens.add(buf.toString());
        buf.clear();
      }
    }

    for (var i = 0; i < s.length; i++) {
      final cu = s.codeUnitAt(i);
      if (_isWhitespace(cu)) {
        flush();
      } else if (_isPunctuation(cu)) {
        flush();
        tokens.add(String.fromCharCode(cu));
      } else {
        buf.writeCharCode(cu);
      }
    }
    flush();
    return tokens;
  }

  static bool _isPunctuation(int cu) {
    // Couverture : ASCII ponctuation + quelques caractères latins typographiques.
    if ((cu >= 0x21 && cu <= 0x2F) ||
        (cu >= 0x3A && cu <= 0x40) ||
        (cu >= 0x5B && cu <= 0x60) ||
        (cu >= 0x7B && cu <= 0x7E)) {
      return true;
    }
    // « » “ ” ‘ ’ — – …
    return cu == 0x00AB ||
        cu == 0x00BB ||
        cu == 0x201C ||
        cu == 0x201D ||
        cu == 0x2018 ||
        cu == 0x2019 ||
        cu == 0x2014 ||
        cu == 0x2013 ||
        cu == 0x2026;
  }

  // ---------------------------------------------------------------------
  // WordPiece : greedy longest match.
  // ---------------------------------------------------------------------

  List<int> _wordPiece(String word) {
    if (word.isEmpty) return const [];
    if (word.length > maxInputCharsPerWord) return [unkToken];

    final out = <int>[];
    var start = 0;
    final len = word.length;

    while (start < len) {
      var end = len;
      int? matchedId;
      String? matchedSub;
      while (start < end) {
        final sub = start == 0
            ? word.substring(start, end)
            : '$continuingPrefix${word.substring(start, end)}';
        final id = vocab[sub];
        if (id != null) {
          matchedId = id;
          matchedSub = sub;
          break;
        }
        end -= 1;
      }
      if (matchedId == null) {
        // Aucun sous-mot trouvé → tout le mot devient [UNK].
        return [unkToken];
      }
      out.add(matchedId);
      start += (matchedSub!.startsWith(continuingPrefix)
          ? matchedSub.length - continuingPrefix.length
          : matchedSub.length);
    }

    return out;
  }
}
