/// Écran d'accueil : recherche + liste des notes.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/note.dart';
import '../../data/repositories/folders_repository.dart';
import '../../data/repositories/notes_repository.dart';
import '../../l10n/app_localizations.dart';
import '../../services/security/folder_vault_service.dart';
import '../../services/settings_service.dart';
import '../../utils/debouncer.dart';
import '../widgets/empty_state.dart';
import '../widgets/folders_drawer.dart';
import '../widgets/indexing_banner.dart';
import '../widgets/note_card.dart';
import '../widgets/vault_pin_sheets.dart';
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
  late final FoldersRepository _folders;
  late final SettingsService _settings;
  late final Debouncer _searchDebouncer;
  late final StreamSubscription<void> _changesSub;
  late final StreamSubscription<void> _foldersSub;
  final _searchCtrl = TextEditingController();

  Future<List<Note>>? _future;
  String _query = '';
  NoteSortMode _activeSort = NoteSortMode.updatedDesc;

  /// `null` = filtre « Toutes les notes ». Sinon ID du dossier actif.
  String? _currentFolderId;

  /// Cache du nom du dossier actif pour l'AppBar (évite un FutureBuilder
  /// dans le titre). Mis à jour par [_refreshCurrentFolderName].
  String? _currentFolderName;

  /// Cache id→name de TOUS les dossiers, utilisé pour afficher la puce
  /// "dossier" sur chaque NoteCard en mode "Toutes les notes". Rechargé
  /// à chaque event de `FoldersRepository.changes`. Vide en mode filtré
  /// (le dossier est déjà connu via le titre AppBar — surcharge inutile).
  Map<String, String> _folderNamesById = const {};

  @override
  void initState() {
    super.initState();
    _notes = context.read<NotesRepository>();
    _folders = context.read<FoldersRepository>();
    _settings = context.read<SettingsService>();
    _activeSort = _settings.sortMode;
    _settings.addListener(_onSettingsChanged);
    _searchDebouncer = Debouncer(AppConstants.searchDebounce);
    _changesSub = _notes.changes.listen((_) {
      if (mounted) _reload();
    });
    _foldersSub = _folders.changes.listen((_) {
      if (!mounted) return;
      _refreshCurrentFolderName();
      _refreshFolderNamesCache();
    });
    _refreshFolderNamesCache();
    _reload();
    // Purge corbeille en arrière-plan, fire-and-forget.
    unawaited(_notes.purgeOldTrash());
  }

  @override
  void dispose() {
    _settings.removeListener(_onSettingsChanged);
    _changesSub.cancel();
    _foldersSub.cancel();
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
      // Recherche globale : ignore le filtre dossier (l'utilisateur cherche
      // dans toutes ses notes, peu importe leur classement).
      if (_query.isNotEmpty) {
        _future = _notes.search(_query);
        return;
      }
      // Pas de filtre dossier → toutes les notes vivantes.
      // Filtre dossier actif → notes du dossier seulement.
      _future = _currentFolderId == null
          ? _notes.listAllAlive()
          : _notes.listByFolder(_currentFolderId!, sort: _activeSort);
    });
  }

  void _onSearchChanged(String value) {
    _searchDebouncer.run(() {
      if (!mounted) return;
      setState(() => _query = value.trim());
      _reload();
    });
  }

  Future<void> _onFolderSelected(String? folderId) async {
    if (folderId == _currentFolderId) return;
    setState(() => _currentFolderId = folderId);
    _reload();
    await _refreshCurrentFolderName();
  }

  /// Recharge le cache id→name pour pouvoir étiqueter les NoteCard avec
  /// leur dossier en mode "Toutes les notes". Appelé au démarrage et à
  /// chaque mutation de FoldersRepository (création, renommage,
  /// suppression). Coût négligeable (≤ 50 dossiers réalistes).
  Future<void> _refreshFolderNamesCache() async {
    final folders = await _folders.listAll();
    if (!mounted) return;
    final next = {for (final f in folders) f.id: f.name};
    // Skip rebuild si la map est identique au précédent snapshot —
    // évite des setState gratuits sur chaque event `folders.changes`
    // (réordonnancement par updated_at sans renommage notamment).
    if (_folderNamesById.length == next.length &&
        next.entries.every((e) => _folderNamesById[e.key] == e.value)) {
      return;
    }
    setState(() => _folderNamesById = next);
  }

  Future<void> _refreshCurrentFolderName() async {
    if (_currentFolderId == null) {
      if (_currentFolderName == null) return;
      setState(() => _currentFolderName = null);
      return;
    }
    final folder = await _folders.get(_currentFolderId!);
    if (!mounted) return;
    if (folder == null) {
      // Dossier supprimé hors de la session courante : retombe sur "Toutes".
      setState(() {
        _currentFolderId = null;
        _currentFolderName = null;
      });
      _reload();
      return;
    }
    if (folder.name != _currentFolderName) {
      setState(() => _currentFolderName = folder.name);
    }
  }

  Future<void> _openNew() async {
    final navigator = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final t = AppLocalizations.of(context);
    final vault = context.read<FolderVaultService>();
    // Note créée dans le dossier actif. Si l'utilisateur est sur « Toutes
    // les notes », on retombe sur l'inbox (dossier par défaut, indélébile)
    // et on signale explicitement où est partie la note pour ne pas
    // qu'elle disparaisse du champ de vision après le retour de l'éditeur.
    final isAllScope = _currentFolderId == null;
    final targetFolderId = _currentFolderId ?? kInboxFolderId;

    // Si le dossier cible est un coffre, exiger la session unlock avant
    // de matérialiser la note — sinon on créerait une note "vide en
    // clair" dans le coffre, incohérente avec l'invariant « toujours
    // chiffrée au repos pour les dossiers coffre ».
    final targetFolder = await _folders.get(targetFolderId);
    if (!mounted) return;
    if (targetFolder != null && targetFolder.isVault) {
      if (!vault.isUnlocked(targetFolderId)) {
        final ok = await showUnlockVaultAdaptive(
          context: context,
          folder: targetFolder,
        );
        if (ok != true || !mounted) return;
      }
    }

    final created = await _notes.create(folderId: targetFolderId);
    // Coffre déverrouillé : on chiffre immédiatement la note neuve
    // (titre/contenu vides, mais l'IV+verifier sont posés). L'éditeur
    // détectera `isLocked` et passera en mode déchiffrement éphémère.
    if (targetFolder != null && targetFolder.isVault) {
      try {
        final encrypted = await vault.encryptNote(created);
        await _notes.save(encrypted);
      } catch (e) {
        if (!mounted) return;
        messenger.showSnackBar(
          SnackBar(
            content: Text(t.homeVaultCreateError(e.toString())),
            behavior: SnackBarBehavior.floating,
          ),
        );
        return;
      }
    }
    if (!mounted) return;
    await navigator.push(
      MaterialPageRoute<void>(
        builder: (_) => NoteEditorScreen(noteId: created.id),
      ),
    );
    if (!mounted) return;
    if (isAllScope) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(t.homeNoteCreatedInInbox),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 2),
        ),
      );
    }
  }

  Future<void> _open(Note note) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => NoteEditorScreen(noteId: note.id)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final t = AppLocalizations.of(context);
    return Scaffold(
      drawer: FoldersDrawer(
        currentFolderId: _currentFolderId,
        onSelect: _onFolderSelected,
      ),
      appBar: AppBar(
        // Titre dynamique : nom du dossier actif si filtré, sinon nom de
        // l'app. Plus efficace qu'un FutureBuilder dans le titre — le
        // nom est mis en cache via `_refreshCurrentFolderName`.
        title: Semantics(
          header: true,
          child: Text(_currentFolderName ?? AppConstants.appName),
        ),
        actions: [
          IconButton(
            tooltip: t.homeAskAi,
            icon: const Icon(Icons.psychology_outlined),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const AiChatScreen()),
            ),
          ),
          IconButton(
            tooltip: t.searchTitle,
            icon: const Icon(Icons.travel_explore),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const SearchScreen()),
            ),
          ),
          IconButton(
            tooltip: t.settingsTitle,
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
        label: Text(t.homeNewNote),
        tooltip: t.homeNewNote,
      ),
      body: SafeArea(
        child: Column(
          children: [
            const IndexingBanner(),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
              child: Semantics(
                label: t.homeSearchHint,
                textField: true,
                child: TextField(
                  controller: _searchCtrl,
                  onChanged: _onSearchChanged,
                  textInputAction: TextInputAction.search,
                  enableSuggestions: false,
                  autocorrect: false,
                  decoration: InputDecoration(
                    // labelText persistant pour qu'un lecteur d'écran
                    // énonce toujours le rôle du champ, même rempli.
                    labelText: t.homeSearchHint,
                    prefixIcon: const ExcludeSemantics(
                      child: Icon(Icons.search),
                    ),
                    suffixIcon: _searchCtrl.text.isEmpty
                        ? null
                        : IconButton(
                            tooltip: t.searchClear,
                            icon: const Icon(Icons.close),
                            onPressed: () {
                              _searchCtrl.clear();
                              _onSearchChanged('');
                            },
                          ),
                  ),
                ),
              ),
            ),
            Expanded(
              child: FutureBuilder<List<Note>>(
                future: _future,
                builder: (context, snap) {
                  if (snap.connectionState == ConnectionState.waiting) {
                    return Center(
                      child: Semantics(
                        label: t.commonLoading,
                        child: const CircularProgressIndicator(),
                      ),
                    );
                  }
                  if (snap.hasError) {
                    return Center(
                      child: Padding(
                        padding: const EdgeInsets.all(24),
                        child: Text(
                          t.homeLoadError,
                          style: theme.textTheme.bodySmall,
                          textAlign: TextAlign.center,
                        ),
                      ),
                    );
                  }
                  final notes = snap.data ?? const [];
                  if (notes.isEmpty) {
                    final inFolder = _currentFolderId != null;
                    return EmptyState(
                      icon: _query.isEmpty
                          ? Icons.note_alt_outlined
                          : Icons.search_off,
                      title: _query.isEmpty
                          ? (inFolder ? t.homeNoNotesIn : t.homeNoNotes)
                          : t.searchEmpty,
                      subtitle: _query.isEmpty
                          ? t.homeStartWriting
                          : t.searchTryOther,
                    );
                  }
                  // En mode "Toutes les notes" (et en recherche globale),
                  // on affiche le badge dossier sur chaque card pour que
                  // l'utilisateur sache où chaque note range. En mode
                  // filtré, le badge est superflu (toutes les notes sont
                  // dans le dossier déjà visible dans l'AppBar).
                  final showFolderBadge =
                      _currentFolderId == null || _query.isNotEmpty;
                  return ListView.separated(
                    padding: const EdgeInsets.fromLTRB(12, 4, 12, 96),
                    itemCount: notes.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 8),
                    itemBuilder: (_, i) {
                      final n = notes[i];
                      // MergeSemantics groupe titre + date + tags + badge
                      // dossier en un seul nœud accessible — un swipe
                      // TalkBack lit la carte d'un coup au lieu d'égrener.
                      return MergeSemantics(
                        child: NoteCard(
                          note: n,
                          onTap: () => _open(n),
                          folderName: showFolderBadge
                              ? _folderNamesById[n.folderId]
                              : null,
                        ),
                      );
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
