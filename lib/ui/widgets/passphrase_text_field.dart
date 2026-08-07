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
      // Sélection CONSERVÉE, mais COPIER et COUPER retirés du menu.
      //
      // Deux erreurs successives ici, et la seconde était la mienne.
      // D'abord `enableInteractiveSelection: !_hidden` : la copie redevenait
      // possible dès que l'utilisateur appuyait sur l'œil pour relire sa
      // saisie — au moment précis où le secret est le plus exposé. Un
      // long-press → « Tout sélectionner » → Copier envoyait la passphrase au
      // presse-papiers, lisible par les gestionnaires tiers et par certains
      // claviers.
      // Puis, en corrigeant, `enableInteractiveSelection: false` — qui coupe
      // AUSSI le collage et le repositionnement du curseur. Quelqu'un dont la
      // passphrase vient d'un gestionnaire de mots de passe devait la retaper
      // entièrement, à l'aveugle. Relevé par une relecture externe (GPT-5.5).
      //
      // La bonne granularité est le menu : on garde coller, tout
      // sélectionner et le curseur ; on retire copier et couper, les deux
      // seuls gestes qui FONT SORTIR le secret du champ.
      enableInteractiveSelection: true,
      contextMenuBuilder: (context, editableTextState) {
        final items = editableTextState.contextMenuButtonItems
            .where(
              (item) =>
                  item.type != ContextMenuButtonType.copy &&
                  item.type != ContextMenuButtonType.cut,
            )
            .toList();
        if (items.isEmpty) return const SizedBox.shrink();
        return AdaptiveTextSelectionToolbar.buttonItems(
          anchors: editableTextState.contextMenuAnchors,
          buttonItems: items,
        );
      },
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
