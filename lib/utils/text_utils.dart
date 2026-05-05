/// Utilitaires de normalisation de texte partagés entre tokenizer et embedder.
library;

class TextUtils {
  TextUtils._();

  /// Mappe un code unit (UTF-16) vers son équivalent ASCII si c'est un
  /// caractère latin diacrité connu, sinon retourne le code unit inchangé.
  ///
  /// Couvre : Latin-1 supplement (0x00C0–0x00FF) + quelques ligatures
  /// usuelles (œ, æ). Suffisant pour FR/EN/ES/IT/DE simples.
  /// Les caractères latins étendus (ē, ı, ş…) ne sont pas couverts.
  static int stripLatinDiacritic(int cu) {
    switch (cu) {
      case 0x00E0:
      case 0x00E1:
      case 0x00E2:
      case 0x00E3:
      case 0x00E4:
      case 0x00E5:
        return 0x61; // a
      case 0x00E6:
        return 0x61; // æ → a (perte ligature, acceptable)
      case 0x00E7:
        return 0x63; // c
      case 0x00E8:
      case 0x00E9:
      case 0x00EA:
      case 0x00EB:
        return 0x65; // e
      case 0x00EC:
      case 0x00ED:
      case 0x00EE:
      case 0x00EF:
        return 0x69; // i
      case 0x00F1:
        return 0x6E; // n
      case 0x00F2:
      case 0x00F3:
      case 0x00F4:
      case 0x00F5:
      case 0x00F6:
        return 0x6F; // o
      case 0x0153:
        return 0x6F; // œ → o
      case 0x00F9:
      case 0x00FA:
      case 0x00FB:
      case 0x00FC:
        return 0x75; // u
      case 0x00FD:
      case 0x00FF:
        return 0x79; // y
      default:
        return cu;
    }
  }
}
