/// Façade d'actions UI sur une note (partage, exports simples).
///
/// Permet de garder les écrans fins. Étendu plus tard par export PDF / vault.
library;

import 'package:flutter/services.dart';

import '../data/models/note.dart';

class NoteActions {
  const NoteActions();

  /// Copie le contenu Markdown brut dans le presse-papier.
  Future<void> copyMarkdown(Note note) {
    return Clipboard.setData(ClipboardData(text: note.content));
  }
}
