/// TextField passphrase avec œil show/hide intégré.
/// Centralise le pattern dupliqué 3× dans vault_passphrase_sheets.dart.
library;

import 'package:flutter/material.dart';

import '../../l10n/app_localizations.dart';

class PassphraseTextField extends StatefulWidget {
  const PassphraseTextField({
    super.key,
    required this.controller,
    required this.labelText,
    this.enabled = true,
    this.errorText,
    this.helperText,
    this.onSubmitted,
    this.autofocus = false,
    this.textInputAction,
  });

  final TextEditingController controller;
  final String labelText;
  final bool enabled;
  final String? errorText;
  final String? helperText;
  final ValueChanged<String>? onSubmitted;
  final bool autofocus;
  final TextInputAction? textInputAction;

  @override
  State<PassphraseTextField> createState() => _PassphraseTextFieldState();
}

class _PassphraseTextFieldState extends State<PassphraseTextField> {
  bool _hidden = true;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return TextField(
      controller: widget.controller,
      enabled: widget.enabled,
      obscureText: _hidden,
      autofocus: widget.autofocus,
      autocorrect: false,
      enableSuggestions: false,
      // U1 v1.0.9 — désactive le service Autofill Android (Samsung Pass /
      // Google Autofill) sur la passphrase du coffre. Le service Autofill
      // pourrait capturer / proposer la valeur cross-app.
      autofillHints: const <String>[],
      // U2 v1.0.9 — `visiblePassword` : neutralise les suggestions et
      // l'auto-capitalisation de SwiftKey / Gboard tiers (au-delà du
      // `autocorrect/enableSuggestions: false` qui peut être ignoré par
      // certains claviers Android).
      keyboardType: TextInputType.visiblePassword,
      // U11 v1.0.9 — sélection/copie désactivée tant que masqué : empêche
      // un long-press → "Tout sélectionner" → Copier qui exposerait la
      // passphrase au clipboard (capté par les clipboard managers tiers
      // sur Android 13-).
      enableInteractiveSelection: !_hidden,
      textInputAction: widget.textInputAction,
      onSubmitted: widget.onSubmitted,
      decoration: InputDecoration(
        labelText: widget.labelText,
        helperText: widget.helperText,
        errorText: widget.errorText,
        suffixIcon: IconButton(
          icon: Icon(
            _hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined,
          ),
          tooltip: _hidden ? t.passphraseShowTooltip : t.passphraseHideTooltip,
          onPressed: () => setState(() => _hidden = !_hidden),
        ),
      ),
    );
  }
}
