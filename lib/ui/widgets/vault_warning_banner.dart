/// Banner d'avertissement Container errorContainer + icône warning + texte.
/// Centralise le pattern dupliqué entre vault_passphrase_sheets.dart et
/// vault_pin_sheets.dart.
library;

import 'package:flutter/material.dart';

class VaultWarningBanner extends StatelessWidget {
  const VaultWarningBanner({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.errorContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.warning_amber_outlined, color: cs.onErrorContainer),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              message,
              // v1.0.7 UI I2 — base sur `bodyMedium` du textTheme pour
              // respecter `MediaQuery.textScaler` (a11y). `bodyMedium`
              // est à 14sp dans le thème par défaut Material 3.
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: cs.onErrorContainer,
                height: 1.35,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
