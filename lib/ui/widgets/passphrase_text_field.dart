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
