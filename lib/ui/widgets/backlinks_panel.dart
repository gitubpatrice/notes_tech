/// Panneau collapsible affichant les liens d'une note :
///  - "Liens" : notes citées par celle-ci (résolus + fantômes).
///  - "Mentions" : notes qui citent celle-ci.
///
/// Auto-rafraîchi via `LinksRepository.changes`.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../data/models/note.dart';
import '../../data/models/note_link.dart';
import '../../data/repositories/links_repository.dart';
import '../../l10n/app_localizations.dart';
import '../../services/backlinks_service.dart';

class BacklinksPanel extends StatefulWidget {
  const BacklinksPanel({
    super.key,
    required this.note,
    required this.onOpenNoteId,
    required this.onTapDangling,
  });

  /// Note dont on affiche les liens. Doit être "fraîche" (id stable).
  final Note note;

  /// Callback : ouvrir la note d'id donné (résolu).
  final ValueChanged<String> onOpenNoteId;

  /// Callback : lien fantôme tapé. Le parent peut proposer "créer" la note.
  final ValueChanged<NoteLink> onTapDangling;

  @override
  State<BacklinksPanel> createState() => _BacklinksPanelState();
}

class _BacklinksPanelState extends State<BacklinksPanel> {
  late final BacklinksService _service;
  late final LinksRepository _links;
  StreamSubscription<void>? _sub;
  Future<_PanelData>? _future;

  @override
  void initState() {
    super.initState();
    _service = context.read<BacklinksService>();
    _links = context.read<LinksRepository>();
    _reload();
    _sub = _links.changes.listen((_) {
      if (mounted) _reload();
    });
  }

  @override
  void didUpdateWidget(covariant BacklinksPanel old) {
    super.didUpdateWidget(old);
    if (old.note.id != widget.note.id || old.note.title != widget.note.title) {
      _reload();
    }
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  void _reload() {
    setState(() => _future = _load());
  }

  Future<_PanelData> _load() async {
    final outgoing = await _service.outgoingLinks(widget.note.id);
    final mentions = await _service.backlinks(widget.note);
    return _PanelData(outgoing: outgoing, mentions: mentions);
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return FutureBuilder<_PanelData>(
      future: _future,
      builder: (context, snap) {
        if (!snap.hasData) return const SizedBox.shrink();
        final data = snap.data!;
        if (data.outgoing.isEmpty && data.mentions.isEmpty) {
          return const SizedBox.shrink();
        }
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (data.outgoing.isNotEmpty)
                _LinkSection(
                  icon: Icons.north_east,
                  label: t.noteEditorOutgoingLinks(data.outgoing.length),
                  children: [
                    for (final l in data.outgoing)
                      _OutgoingChip(link: l, onTap: () => _onTapOutgoing(l)),
                  ],
                ),
              if (data.mentions.isNotEmpty)
                _LinkSection(
                  icon: Icons.south_west,
                  // Reuse existing key for backlinks header.
                  label: '${t.noteEditorBacklinks} (${data.mentions.length})',
                  children: [
                    for (final n in data.mentions)
                      ActionChip(
                        avatar: const ExcludeSemantics(
                          child: Icon(Icons.description_outlined, size: 16),
                        ),
                        label: Text(
                          n.title.isEmpty ? t.noteUntitled : n.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        onPressed: () => widget.onOpenNoteId(n.id),
                      ),
                  ],
                ),
            ],
          ),
        );
      },
    );
  }

  void _onTapOutgoing(NoteLink l) {
    if (l.isResolved) {
      widget.onOpenNoteId(l.targetId!);
    } else {
      widget.onTapDangling(l);
    }
  }
}

class _PanelData {
  const _PanelData({required this.outgoing, required this.mentions});
  final List<NoteLink> outgoing;
  final List<Note> mentions;
}

class _LinkSection extends StatelessWidget {
  const _LinkSection({
    required this.icon,
    required this.label,
    required this.children,
  });

  final IconData icon;
  final String label;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 14, color: theme.iconTheme.color),
              const SizedBox(width: 6),
              Text(label, style: theme.textTheme.labelMedium),
            ],
          ),
          const SizedBox(height: 6),
          Wrap(spacing: 6, runSpacing: 6, children: children),
        ],
      ),
    );
  }
}

class _OutgoingChip extends StatelessWidget {
  const _OutgoingChip({required this.link, required this.onTap});
  final NoteLink link;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final dangling = !link.isResolved;
    return ActionChip(
      avatar: ExcludeSemantics(
        child: Icon(
          dangling ? Icons.add_link : Icons.description_outlined,
          size: 16,
        ),
      ),
      label: Text(
        link.targetTitle,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: dangling ? TextStyle(color: Theme.of(context).hintColor) : null,
      ),
      onPressed: onTap,
      tooltip: dangling ? t.noteEditorBacklinkDangling(link.targetTitle) : null,
    );
  }
}
