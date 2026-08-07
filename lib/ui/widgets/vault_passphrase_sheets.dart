/// Bottom sheets de création et de déverrouillage d'un coffre.
///
/// Deux flux distincts :
///
/// - **Création** ([showCreateVaultSheet]) : avertissement strict
///   « passphrase oubliée = données perdues », saisie 2× (champ + champ
///   confirmation), validation longueur minimale, bouton désactivé tant
///   que les 2 champs ne sont pas identiques et longueur OK.
///
/// - **Déverrouillage** ([showUnlockVaultSheet]) : champ unique, bouton
///   « Déverrouiller » qui valide via [FolderVaultService.unlock].
///   En cas de mauvaise passphrase, message rouge sous le champ et
///   l'utilisateur peut retenter sans fermer le sheet.
///
/// Aucune des deux versions ne stocke la passphrase : elle vit
/// uniquement dans le `TextEditingController` qui est `dispose`d à la
/// fermeture.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/folder.dart';
import '../../l10n/app_localizations.dart';
import '../../services/secure_window_service.dart';
import '../../services/security/folder_vault_service.dart';
import 'passphrase_text_field.dart';
import 'sheet_handle.dart';
import 'vault_warning_banner.dart';

// ─── Création ─────────────────────────────────────────────────────────

/// Affiche le sheet de création de coffre. Retourne la passphrase
/// validée (jamais vide, jamais inférieure au min) ou `null` si
/// l'utilisateur annule.
Future<String?> showCreateVaultSheet({
  required BuildContext context,
  required String folderName,
}) {
  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    enableDrag: false,
    builder: (ctx) {
      // Padding réactif au clavier (`viewInsets.bottom` quand IME ouvert).
      return Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(ctx).bottom),
        child: _CreateVaultSheet(folderName: folderName),
      );
    },
  );
}

class _CreateVaultSheet extends StatefulWidget {
  const _CreateVaultSheet({required this.folderName});
  final String folderName;

  @override
  State<_CreateVaultSheet> createState() => _CreateVaultSheetState();
}

class _CreateVaultSheetState extends State<_CreateVaultSheet>
    with SecureWindowGuardMixin {
  final _pass1 = TextEditingController();
  final _pass2 = TextEditingController();

  bool get _lengthOk =>
      _pass1.text.length >= AppConstants.vaultPassphraseMinLength;
  bool get _matchOk => _pass1.text.isNotEmpty && _pass1.text == _pass2.text;
  bool get _canSubmit => _lengthOk && _matchOk;

  @override
  void initState() {
    super.initState();
    _pass1.addListener(() => setState(() {}));
    _pass2.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    // ⚠️ VIDER AVANT DE DISPOSER. `dispose()` libère le contrôleur mais ne
    // touche pas à la `String` qu'il détient : la passphrase restait
    // référencée jusqu'au passage du ramasse-miettes, sur TOUS les chemins de
    // sortie — succès, annulation, retour système. Dart n'offre pas de
    // chaîne effaçable ; vider le contrôleur est ce qu'on peut faire, et
    // c'est mieux que rien. Relevé en CRITIQUE par une relecture externe
    // (GPT-5.5).
    _pass1.clear();
    _pass2.clear();
    _pass1.dispose();
    _pass2.dispose();
    super.dispose();
  }

  void _submit() {
    if (!_canSubmit) return;
    // La valeur est lue AVANT d'être effacée : l'appelant la reçoit, le
    // champ ne la garde pas.
    final saisie = _pass1.text;
    _pass1.clear();
    _pass2.clear();
    Navigator.of(context).pop(saisie);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SheetHandle(),
            Row(
              children: [
                Icon(Icons.lock_outline, color: cs.error),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    t.vaultPassCreateTitle,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              t.vaultPassCreateBody,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: cs.onSurfaceVariant,
                height: 1.35,
              ),
            ),
            const SizedBox(height: 12),
            VaultWarningBanner(message: t.vaultPassWarningLost),
            const SizedBox(height: 16),
            PassphraseTextField(
              controller: _pass1,
              labelText: t.vaultPassField,
              autofocus: true,
              textInputAction: TextInputAction.next,
              helperText: t.vaultPassMinLength(
                AppConstants.vaultPassphraseMinLength,
              ),
            ),
            const SizedBox(height: 12),
            PassphraseTextField(
              controller: _pass2,
              labelText: t.vaultPassConfirmField,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _submit(),
              errorText: (_pass2.text.isNotEmpty && !_matchOk)
                  ? t.vaultPassMismatch
                  : null,
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: Text(t.commonCancel),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _canSubmit ? _submit : null,
                    icon: const Icon(Icons.lock_outline),
                    label: Text(t.vaultPassCreateAction),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}

// ─── Déverrouillage ───────────────────────────────────────────────────

/// Affiche le sheet de déverrouillage d'un coffre. Retourne `true` si
/// l'utilisateur a réussi à déverrouiller (`unlock` du service a réussi),
/// sinon `false` (annulation) ou `null`.
///
/// Le sheet gère lui-même l'appel à [FolderVaultService.unlock], affiche
/// un loader pendant l'Argon2id (~1-2 s sur S9) et un message d'erreur
/// si la passphrase est incorrecte — l'utilisateur peut retenter sans
/// fermer.
Future<bool?> showUnlockVaultSheet({
  required BuildContext context,
  required Folder folder,
}) {
  return showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    isDismissible: true,
    builder: (ctx) {
      return Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(ctx).bottom),
        child: _UnlockVaultSheet(folder: folder),
      );
    },
  );
}

class _UnlockVaultSheet extends StatefulWidget {
  const _UnlockVaultSheet({required this.folder});
  final Folder folder;

  @override
  State<_UnlockVaultSheet> createState() => _UnlockVaultSheetState();
}

class _UnlockVaultSheetState extends State<_UnlockVaultSheet>
    with SecureWindowGuardMixin {
  final _passCtrl = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    // Même raison que la feuille de création : vider avant de disposer, pour
    // que la passphrase ne reste pas référencée après une annulation ou un
    // retour système.
    _passCtrl.clear();
    _passCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy) return;
    if (_passCtrl.text.isEmpty) return;
    final t = AppLocalizations.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
    final vault = context.read<FolderVaultService>();
    try {
      await vault.unlock(folder: widget.folder, passphrase: _passCtrl.text);
      if (!mounted) return;
      // A11y : annonce TalkBack/lecteur d'écran que le coffre est ouvert.
      unawaited(
        // ignore: deprecated_member_use
        SemanticsService.announce(
          t.homeAnnounceVaultUnlocked,
          TextDirection.ltr,
        ),
      );
      Navigator.of(context).pop(true);
    } on WrongPassphraseException {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = t.vaultPassWrong;
      });
      // Vide le champ, focus reste — UX standard de retry
      _passCtrl.clear();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        // ⚠️ PAS DE `e.toString()` SUR UN ÉCRAN DE DÉVERROUILLAGE. Une
        // exception de couche basse peut porter un alias Keystore, un chemin
        // de fichier, un détail de format — voire la valeur fautive. Le type
        // suffit à diagnostiquer sans rien exposer. Relevé par une relecture
        // externe (GPT-5.5).
        _error = t.commonErrorWith(e.runtimeType.toString());
      });
      // Le champ est vidé sur CE chemin aussi : jusqu'ici seule l'erreur de
      // passphrase incorrecte le faisait, et une erreur technique laissait la
      // saisie à l'écran et en mémoire.
      _passCtrl.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SheetHandle(),
            Row(
              children: [
                Icon(Icons.lock_outline, color: cs.error),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    t.vaultPassUnlockTitle,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              t.vaultPassUnlockBody(widget.folder.name),
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
            ),
            const SizedBox(height: 12),
            PassphraseTextField(
              controller: _passCtrl,
              labelText: t.vaultPassField,
              enabled: !_busy,
              autofocus: true,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _submit(),
              errorText: _error,
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _busy
                        ? null
                        : () => Navigator.of(context).pop(false),
                    child: Text(t.commonCancel),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _busy ? null : _submit,
                    icon: _busy
                        ? Semantics(
                            liveRegion: true,
                            label: t.vaultPassDeriving,
                            child: const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                          )
                        : const Icon(Icons.lock_open_outlined),
                    label: Text(
                      _busy ? t.vaultPassDeriving : t.vaultPassUnlockAction,
                    ),
                  ),
                ),
              ],
            ),
            // L4 — ce libellé n'a de sens QUE pendant la dérivation Argon2id.
            // Affiché en permanence, il annonçait un calcul en cours alors que
            // la sheet était au repos (le bouton ci-dessus gère déjà l'état
            // `_busy` avec son spinner + son label).
            if (_busy) ...[
              const SizedBox(height: 8),
              Text(
                t.vaultPassDeriving,
                style: Theme.of(
                  context,
                ).textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
              ),
            ],
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}
