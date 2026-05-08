/// Dialogue bloquant de progression (PopScope canPop=false + spinner + textes).
/// Centralise le pattern dupliqué entre `_VaultConvertProgressDialog`
/// (folders_drawer.dart) et `_PanicProgressDialog` (settings_screen.dart).
library;

import 'package:flutter/material.dart';

class BlockingProgressDialog extends StatelessWidget {
  const BlockingProgressDialog({
    super.key,
    required this.title,
    this.subtitle,
  });

  final String title;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return PopScope(
      canPop: false,
      child: AlertDialog(
        // `Semantics(liveRegion:true, label:title)` : TalkBack annonce le
        // titre lorsque le dialog apparaît, et toute mise à jour du title
        // (ex. progression par étape) est annoncée. Préserve la promesse
        // a11y de `_VaultConvertProgressDialog` qui le faisait inline.
        content: Semantics(
          liveRegion: true,
          label: subtitle == null ? title : '$title — $subtitle',
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SizedBox(
                width: 40,
                height: 40,
                child: CircularProgressIndicator(strokeWidth: 3),
              ),
              const SizedBox(height: 16),
              Text(title, style: theme.textTheme.titleMedium),
              if (subtitle != null) ...[
                const SizedBox(height: 6),
                Text(
                  subtitle!,
                  style: theme.textTheme.bodySmall,
                  textAlign: TextAlign.center,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
