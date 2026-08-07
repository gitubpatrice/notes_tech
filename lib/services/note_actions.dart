/// Façade d'actions UI sur une note (partage, exports simples).
///
/// Permet de garder les écrans fins. Étendu plus tard par export PDF / vault.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import '../core/exceptions.dart';
import '../data/models/note.dart';

class NoteActions {
  const NoteActions._internal();

  static const NoteActions instance = NoteActions._internal();

  /// F4 v1.1.0 — MethodChannel natif Kotlin qui pose
  /// `ClipDescription.EXTRA_IS_SENSITIVE` (Android 13+) sur le clip et
  /// déclenche un auto-clear minuté côté Dart (60 s par défaut). Avant :
  /// `Clipboard.setData(ClipboardData(text: note.content))` exposait le
  /// plaintext d'une note vault déchiffrée à TOUT clipboard manager
  /// tiers + Samsung Knox clipboard history sans expiration ni marqueur
  /// "sensible".
  static const MethodChannel _channel = MethodChannel(
    'com.filestech.notes_tech/clipboard',
  );

  /// Durée par défaut avant clear automatique du clipboard.
  static const Duration _autoClearAfter = Duration(seconds: 60);

  /// Timer actif d'auto-clear (un seul à la fois).
  static Timer? _clearTimer;

  /// Texte courant déposé par cette façade (utilisé pour vérifier qu'on
  /// efface bien NOTRE valeur et pas un secret tiers que l'utilisateur a
  /// copié entretemps).
  static String? _ownTextSnapshot;

  /// Jeton de la dernière opération de presse-papiers, incrémenté par CHAQUE
  /// copie ET par chaque purge.
  ///
  /// ⚠️ UN SEUL COMPTEUR POUR LES DEUX, et c'est le point. La version
  /// précédente n'invalidait qu'aux purges : deux copies successives se
  /// marchaient donc dessus. Une relecture externe (GPT-5.5) a décrit la
  /// séquence exacte — la minuterie de la copie A se réveille, attend la
  /// lecture du presse-papiers, l'utilisateur copie B pendant cette attente,
  /// puis A reprend et remet l'état à zéro. La minuterie de B existe encore
  /// mais son état a disparu : au réveil elle ne trouve plus rien à effacer,
  /// et **le contenu de B reste indéfiniment dans le presse-papiers**.
  ///
  /// Avec un jeton commun, chaque opération sait si elle est encore la plus
  /// récente. Une opération périmée ne touche plus à rien.
  static int _jeton = 0;

  /// Jeton de la copie qui détient l'état courant (`_ownTextSnapshot` et
  /// `_clearTimer`). Une minuterie ne nettoie que si elle le possède encore.
  static int _jetonProprietaire = 0;

  /// Vrai si l'effacement automatique de la copie COURANTE a déjà été réarmé
  /// une fois. Remis à zéro à chaque nouvelle copie — il était global, donc
  /// une copie pouvait consommer le réessai de la suivante.
  static bool _reessaiAutoClear = false;

  /// Copie le contenu Markdown brut dans le presse-papier avec marquage
  /// "sensible" (Android 13+) + auto-clear 60 s.
  /// [fromVault] : la note vient d'un dossier coffre. Le repli non sécurisé
  /// est alors REFUSÉ — voir `_copySensitive`.
  Future<void> copyMarkdown(Note note, {bool fromVault = false}) async {
    final text = note.content;
    await _copySensitive(text, refuserRepli: fromVault);
  }

  /// Implémentation factorisable : tente le path natif sensitive, retombe
  /// sur `Clipboard.setData` standard si le channel n'est pas disponible
  /// (tests, plate-formes non supportées).
  Future<void> _copySensitive(String text, {bool refuserRepli = false}) async {
    // Chaque copie prend son propre jeton : elle saura si une autre copie ou
    // une purge est passée pendant qu'elle attendait le canal natif.
    final jeton = ++_jeton;
    bool nativeOk = false;
    try {
      final r = await _channel.invokeMethod<bool>('copySensitive', {
        'text': text,
      });
      nativeOk = r == true;
    } catch (_) {
      nativeOk = false;
    }
    // ⚠️ NE PAS RESSUSCITER LE TEXTE APRÈS UNE PURGE D'URGENCE.
    //
    // Scénario : l'utilisateur copie une note, l'appel natif est encore en
    // vol, il déclenche le mode panique. `cancelAndClear()` vide le
    // presse-papiers. Puis l'appel natif interrompu échoue, on tombe dans le
    // repli, et le texte en clair est RÉINJECTÉ une fraction de seconde
    // après la purge — exposé à toutes les applications. Relevé en CRITIQUE
    // par une relecture externe (Gemini 3.1 Pro).
    //
    // La génération tranche : toute purge l'incrémente, et un repli dont la
    // génération a changé est abandonné.
    if (jeton != _jeton) {
      // ⚠️ UN SIMPLE `return` NE SUFFIT PAS. Le handler natif a pu ECRIRE le
      // presse-papiers AVANT que la purge d'urgence ne passe : dans ce cas le
      // texte y est encore, sans instantané ni minuteur pour le retirer. La
      // génération n'empêche que ce qui vient APRES l'`await`, pas l'effet de
      // bord deja produit pendant. Relevé en CRITIQUE par une relecture
      // externe (GPT-5.5) sur le correctif de génération lui-même.
      await _viderSiCEstCeTexte(text);
      return;
    }
    if (!nativeOk) {
      // ⚠️ REPLI REFUSÉ POUR LE CONTENU D'UN COFFRE.
      //
      // Le repli posait le texte dans le presse-papiers ORDINAIRE quand le
      // canal natif échouait — donc sans le marqueur « sensible » d'Android
      // 13+, qui empêche l'aperçu et signale aux gestionnaires de ne pas
      // historiser. Pour du contenu de coffre, c'était un repli qui échoue du
      // mauvais côté : en cas de problème, l'application dégradait la
      // protection au lieu de refuser. Relevé par une relecture externe
      // (GPT-5.5).
      //
      // Hors coffre, le repli reste : refuser toute copie parce qu'un canal
      // de plateforme manque casserait une fonction ordinaire sans rien
      // protéger de sensible.
      if (refuserRepli) {
        // ⚠️ NETTOYER AVANT DE REFUSER. `nativeOk == false` ne prouve PAS que
        // le natif n'a rien écrit : `setPrimaryClip` peut réussir puis le
        // code natif échouer en posant les extras sensibles, ou rendre une
        // erreur après coup. On annonçait « copie refusée » pendant que le
        // texte était dans le presse-papiers, sans instantané ni minuteur
        // pour l'en retirer. Relevé par une relecture externe (GPT-5.5).
        await _viderSiCEstCeTexte(text);
        throw const NotesTechException(
          'Copie refusée : le presse-papiers sécurisé est indisponible.',
        );
      }
      await Clipboard.setData(ClipboardData(text: text));
    }
    // Une purge a pu tomber pendant l'écriture de repli elle-même : armer un
    // minuteur et un instantané après coup laisserait un état incohérent
    // pendant 60 s — un instantané qui ne correspond plus au presse-papiers,
    // et un minuteur armé après la purge.
    if (jeton != _jeton) {
      await _viderSiCEstCeTexte(text);
      return;
    }
    // Cette copie est la plus récente : elle prend la propriété de l'état.
    _jetonProprietaire = jeton;
    _ownTextSnapshot = text;
    _reessaiAutoClear = false;
    _clearTimer?.cancel();
    _clearTimer = Timer(_autoClearAfter, () => _autoClearIfMine(jeton));
  }

  /// Vide le presse-papiers s'il contient encore [texte].
  ///
  /// Le test évite d'écraser un contenu DIFFÉRENT que l'utilisateur aurait
  /// copié entretemps depuis une autre application.
  ///
  /// ⚠️ CE N'EST PAS UNE PREUVE DE PROPRIÉTÉ, et il ne faut pas le lire
  /// comme tel. Deux cas restent : l'utilisateur a copié ailleurs la MÊME
  /// valeur — on l'efface alors que ce n'est pas notre clip — et le
  /// presse-papiers peut changer entre la lecture et l'écriture. L'API
  /// Clipboard de Flutter n'expose ni propriétaire ni jeton : l'égalité de
  /// texte est la meilleure heuristique disponible, pas une garantie.
  /// Formulation corrigée après une relecture externe (GPT-5.5) qui a relevé
  /// que le commentaire précédent promettait plus que le code ne tient.
  static Future<void> _viderSiCEstCeTexte(String texte) async {
    try {
      final courant = await Clipboard.getData(Clipboard.kTextPlain);
      if (courant?.text == texte) {
        await Clipboard.setData(const ClipboardData(text: ''));
      }
    } catch (_) {
      /* best-effort */
    }
  }

  /// Vide le clipboard SEULEMENT si la valeur courante est encore celle
  /// que l'on a posée — évite d'effacer un autre secret que l'utilisateur
  /// a copié entretemps depuis une autre app.
  static Future<void> _autoClearIfMine(int jeton) async {
    // Minuterie périmée : une copie plus récente ou une purge est passée.
    // Elle ne doit ni effacer, ni toucher à l'état de l'opération en cours.
    if (jeton != _jetonProprietaire) return;
    final mine = _ownTextSnapshot;
    if (mine == null) return;
    // ⚠️ NE PAS OUBLIER LE TEXTE SI L'EFFACEMENT A ÉCHOUÉ.
    //
    // L'instantané et le minuteur étaient remis à zéro dans tous les cas,
    // même quand `setData('')` levait : le contenu restait alors dans le
    // presse-papiers, et plus rien ne repassait jamais dessus. On réarme donc
    // un tour plutôt que d'abandonner. Relevé par une relecture externe
    // (GPT-5.5).
    try {
      final cur = await Clipboard.getData(Clipboard.kTextPlain);
      if (cur?.text == mine) {
        await Clipboard.setData(const ClipboardData(text: ''));
      }
    } catch (_) {
      // Un seul réarmement : si le presse-papiers reste inaccessible, insister
      // indéfiniment ne ferait que maintenir le texte en mémoire ici.
      if (!_reessaiAutoClear) {
        _reessaiAutoClear = true;
        _clearTimer = Timer(_autoClearAfter, () => _autoClearIfMine(jeton));
        return;
      }
    }
    // Re-vérifié APRÈS les `await` : une copie plus récente a pu prendre la
    // main entretemps, et son état ne doit pas être efface par nous.
    if (jeton != _jetonProprietaire) return;
    _reessaiAutoClear = false;
    _ownTextSnapshot = null;
    _clearTimer = null;
  }

  /// Force un clear immédiat (utilisé par PanicService).
  static Future<void> cancelAndClear() async {
    // Invalide toute copie EN VOL avant même de vider : une copie qui se
    // termine après nous ne doit pas réécrire ce qu'on efface.
    // Invalide toute copie EN VOL et toute minuterie armée.
    final jeton = ++_jeton;
    _jetonProprietaire = jeton;
    _clearTimer?.cancel();
    _clearTimer = null;
    // ⚠️ L'INSTANTANE N'EST PAS OUBLIE TANT QUE LA PURGE N'A PAS ABOUTI.
    // Il l'était d'emblée : si l'effacement échouait, plus rien ne savait
    // quel texte retirer, et aucune minuterie ne repassait. Relevé par une
    // relecture externe (GPT-5.5).
    final aRetirer = _ownTextSnapshot;
    // ⚠️ LA PURGE D'URGENCE NE DOIT PAS AVALER SON PROPRE ÉCHEC.
    //
    // Elle attrapait tout en silence : si `setData('')` échouait — canal mort,
    // OS qui refuse, plugin indisponible — le contenu restait dans le
    // presse-papiers, l'état interne était nettoyé, aucun minuteur ne
    // repassait, et le mode panique enregistrait l'étape comme réussie.
    // C'est le pire endroit pour un « best-effort » muet. Relevé par une
    // relecture externe (GPT-5.5).
    //
    // Une seule reprise, puis on lève : `PanicService._runStep` enregistre
    // l'échec, et l'écran de fin n'annonce plus un effacement complet.
    for (var essai = 0; essai < 2; essai++) {
      try {
        await Clipboard.setData(const ClipboardData(text: ''));
        _ownTextSnapshot = null;
        return;
      } catch (_) {
        if (essai == 1) {
          // Échec confirmé : on garde de quoi réessayer plus tard, et on
          // réarme une minuterie plutôt que d'abandonner le texte sur place.
          _ownTextSnapshot = aRetirer;
          _reessaiAutoClear = false;
          _clearTimer = Timer(_autoClearAfter, () => _autoClearIfMine(jeton));
          throw const NotesTechException(
            'Presse-papiers non vidé : le contenu copié peut subsister.',
          );
        }
      }
    }
  }
}
