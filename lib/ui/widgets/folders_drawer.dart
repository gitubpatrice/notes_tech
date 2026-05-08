/// Drawer latéral listant les dossiers de notes.
///
/// Modèle UX :
/// - 1ʳᵉ entrée virtuelle « Toutes les notes » qui retire le filtre.
/// - 2ᵉ entrée fixe « Boîte de réception » correspondant au dossier
///   `inbox` (renommable mais non-supprimable, contraint par DAO).
/// - Liste des dossiers utilisateur ordonnés par date de mise à jour
///   décroissante.
/// - Long-press sur un dossier utilisateur → menu Renommer / Supprimer.
/// - Bouton « Nouveau dossier » en bas (FAB-like).
///
/// Le drawer écoute `FoldersRepository.changes` pour rebuild
/// automatiquement après création / renommage / suppression.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/folder.dart';
import '../../data/repositories/folders_repository.dart';
import '../../data/repositories/notes_repository.dart';
import '../../l10n/app_localizations.dart';
import '../../services/security/folder_vault_service.dart';
import 'folder_dialogs.dart';
import 'vault_passphrase_sheets.dart';
import 'vault_pin_sheets.dart';

/// Identifiant fictif utilisé par le drawer pour signaler « Toutes les
/// notes ». Ré-export pour les call-sites (HomeScreen).
const String kAllNotesSentinel = AppConstants.allFoldersSentinel;

/// ID du dossier par défaut, protégé en suppression côté DAO. Ré-export.
const String kInboxFolderId = AppConstants.inboxFolderId;

class FoldersDrawer extends StatefulWidget {
  const FoldersDrawer({
    super.key,
    required this.currentFolderId,
    required this.onSelect,
  });

  /// `null` ou [kAllNotesSentinel] = aucun filtre. Sinon ID du dossier actif.
  final String? currentFolderId;
  final void Function(String? folderId) onSelect;

  @override
  State<FoldersDrawer> createState() => _FoldersDrawerState();
}

class _FoldersDrawerState extends State<FoldersDrawer> {
  late final FoldersRepository _repo;
  late final NotesRepository _notes;
  late Future<List<Folder>> _foldersFuture;
  // Stockée explicitement pour pouvoir cancel() en dispose — sans ça,
  // chaque ouverture du Drawer accumule un listener actif sur le
  // broadcast stream (Scaffold.drawer reconstruit l'instance State).
  StreamSubscription<void>? _foldersSub;

  @override
  void initState() {
    super.initState();
    _repo = context.read<FoldersRepository>();
    _notes = context.read<NotesRepository>();
    _foldersFuture = _repo.listAll();
    _foldersSub = _repo.changes.listen((_) {
      if (!mounted) return;
      setState(() => _foldersFuture = _repo.listAll());
    });
  }

  @override
  void dispose() {
    _foldersSub?.cancel();
    super.dispose();
  }

  bool _isAllSelected() =>
      widget.currentFolderId == null ||
      widget.currentFolderId == kAllNotesSentinel;

  Future<void> _createFolder() async {
    final t = AppLocalizations.of(context);
    final name = await showFolderNameDialog(
      context: context,
      title: t.folderCreateTitle,
      hint: t.folderCreateField,
    );
    if (name == null || !mounted) return;
    final folder = await _repo.create(name: name);
    widget.onSelect(folder.id);
    if (mounted) Navigator.of(context).pop(); // ferme le drawer
  }

  Future<void> _renameFolder(Folder folder) async {
    final t = AppLocalizations.of(context);
    final name = await showFolderNameDialog(
      context: context,
      title: t.folderRenameTitle,
      hint: t.folderRenameField,
      initial: folder.name,
    );
    if (name == null || name == folder.name) return;
    await _repo.rename(folder, name);
  }

  Future<void> _deleteFolder(Folder folder) async {
    if (folder.id == kInboxFolderId) return; // garde-fou redondant
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final vault = context.read<FolderVaultService>();
    final outcome = await confirmDeleteFolder(
      context: context,
      folderName: folder.name,
    );
    if (outcome == null || !mounted) return;

    if (outcome == FolderDeletionChoice.moveToInbox) {
      // Cas coffre : les notes sont chiffrées avec la folder_kek du
      // coffre source. Une fois déplacées vers l'inbox (sans coffre),
      // elles deviendraient illisibles à jamais. On les déchiffre AVANT
      // le move ; passphrase requise si le coffre est verrouillé.
      if (folder.isVault) {
        if (!vault.isUnlocked(folder.id)) {
          final ok = await showUnlockVaultAdaptive(
            context: context,
            folder: folder,
          );
          if (ok != true || !mounted) return;
        }
        try {
          final res = await vault.decryptAllNotesInFolder(folder.id);
          if (!mounted) return;
          if (res.failed > 0) {
            messenger.showSnackBar(
              SnackBar(
                content: Text(t.folderDeleteDecryptFailed(res.failed)),
                backgroundColor: Theme.of(context).colorScheme.error,
                behavior: SnackBarBehavior.floating,
                duration: const Duration(seconds: 8),
              ),
            );
            return;
          }
        } catch (e) {
          if (!mounted) return;
          messenger.showSnackBar(
            SnackBar(
              content: Text(t.folderDeleteCancelledError(e.toString())),
              behavior: SnackBarBehavior.floating,
            ),
          );
          return;
        }
      }
      // Pré-déplacement BATCH (UPDATE atomique unique) — couvre TOUTES
      // les notes du dossier, y compris archivées et en corbeille
      // (sinon le ON DELETE CASCADE qui suit les effacerait
      // définitivement, bypassant la rétention 30 jours).
      await moveAllNotesToInbox(_notes, fromFolderId: folder.id);
    }
    if (!mounted) return;
    // Verrouille la session avant suppression — libère la folder_kek en
    // RAM et évite qu'un futur dossier réutilisant l'id (improbable vu
    // l'UUID v4) hérite d'une session fantôme.
    if (folder.isVault) vault.lock(folder.id);
    // Coffre PIN : supprime aussi la clé Keystore (alias = vault_pin_<id>)
    // pour ne pas laisser d'orphelin dans le TEE/StrongBox.
    if (folder.isPinVault) {
      await vault.deletePinKey(folder.id);
    }
    await _repo.delete(folder.id);
    // Si l'utilisateur regardait ce dossier, on retombe sur "Toutes".
    if (widget.currentFolderId == folder.id) {
      widget.onSelect(null);
    }
  }

  void _select(String? id) {
    widget.onSelect(id);
    Navigator.of(context).pop(); // ferme le drawer
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final t = AppLocalizations.of(context);
    return Drawer(
      child: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
              child: Row(
                children: [
                  ExcludeSemantics(
                    child: Icon(
                      Icons.folder_copy_outlined,
                      color: theme.colorScheme.primary,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Semantics(
                    header: true,
                    child: Text(
                      t.homeFolders,
                      style: theme.textTheme.titleMedium,
                    ),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: FutureBuilder<List<Folder>>(
                future: _foldersFuture,
                builder: (context, snap) {
                  final all = snap.data ?? const <Folder>[];
                  // On extrait l'inbox pour la mettre en premier ;
                  // les autres restent triés par updated_at desc (DAO).
                  final inbox = all.firstWhere(
                    (f) => f.id == kInboxFolderId,
                    orElse: () => Folder(
                      id: kInboxFolderId,
                      name: t.homeFolderInbox,
                      createdAt: DateTime.fromMillisecondsSinceEpoch(0),
                      updatedAt: DateTime.fromMillisecondsSinceEpoch(0),
                    ),
                  );
                  final userFolders =
                      all.where((f) => f.id != kInboxFolderId).toList();

                  return ListView(
                    children: [
                      _DrawerTile(
                        icon: Icons.notes_outlined,
                        title: t.homeAllNotes,
                        selected: _isAllSelected(),
                        onTap: () => _select(null),
                      ),
                      _DrawerTile(
                        icon: Icons.inbox_outlined,
                        title: inbox.name,
                        selected: widget.currentFolderId == kInboxFolderId,
                        onTap: () => _select(kInboxFolderId),
                        onLongPress: () => _renameFolder(inbox),
                      ),
                      if (userFolders.isNotEmpty) ...[
                        Padding(
                          padding: const EdgeInsets.fromLTRB(20, 16, 20, 6),
                          child: Semantics(
                            header: true,
                            child: Text(
                              t.drawerHeaderFolders,
                              style: const TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                                letterSpacing: 1.1,
                              ),
                            ),
                          ),
                        ),
                        ...userFolders.map(
                          (f) => _DrawerTile(
                            // Cadenas rouge si dossier coffre, sinon
                            // dossier classique. Signal visuel fort,
                            // cohérent avec le badge cadenas sur les
                            // NoteCard verrouillées.
                            icon: f.isVault
                                ? Icons.lock_outline
                                : Icons.folder_outlined,
                            iconTint: f.isVault
                                ? Theme.of(context).colorScheme.error
                                : null,
                            title: f.name,
                            selected: widget.currentFolderId == f.id,
                            onTap: () => _select(f.id),
                            onLongPress: () => _showFolderMenu(f),
                            // 3 points explicite : sans ce bouton, les
                            // utilisateurs ne savent pas qu'ils peuvent
                            // renommer / verrouiller / convertir en
                            // coffre / supprimer (le long-press n'est
                            // pas découvrable).
                            trailing: IconButton(
                              tooltip: t.drawerFolderOptions,
                              icon: const Icon(Icons.more_vert),
                              onPressed: () => _showFolderMenu(f),
                            ),
                          ),
                        ),
                      ],
                    ],
                  );
                },
              ),
            ),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.all(12),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton.tonalIcon(
                  onPressed: _createFolder,
                  icon: const ExcludeSemantics(
                    child: Icon(Icons.create_new_folder_outlined),
                  ),
                  label: Text(t.drawerNewFolder),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _showFolderMenu(Folder folder) async {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final action = await showModalBottomSheet<_FolderAction>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.drive_file_rename_outline),
              title: Text(t.commonRename),
              onTap: () => Navigator.of(ctx).pop(_FolderAction.rename),
            ),
            // Vault : convertir un dossier ordinaire en coffre, ou
            // verrouiller maintenant un coffre déverrouillé. Caché si
            // déjà coffre + verrouillé (rien à faire).
            if (!folder.isVault)
              ListTile(
                leading: Icon(Icons.lock_outline, color: cs.error),
                title: Text(
                  t.drawerConvertToVault,
                  style: TextStyle(color: cs.error),
                ),
                subtitle: Text(t.drawerConvertToVaultSubtitle),
                onTap: () =>
                    Navigator.of(ctx).pop(_FolderAction.convertToVault),
              )
            else if (context
                .read<FolderVaultService>()
                .isUnlocked(folder.id))
              ListTile(
                leading: Icon(Icons.lock_outline, color: cs.error),
                title: Text(t.drawerLockNow),
                subtitle: Text(t.drawerLockNowSubtitle),
                onTap: () =>
                    Navigator.of(ctx).pop(_FolderAction.lockNow),
              ),
            ListTile(
              leading: Icon(
                Icons.delete_outline,
                color: Theme.of(ctx).colorScheme.error,
              ),
              title: Text(
                t.commonDelete,
                style: TextStyle(color: Theme.of(ctx).colorScheme.error),
              ),
              onTap: () => Navigator.of(ctx).pop(_FolderAction.delete),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (action == _FolderAction.rename) await _renameFolder(folder);
    if (action == _FolderAction.delete) await _deleteFolder(folder);
    if (action == _FolderAction.convertToVault) await _convertToVault(folder);
    if (action == _FolderAction.lockNow) {
      if (!mounted) return;
      context.read<FolderVaultService>().lock(folder.id);
    }
  }

  /// Conversion d'un dossier ordinaire en coffre :
  /// 1. Sheet création passphrase (avec confirmation 2x).
  /// 2. `vault.createVault` génère salt + folder_kek + verifier, persiste
  ///    les colonnes vault sur le folder, ouvre la session.
  /// 3. `vault.encryptAllNotesInFolder` re-encrypte toutes les notes
  ///    existantes du dossier — opération potentiellement longue.
  ///    On l'enveloppe dans un dialog de progression bloquant.
  Future<void> _convertToVault(Folder folder) async {
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final vault = context.read<FolderVaultService>();

    // v0.9 — choix mode passphrase vs PIN. L'user décide selon usage :
    // passphrase pour secret pro, PIN pour notes perso.
    final mode = await showVaultModeChooserSheet(
      context: context,
      folderName: folder.name,
    );
    if (mode == null || !mounted) return;

    final String? secret;
    if (mode == VaultMode.passphrase) {
      secret = await showCreateVaultSheet(
        context: context,
        folderName: folder.name,
      );
    } else {
      secret = await showCreatePinSheet(
        context: context,
        folderName: folder.name,
      );
    }
    if (secret == null || !mounted) return;

    final navigator = Navigator.of(context);
    unawaited(
      showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (_) => const _VaultConvertProgressDialog(),
      ),
    );
    try {
      final updated = mode == VaultMode.passphrase
          ? await vault.createVault(folder: folder, passphrase: secret)
          : await vault.createPinVault(folder: folder, pin: secret);
      // Re-encrypte toutes les notes existantes du dossier — la session
      // est active suite au createVault, donc encryptAllNotesInFolder
      // peut accéder à la folder_kek.
      final result = await vault.encryptAllNotesInFolder(updated.id);
      if (!mounted) return;
      navigator.pop(); // ferme le dialog progress
      // Affichage HONNÊTE du résultat : si failed > 0, on alerte
      // l'utilisateur en rouge plutôt que de masquer l'incohérence.
      if (result.failed > 0) {
        messenger.showSnackBar(
          SnackBar(
            content: Text(
              t.vaultConvertPartialFail(
                result.failed,
                result.encrypted + result.failed,
              ),
            ),
            backgroundColor: Theme.of(context).colorScheme.error,
            behavior: SnackBarBehavior.floating,
            duration: const Duration(seconds: 8),
          ),
        );
      } else {
        messenger.showSnackBar(
          SnackBar(
            content: Text(
              result.encrypted == 0
                  ? t.vaultConvertSuccess
                  : t.vaultConvertSuccessWithCount(result.encrypted),
            ),
            behavior: SnackBarBehavior.floating,
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      navigator.pop(); // ferme le dialog progress
      messenger.showSnackBar(
        SnackBar(
          content: Text(t.vaultConvertImpossible(e.toString())),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }
}

enum _FolderAction { rename, delete, convertToVault, lockNow }

class _DrawerTile extends StatelessWidget {
  const _DrawerTile({
    required this.icon,
    required this.title,
    required this.selected,
    required this.onTap,
    this.onLongPress,
    this.iconTint,
    this.trailing,
  });

  final IconData icon;
  final String title;
  final bool selected;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;

  /// Couleur explicite pour l'icône — précède la couleur "selected" du
  /// dossier coffre (cadenas rouge même quand le dossier n'est pas
  /// l'actif courant).
  final Color? iconTint;

  /// Widget optionnel à droite (typiquement l'IconButton des 3 points
  /// pour les dossiers utilisateur).
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final color = iconTint ?? (selected ? cs.primary : null);
    return ListTile(
      leading: Icon(icon, color: color),
      title: Text(
        title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: selected ? cs.primary : null,
          fontWeight: selected ? FontWeight.w600 : null,
        ),
      ),
      selected: selected,
      selectedTileColor: cs.primaryContainer.withValues(alpha: 0.4),
      onTap: onTap,
      onLongPress: onLongPress,
      trailing: trailing,
    );
  }
}

/// Dialog modal pendant la conversion d'un dossier en coffre. PopScope
/// bloque le retour : interrompre le re-chiffrement laisserait certaines
/// notes chiffrées et d'autres en clair (incohérent).
class _VaultConvertProgressDialog extends StatelessWidget {
  const _VaultConvertProgressDialog();

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return PopScope(
      canPop: false,
      child: AlertDialog(
        content: Semantics(
          liveRegion: true,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const CircularProgressIndicator(),
              const SizedBox(height: 16),
              Text(
                t.folderConvertProgressTitle,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 6),
              Text(
                t.folderConvertProgressBody,
                style: const TextStyle(fontSize: 12),
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
