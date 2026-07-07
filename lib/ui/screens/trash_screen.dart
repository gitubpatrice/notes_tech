/// Écran Corbeille : liste des notes supprimées, restauration et suppression
/// définitive. Comble le trou de câblage où `trash()` / `restoreFromTrash()`
/// existaient sans aucune UI (note supprimée = irrécupérable jusqu'à la purge
/// automatique après [AppConstants.trashRetentionDays] jours).
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/note.dart';
import '../../data/repositories/notes_repository.dart';
import '../../l10n/app_localizations.dart';
import '../../utils/snackbar_ext.dart';
import '../widgets/empty_state.dart';

class TrashScreen extends StatefulWidget {
  const TrashScreen({super.key});

  @override
  State<TrashScreen> createState() => _TrashScreenState();
}

class _TrashScreenState extends State<TrashScreen> {
  late final NotesRepository _notes;
  List<Note>? _items;

  @override
  void initState() {
    super.initState();
    _notes = context.read<NotesRepository>();
    _reload();
  }

  Future<void> _reload() async {
    final items = await _notes.trash();
    if (!mounted) return;
    setState(() => _items = items);
  }

  Future<void> _restore(Note note) async {
    final messenger = ScaffoldMessenger.of(context);
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    try {
      await _notes.restoreFromTrash(note);
      await _reload();
      messenger.showSuccessSnack(t.trashRestored, cs);
    } catch (e) {
      messenger.showErrorSnack(t.commonErrorWith('$e'), cs);
    }
  }

  Future<void> _deleteForever(Note note) async {
    final messenger = ScaffoldMessenger.of(context);
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final confirmed = await _confirm(
      title: t.trashDeleteForeverTitle,
      body: t.trashDeleteForeverBody,
      confirmLabel: t.trashDeleteForever,
    );
    if (confirmed != true) return;
    try {
      await _notes.deletePermanently(note.id);
      await _reload();
      messenger.showSuccessSnack(t.trashDeletedForever, cs);
    } catch (e) {
      messenger.showErrorSnack(t.commonErrorWith('$e'), cs);
    }
  }

  Future<void> _emptyAll() async {
    final messenger = ScaffoldMessenger.of(context);
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final items = _items;
    if (items == null || items.isEmpty) return;
    final confirmed = await _confirm(
      title: t.trashEmptyAll,
      body: t.trashEmptyAllConfirm,
      confirmLabel: t.trashEmptyAll,
    );
    if (confirmed != true) return;
    try {
      for (final n in items) {
        await _notes.deletePermanently(n.id);
      }
      await _reload();
      messenger.showSuccessSnack(t.trashDeletedForever, cs);
    } catch (e) {
      messenger.showErrorSnack(t.commonErrorWith('$e'), cs);
    }
  }

  Future<bool?> _confirm({
    required String title,
    required String body,
    required String confirmLabel,
  }) {
    final t = AppLocalizations.of(context);
    final cs = Theme.of(context).colorScheme;
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(
            autofocus: true,
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(t.commonCancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: cs.errorContainer,
              foregroundColor: cs.onErrorContainer,
            ),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(confirmLabel),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final items = _items;
    return Scaffold(
      appBar: AppBar(
        title: Text(t.trashTitle),
        actions: [
          if (items != null && items.isNotEmpty)
            IconButton(
              tooltip: t.trashEmptyAll,
              icon: const Icon(Icons.delete_sweep_outlined),
              onPressed: _emptyAll,
            ),
        ],
      ),
      body: SafeArea(
        child: items == null
            ? const Center(child: CircularProgressIndicator())
            : items.isEmpty
            ? EmptyState(
                icon: Icons.delete_outline,
                title: t.trashEmptyTitle,
                subtitle: t.trashEmptySubtitle,
              )
            : Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
                    child: Row(
                      children: [
                        Icon(
                          Icons.info_outline,
                          size: 16,
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            t.trashRetentionNotice(
                              AppConstants.trashRetentionDays,
                            ),
                            style: TextStyle(
                              fontSize: 12,
                              color: Theme.of(
                                context,
                              ).colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const Divider(height: 1),
                  Expanded(
                    child: ListView.separated(
                      padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
                      itemCount: items.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 4),
                      itemBuilder: (_, i) => _TrashTile(
                        note: items[i],
                        onRestore: () => _restore(items[i]),
                        onDeleteForever: () => _deleteForever(items[i]),
                      ),
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

class _TrashTile extends StatelessWidget {
  final Note note;
  final VoidCallback onRestore;
  final VoidCallback onDeleteForever;

  const _TrashTile({
    required this.note,
    required this.onRestore,
    required this.onDeleteForever,
  });

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final cs = Theme.of(context).colorScheme;
    final locale = Localizations.localeOf(context).toString();
    final title = note.isLocked
        ? t.noteCardLocked
        : (note.title.isEmpty ? t.noteUntitled : note.title);
    final trashedAt = note.trashedAt;
    final subtitle = trashedAt == null
        ? null
        : DateFormat.yMMMd(locale).add_Hm().format(trashedAt);

    return Card(
      margin: EdgeInsets.zero,
      child: ListTile(
        leading: Icon(
          note.isLocked ? Icons.lock_outline : Icons.description_outlined,
          color: note.isLocked ? cs.error : cs.onSurfaceVariant,
        ),
        title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: subtitle == null ? null : Text(subtitle),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              tooltip: t.commonRestore,
              icon: const Icon(Icons.restore_from_trash_outlined),
              onPressed: onRestore,
            ),
            IconButton(
              tooltip: t.trashDeleteForever,
              icon: Icon(Icons.delete_forever_outlined, color: cs.error),
              onPressed: onDeleteForever,
            ),
          ],
        ),
      ),
    );
  }
}
