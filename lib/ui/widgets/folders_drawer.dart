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
import 'folder_dialogs.dart';

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
    final name = await showFolderNameDialog(
      context: context,
      title: 'Nouveau dossier',
      hint: 'Nom du dossier (ex. Reiki, Géobiologie…)',
    );
    if (name == null || !mounted) return;
    final folder = await _repo.create(name: name);
    widget.onSelect(folder.id);
    if (mounted) Navigator.of(context).pop(); // ferme le drawer
  }

  Future<void> _renameFolder(Folder folder) async {
    final name = await showFolderNameDialog(
      context: context,
      title: 'Renommer',
      hint: 'Nouveau nom',
      initial: folder.name,
    );
    if (name == null || name == folder.name) return;
    await _repo.rename(folder, name);
  }

  Future<void> _deleteFolder(Folder folder) async {
    if (folder.id == kInboxFolderId) return; // garde-fou redondant
    final outcome = await confirmDeleteFolder(
      context: context,
      folderName: folder.name,
    );
    if (outcome == null || !mounted) return;

    if (outcome == FolderDeletionChoice.moveToInbox) {
      // Pré-déplacement BATCH (UPDATE atomique unique) — couvre TOUTES
      // les notes du dossier, y compris archivées et en corbeille
      // (sinon le ON DELETE CASCADE qui suit les effacerait
      // définitivement, bypassant la rétention 30 jours).
      await moveAllNotesToInbox(_notes, fromFolderId: folder.id);
    }
    if (!mounted) return;
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
    return Drawer(
      child: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
              child: Row(
                children: [
                  Icon(
                    Icons.folder_copy_outlined,
                    color: theme.colorScheme.primary,
                  ),
                  const SizedBox(width: 12),
                  Text(
                    'Mes dossiers',
                    style: theme.textTheme.titleMedium,
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
                      name: 'Boîte de réception',
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
                        title: 'Toutes les notes',
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
                        const Padding(
                          padding: EdgeInsets.fromLTRB(20, 16, 20, 6),
                          child: Text(
                            'DOSSIERS',
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                              letterSpacing: 1.1,
                            ),
                          ),
                        ),
                        ...userFolders.map(
                          (f) => _DrawerTile(
                            icon: Icons.folder_outlined,
                            title: f.name,
                            selected: widget.currentFolderId == f.id,
                            onTap: () => _select(f.id),
                            onLongPress: () => _showFolderMenu(f),
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
                  icon: const Icon(Icons.create_new_folder_outlined),
                  label: const Text('Nouveau dossier'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _showFolderMenu(Folder folder) async {
    final action = await showModalBottomSheet<_FolderAction>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.drive_file_rename_outline),
              title: const Text('Renommer'),
              onTap: () => Navigator.of(ctx).pop(_FolderAction.rename),
            ),
            ListTile(
              leading: Icon(
                Icons.delete_outline,
                color: Theme.of(ctx).colorScheme.error,
              ),
              title: Text(
                'Supprimer',
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
  }
}

enum _FolderAction { rename, delete }

class _DrawerTile extends StatelessWidget {
  const _DrawerTile({
    required this.icon,
    required this.title,
    required this.selected,
    required this.onTap,
    this.onLongPress,
  });

  final IconData icon;
  final String title;
  final bool selected;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ListTile(
      leading: Icon(icon, color: selected ? cs.primary : null),
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
    );
  }
}
