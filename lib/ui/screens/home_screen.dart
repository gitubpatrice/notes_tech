/// Écran d'accueil : recherche + liste des notes.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/note.dart';
import '../../data/repositories/notes_repository.dart';
import '../../services/settings_service.dart';
import '../../utils/debouncer.dart';
import '../widgets/empty_state.dart';
import '../widgets/indexing_banner.dart';
import '../widgets/note_card.dart';
import 'ai_chat_screen.dart';
import 'note_editor_screen.dart';
import 'search_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final NotesRepository _notes;
  late final SettingsService _settings;
  late final Debouncer _searchDebouncer;
  late final StreamSubscription<void> _changesSub;
  final _searchCtrl = TextEditingController();

  Future<List<Note>>? _future;
  String _query = '';
  NoteSortMode _activeSort = NoteSortMode.updatedDesc;

  @override
  void initState() {
    super.initState();
    _notes = context.read<NotesRepository>();
    _settings = context.read<SettingsService>();
    _activeSort = _settings.sortMode;
    _settings.addListener(_onSettingsChanged);
    _searchDebouncer = Debouncer(AppConstants.searchDebounce);
    _changesSub = _notes.changes.listen((_) {
      if (mounted) _reload();
    });
    _reload();
    // Purge corbeille en arrière-plan, fire-and-forget.
    unawaited(_notes.purgeOldTrash());
  }

  @override
  void dispose() {
    _settings.removeListener(_onSettingsChanged);
    _changesSub.cancel();
    _searchDebouncer.dispose();
    _searchCtrl.dispose();
    super.dispose();
  }

  void _onSettingsChanged() {
    if (!mounted) return;
    if (_settings.sortMode != _activeSort) {
      _activeSort = _settings.sortMode;
      _reload();
    }
  }

  void _reload() {
    setState(() {
      _future = _query.isEmpty
          ? _notes.listByFolder('inbox', sort: _activeSort)
          : _notes.search(_query);
    });
  }

  void _onSearchChanged(String value) {
    _searchDebouncer.run(() {
      if (!mounted) return;
      setState(() => _query = value.trim());
      _reload();
    });
  }

  Future<void> _openNew() async {
    final navigator = Navigator.of(context);
    final created = await _notes.create(folderId: 'inbox');
    if (!mounted) return;
    await navigator.push(
      MaterialPageRoute<void>(builder: (_) => NoteEditorScreen(noteId: created.id)),
    );
  }

  Future<void> _open(Note note) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => NoteEditorScreen(noteId: note.id)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text(AppConstants.appName),
        actions: [
          IconButton(
            tooltip: 'Demander à mes notes',
            icon: const Icon(Icons.psychology_outlined),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const AiChatScreen()),
            ),
          ),
          IconButton(
            tooltip: 'Recherche avancée',
            icon: const Icon(Icons.travel_explore),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const SearchScreen()),
            ),
          ),
          IconButton(
            tooltip: 'Réglages',
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const SettingsScreen()),
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _openNew,
        icon: const Icon(Icons.edit_outlined),
        label: const Text('Nouvelle note'),
      ),
      body: SafeArea(
        child: Column(
          children: [
            const IndexingBanner(),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
              child: TextField(
                controller: _searchCtrl,
                onChanged: _onSearchChanged,
                textInputAction: TextInputAction.search,
                enableSuggestions: false,
                autocorrect: false,
                decoration: InputDecoration(
                  prefixIcon: const Icon(Icons.search),
                  hintText: 'Rechercher dans toutes les notes',
                  suffixIcon: _searchCtrl.text.isEmpty
                      ? null
                      : IconButton(
                          icon: const Icon(Icons.close),
                          onPressed: () {
                            _searchCtrl.clear();
                            _onSearchChanged('');
                          },
                        ),
                ),
              ),
            ),
            Expanded(
              child: FutureBuilder<List<Note>>(
                future: _future,
                builder: (context, snap) {
                  if (snap.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snap.hasError) {
                    return Center(
                      child: Padding(
                        padding: const EdgeInsets.all(24),
                        child: Text(
                          'Une erreur est survenue lors du chargement.',
                          style: theme.textTheme.bodySmall,
                          textAlign: TextAlign.center,
                        ),
                      ),
                    );
                  }
                  final notes = snap.data ?? const [];
                  if (notes.isEmpty) {
                    return EmptyState(
                      icon: _query.isEmpty
                          ? Icons.note_alt_outlined
                          : Icons.search_off,
                      title: _query.isEmpty
                          ? 'Aucune note pour le moment'
                          : 'Aucun résultat',
                      subtitle: _query.isEmpty
                          ? 'Touchez « Nouvelle note » pour démarrer.'
                          : 'Essayez d\'autres mots-clés.',
                    );
                  }
                  return ListView.separated(
                    padding: const EdgeInsets.fromLTRB(12, 4, 12, 96),
                    itemCount: notes.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 8),
                    itemBuilder: (_, i) {
                      final n = notes[i];
                      return NoteCard(note: n, onTap: () => _open(n));
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
