/// Bottom sheet « Déplacer vers… » : affiche les dossiers disponibles
/// avec mise en évidence du dossier courant, retourne l'`id` du dossier
/// sélectionné (ou `null` si annulé / déjà dans le dossier).
///
/// Utilisé depuis le menu de l'éditeur de note. Le caller fait
/// l'`UPDATE` via `NotesRepository.save(note.copyWith(folderId:...))`.
library;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/folder.dart';
import '../../data/repositories/folders_repository.dart';
import '../../l10n/app_localizations.dart';

/// Présente la liste des dossiers et retourne l'id sélectionné.
/// Inclut systématiquement « Boîte de réception » même si elle n'apparaît
/// pas dans le retour DAO (filet de sécurité).
Future<String?> showMoveToFolderSheet({
  required BuildContext context,
  required String currentFolderId,
}) async {
  final repo = context.read<FoldersRepository>();
  final folders = await repo.listAll();
  if (!context.mounted) return null;

  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    builder: (ctx) {
      return _MoveSheet(folders: folders, currentFolderId: currentFolderId);
    },
  );
}

class _MoveSheet extends StatelessWidget {
  const _MoveSheet({required this.folders, required this.currentFolderId});

  final List<Folder> folders;
  final String currentFolderId;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    // Ordre : Inbox d'abord (toujours présente), puis dossiers utilisateur.
    final inbox = folders.firstWhere(
      (f) => f.id == AppConstants.inboxFolderId,
      orElse: () => Folder(
        id: AppConstants.inboxFolderId,
        name: t.homeFolderInbox,
        createdAt: DateTime.fromMillisecondsSinceEpoch(0),
        updatedAt: DateTime.fromMillisecondsSinceEpoch(0),
      ),
    );
    final others = folders
        .where((f) => f.id != AppConstants.inboxFolderId)
        .toList();

    return SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
            child: Row(
              children: [
                ExcludeSemantics(
                  child: Icon(Icons.drive_file_move_outline, color: cs.primary),
                ),
                const SizedBox(width: 12),
                Semantics(
                  header: true,
                  child: Text(
                    t.moveToFolderTitle,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Flexible(
            child: folders.isEmpty
                ? Padding(
                    padding: const EdgeInsets.all(24),
                    child: Text(
                      t.moveToFolderEmpty,
                      textAlign: TextAlign.center,
                    ),
                  )
                : ListView(
                    shrinkWrap: true,
                    children: [
                      _FolderTile(
                        icon: Icons.inbox_outlined,
                        name: inbox.name,
                        selected: currentFolderId == inbox.id,
                        onTap: () => Navigator.of(context).pop(inbox.id),
                      ),
                      if (others.isNotEmpty) const Divider(height: 1),
                      ...others.map(
                        (f) => _FolderTile(
                          icon: Icons.folder_outlined,
                          name: f.name,
                          selected: currentFolderId == f.id,
                          onTap: () => Navigator.of(context).pop(f.id),
                        ),
                      ),
                    ],
                  ),
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }
}

class _FolderTile extends StatelessWidget {
  const _FolderTile({
    required this.icon,
    required this.name,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String name;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ListTile(
      leading: Icon(icon, color: selected ? cs.primary : null),
      title: Text(
        name,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: selected ? cs.primary : null,
          fontWeight: selected ? FontWeight.w600 : null,
        ),
      ),
      trailing: selected
          ? Icon(Icons.check, color: cs.primary, size: 20)
          : null,
      onTap: onTap,
    );
  }
}
