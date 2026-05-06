/// Poignée Material 3 standard en haut des bottom sheets.
///
/// Extrait pour être réutilisé par tous les sheets de l'app
/// (vault create/unlock passphrase, vault create/unlock PIN, mode
/// chooser, link autocomplete…) sans dupliquer le `Container` 36×4 dp.
library;

import 'package:flutter/material.dart';

class SheetHandle extends StatelessWidget {
  const SheetHandle({super.key});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        width: 36,
        height: 4,
        margin: const EdgeInsets.only(bottom: 12),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.outlineVariant,
          borderRadius: BorderRadius.circular(2),
        ),
      ),
    );
  }
}
