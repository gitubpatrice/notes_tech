/// Helpers de dialogues partagés (confirmation destructive, blocage).
library;

import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';

/// Dialog de confirmation. Renvoie `true` si l'utilisateur a confirmé.
/// Si [destructive], bouton primaire en rouge (cs.error).
Future<bool?> showConfirmDialog(
  BuildContext context, {
  required String title,
  required String body,
  required String yesLabel,
  String? noLabel,
  bool destructive = false,
}) {
  final t = AppLocalizations.of(context);
  return showDialog<bool>(
    context: context,
    builder: (ctx) {
      final cs = Theme.of(ctx).colorScheme;
      return AlertDialog(
        title: Text(title),
        content: SingleChildScrollView(child: Text(body)),
        actions: [
          TextButton(
            // U3 v1.0.9 — `autofocus` sur Annuler quand le dialog est
            // destructif : la touche Entrée (clavier physique / a11y)
            // déclenche l'annulation, pas la suppression. Safe default.
            autofocus: destructive,
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(noLabel ?? t.commonCancel),
          ),
          FilledButton(
            style: destructive
                ? FilledButton.styleFrom(
                    backgroundColor: cs.errorContainer,
                    foregroundColor: cs.onErrorContainer,
                  )
                : null,
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(yesLabel),
          ),
        ],
      );
    },
  );
}
