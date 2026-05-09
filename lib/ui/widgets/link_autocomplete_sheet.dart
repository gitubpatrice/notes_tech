/// Bottom sheet d'auto-complétion d'un lien `[[Titre]]`.
///
/// Ouvert depuis l'éditeur quand l'utilisateur tape `[[` ; il continue
/// à taper la requête, on filtre les notes existantes par titre.
///
/// Garanties UX :
///  - Pas de cancel implicite si l'utilisateur tape rapidement.
///  - Touche "Créer …" toujours disponible si la requête n'est pas vide.
///  - Clavier ouvert au focus auto.
library;

import 'package:flutter/material.dart';

import '../../data/models/note.dart';
import '../../l10n/app_localizations.dart';
import '../../services/backlinks_service.dart';
import '../../utils/debouncer.dart';

class LinkAutocompleteResult {
  const LinkAutocompleteResult.existing(this.title) : isCreate = false;
  const LinkAutocompleteResult.create(this.title) : isCreate = true;
  final String title;
  final bool isCreate;
}

/// Présente le sheet et retourne le titre choisi (existant ou nouveau),
/// ou `null` si l'utilisateur annule (back / dismiss).
Future<LinkAutocompleteResult?> showLinkAutocompleteSheet({
  required BuildContext context,
  required BacklinksService service,
  required String? excludeNoteId,
  String initialQuery = '',
}) {
  return showModalBottomSheet<LinkAutocompleteResult>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (ctx) => _LinkAutocompleteSheet(
      service: service,
      excludeNoteId: excludeNoteId,
      initialQuery: initialQuery,
    ),
  );
}

class _LinkAutocompleteSheet extends StatefulWidget {
  const _LinkAutocompleteSheet({
    required this.service,
    required this.excludeNoteId,
    required this.initialQuery,
  });

  final BacklinksService service;
  final String? excludeNoteId;
  final String initialQuery;

  @override
  State<_LinkAutocompleteSheet> createState() => _LinkAutocompleteSheetState();
}

class _LinkAutocompleteSheetState extends State<_LinkAutocompleteSheet> {
  late final TextEditingController _ctrl;
  final _debouncer = Debouncer(const Duration(milliseconds: 120));
  Future<List<Note>>? _future;

  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController(text: widget.initialQuery);
    _ctrl.addListener(_onChanged);
    _runSearch(widget.initialQuery);
  }

  @override
  void dispose() {
    _debouncer.dispose();
    _ctrl.removeListener(_onChanged);
    _ctrl.dispose();
    super.dispose();
  }

  void _onChanged() {
    _debouncer.run(() {
      if (!mounted) return;
      _runSearch(_ctrl.text);
    });
  }

  void _runSearch(String q) {
    setState(() {
      _future = widget.service.suggestTitles(
        q,
        excludeId: widget.excludeNoteId,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final viewInsets = MediaQuery.viewInsetsOf(context);
    final query = _ctrl.text.trim();
    return Padding(
      padding: EdgeInsets.only(bottom: viewInsets.bottom),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Semantics(
                header: true,
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    t.linkAutocompleteTitle,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ),
              TextField(
                controller: _ctrl,
                autofocus: true,
                enableSuggestions: false,
                autocorrect: false,
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => _onSubmit(query),
                decoration: InputDecoration(
                  labelText: t.linkAutocompleteHint,
                  hintText: t.linkAutocompleteHint,
                  prefixIcon: const Icon(Icons.link),
                ),
              ),
              const SizedBox(height: 12),
              ConstrainedBox(
                constraints: BoxConstraints(
                  maxHeight: MediaQuery.sizeOf(context).height * 0.4,
                ),
                child: FutureBuilder<List<Note>>(
                  future: _future,
                  builder: (_, snap) {
                    final notes = snap.data ?? const <Note>[];
                    final showCreate =
                        query.isNotEmpty && _isExactMatchAbsent(notes, query);
                    if (notes.isEmpty && !showCreate) {
                      return Padding(
                        padding: const EdgeInsets.all(16),
                        child: Text(
                          t.linkAutocompleteEmpty,
                          textAlign: TextAlign.center,
                        ),
                      );
                    }
                    return ListView(
                      shrinkWrap: true,
                      children: [
                        for (final n in notes)
                          ListTile(
                            leading: const ExcludeSemantics(
                              child: Icon(Icons.description_outlined),
                            ),
                            title: Text(
                              n.title.isEmpty ? t.noteUntitled : n.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            onTap: () => Navigator.of(
                              context,
                            ).pop(LinkAutocompleteResult.existing(n.title)),
                          ),
                        if (showCreate)
                          ListTile(
                            leading: const ExcludeSemantics(
                              child: Icon(Icons.add),
                            ),
                            title: Text(t.linkAutocompleteCreateNew(query)),
                            onTap: () => Navigator.of(
                              context,
                            ).pop(LinkAutocompleteResult.create(query)),
                          ),
                      ],
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  static bool _isExactMatchAbsent(List<Note> notes, String query) {
    final q = BacklinksService.normalizeTitle(query);
    return !notes.any((n) => BacklinksService.normalizeTitle(n.title) == q);
  }

  void _onSubmit(String query) {
    if (query.isEmpty) {
      Navigator.of(context).pop();
      return;
    }
    Navigator.of(context).pop(LinkAutocompleteResult.create(query));
  }
}
