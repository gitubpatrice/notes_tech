/// Façade d'actions UI sur une note (partage, exports simples).
///
/// Permet de garder les écrans fins. Étendu plus tard par export PDF / vault.
library;

import 'dart:async';

import 'package:flutter/services.dart';

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

  /// Copie le contenu Markdown brut dans le presse-papier avec marquage
  /// "sensible" (Android 13+) + auto-clear 60 s.
  Future<void> copyMarkdown(Note note) async {
    final text = note.content;
    await _copySensitive(text);
  }

  /// Implémentation factorisable : tente le path natif sensitive, retombe
  /// sur `Clipboard.setData` standard si le channel n'est pas disponible
  /// (tests, plate-formes non supportées).
  Future<void> _copySensitive(String text) async {
    bool nativeOk = false;
    try {
      final r = await _channel.invokeMethod<bool>('copySensitive', {
        'text': text,
      });
      nativeOk = r == true;
    } catch (_) {
      nativeOk = false;
    }
    if (!nativeOk) {
      await Clipboard.setData(ClipboardData(text: text));
    }
    _ownTextSnapshot = text;
    _clearTimer?.cancel();
    _clearTimer = Timer(_autoClearAfter, _autoClearIfMine);
  }

  /// Vide le clipboard SEULEMENT si la valeur courante est encore celle
  /// que l'on a posée — évite d'effacer un autre secret que l'utilisateur
  /// a copié entretemps depuis une autre app.
  static Future<void> _autoClearIfMine() async {
    final mine = _ownTextSnapshot;
    if (mine == null) return;
    try {
      final cur = await Clipboard.getData(Clipboard.kTextPlain);
      if (cur?.text == mine) {
        await Clipboard.setData(const ClipboardData(text: ''));
      }
    } catch (_) {
      /* best-effort */
    }
    _ownTextSnapshot = null;
    _clearTimer = null;
  }

  /// Force un clear immédiat (utilisé par PanicService).
  static Future<void> cancelAndClear() async {
    _clearTimer?.cancel();
    _clearTimer = null;
    _ownTextSnapshot = null;
    try {
      await Clipboard.setData(const ClipboardData(text: ''));
    } catch (_) {
      /* best-effort */
    }
  }
}
