/// Écran "Demander à mes notes" — Q&A on-device avec Gemma 3 1B.
///
/// États :
///   - Modèle non installé → bouton d'import via SAF
///   - Modèle en import → barre de progression
///   - Modèle en warmUp → spinner
///   - Modèle prêt → input + flux de réponse
///
/// Performance :
///   - Streaming token : `ValueNotifier<String>` par tour, seule la bulle
///     en cours rebuild (pas la ListView entière).
///   - AutoScroll : `jumpTo` léger pendant streaming, `animateTo` à `onDone`.
///   - Bouton "envoyer" réactif au texte via `addListener`.
///
/// Aucune permission INTERNET, aucun envoi réseau.
library;

import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:provider/provider.dart';

import '../../core/a11y.dart';
import '../../data/models/note.dart';
import '../../l10n/app_localizations.dart';
import '../../services/ai/gemma_service.dart';
import '../../services/ai/rag_service.dart';
import '../../services/ml/ml_memory_guard.dart';
import '../../services/secure_window_service.dart';
import '../../services/semantic_search_service.dart';
import '../../services/settings_service.dart';
import '../widgets/empty_state.dart';
import 'note_editor_screen.dart';
import 'settings_screen.dart';

class AiChatScreen extends StatefulWidget {
  const AiChatScreen({super.key});

  @override
  State<AiChatScreen> createState() => _AiChatScreenState();
}

class _AiChatScreenState extends State<AiChatScreen>
    with WidgetsBindingObserver, SecureWindowGuardMixin {
  late final GemmaService _gemma;
  late final RagService _rag;
  final _inputCtrl = TextEditingController();
  final _scrollCtrl = ScrollController();
  final _canSend = ValueNotifier<bool>(false);

  // États
  _Phase _phase = _Phase.checking;
  String? _phaseError;
  ({int copied, int total})? _importProgress;

  // Conversation (volontairement éphémère — pas de persistance)
  final List<_ChatTurn> _turns = <_ChatTurn>[];
  StreamSubscription<String>? _genSub;
  bool _generating = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _gemma = context.read<GemmaService>();
    _rag = RagService(search: context.read<SemanticSearchService>());
    _inputCtrl.addListener(_updateCanSend);
    _initialize();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _inputCtrl.removeListener(_updateCanSend);
    _genSub?.cancel();
    // Si une génération tournait, on la coupe côté natif aussi.
    if (_generating) _gemma.stopGeneration();
    _inputCtrl.dispose();
    _scrollCtrl.dispose();
    _canSend.dispose();
    // v1.0.7 qual H1 — libère les notifiers de chaque tour avant de
    // quitter l'écran. Le `_turns.clear` implicite ne dispose pas les
    // notifiers Dart (GC du conteneur ≠ dispose Listenable).
    _disposeAllTurns();
    super.dispose();
  }

  /// Dispose tous les notifiers des tours puis vide la liste. Centralisé
  /// pour garantir le pattern partout où l'on clear `_turns` (lifecycle
  /// paused/hidden/detached, clear conversation, dispose final).
  void _disposeAllTurns() {
    for (final t in _turns) {
      t.dispose();
    }
    _turns.clear();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // A13 v1.0.4 — clear `_turns` au lifecycle paused/hidden/detached.
    // Les turns contiennent les réponses Gemma + les `sources` (notes
    // pertinentes), dont des notes potentiellement vault déchiffrées
    // résident encore en RAM bien après que le coffre soit re-locké
    // par lifecycle. Sans clear, RAM forensics post-paused récupère le
    // plaintext même après auto-lock.
    // B14 v1.0.4 — stop génération en cours sur paused (économie RAM
    // + batterie + sécurité : la suite du stream peut contenir le
    // contenu vault).
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden ||
        state == AppLifecycleState.detached) {
      if (_generating) {
        _gemma.stopGeneration();
      }
      if (_turns.isNotEmpty && mounted) {
        setState(_disposeAllTurns);
      }
    }
  }

  void _updateCanSend() {
    final v = _inputCtrl.text.trim().isNotEmpty && !_generating;
    if (_canSend.value != v) _canSend.value = v;
  }

  // ---------------------------------------------------------------------
  // Initialisation
  // ---------------------------------------------------------------------

  Future<void> _initialize() async {
    setState(() {
      _phase = _Phase.checking;
      _phaseError = null;
    });
    try {
      final installed = await _gemma.isModelInstalled();
      if (!installed) {
        if (mounted) setState(() => _phase = _Phase.notInstalled);
        return;
      }
      await _warmUp();
    } catch (e) {
      if (mounted) {
        setState(() {
          _phase = _Phase.notInstalled;
          _phaseError = e.toString();
        });
      }
    }
  }

  Future<void> _warmUp() async {
    if (!mounted) return;
    setState(() {
      _phase = _Phase.warmingUp;
      _phaseError = null;
    });
    try {
      // Coordination RAM : libère Whisper si chargé (sur 4 Go RAM, charger
      // les deux moteurs ML simultanément peut OOM). MlMemoryGuard est
      // optionnel (Provider injecté dans main.dart) ; absent => no-op.
      await context.read<MlMemoryGuard?>()?.requestGemma();
      await _gemma.warmUp();
      if (mounted) setState(() => _phase = _Phase.ready);
    } catch (e) {
      if (mounted) {
        final t = AppLocalizations.of(context);
        setState(() {
          _phase = _Phase.error;
          _phaseError = t.aiChatLoadFailed('$e');
        });
      }
    }
  }

  /// Import via Storage Access Framework uniquement.
  Future<void> _pickAndImport() async {
    // Lecture du toggle AVANT tout `await` — évite l'usage de `context`
    // après async gap (analyse statique stricte).
    final acceptUnknownHash = context
        .read<SettingsService>()
        .acceptUnknownGemmaHash;

    setState(() => _phaseError = null);

    final source = await _resolveSource();
    if (source == null) return;
    if (!mounted) return;

    setState(() {
      _phase = _Phase.importing;
      _importProgress = (copied: 0, total: source.lengthSync());
    });

    try {
      await for (final p in _gemma.importFromFile(
        source,
        acceptUnknownHash: acceptUnknownHash,
      )) {
        if (!mounted) return;
        setState(() => _importProgress = p);
      }
      await _warmUp();
    } catch (e) {
      if (mounted) {
        setState(() {
          _phase = _Phase.notInstalled;
          _phaseError = e.toString();
          _importProgress = null;
        });
      }
    }
  }

  Future<File?> _resolveSource() async {
    final t = AppLocalizations.of(context);
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['task'],
      allowMultiple: false,
      initialDirectory: '/storage/emulated/0/Download',
      dialogTitle: t.aiChatPickerDialogTitle,
    );
    if (result == null || result.files.single.path == null) return null;
    return File(result.files.single.path!);
  }

  // ---------------------------------------------------------------------
  // Conversation
  // ---------------------------------------------------------------------

  Future<void> _send() async {
    final question = _inputCtrl.text.trim();
    if (question.isEmpty || _generating || _phase != _Phase.ready) return;

    _inputCtrl.clear();
    // v1.0 i18n : on capture les chaînes localisées ICI (la locale du
    // contexte actif) et on les passe au service RAG. Cela garantit que
    // Gemma reçoit son prompt système dans la langue de l'utilisateur,
    // donc répond en français pour un user FR / en anglais pour un user EN.
    final t = AppLocalizations.of(context);
    final isEn = Localizations.localeOf(context).languageCode == 'en';
    final ragStrings = RagLocaleStrings(
      systemPrompt: isEn ? t.ragSystemPromptEn : t.ragSystemPromptFr,
      contextHeader: t.ragContextHeader,
      noResults: t.ragNoResults,
      untitledFallback: t.noteUntitled,
    );
    final ctx = await _rag.build(question, strings: ragStrings);
    final prompt = _rag.composePrompt(ctx);

    final userTurn = _ChatTurn.user(question, sources: ctx.sources);
    final aiTurn = _ChatTurn.assistant();
    setState(() {
      _turns
        ..add(userTurn)
        ..add(aiTurn);
      _generating = true;
    });
    _updateCanSend();
    _jumpToBottom();

    try {
      _genSub = _gemma
          .ask(prompt)
          .listen(
            (token) {
              if (!mounted) return;
              aiTurn.appendToken(token);
              _jumpToBottom();
            },
            onError: (Object e) {
              if (!mounted) return;
              final t = AppLocalizations.of(context);
              aiTurn.appendToken('\n\n[${t.commonErrorWith('$e')}]');
              setState(() => _generating = false);
              _updateCanSend();
            },
            onDone: () {
              if (!mounted) return;
              setState(() => _generating = false);
              _updateCanSend();
              _animateToBottom();
              final t = AppLocalizations.of(context);
              // ignore: deprecated_member_use
              SemanticsService.announce(
                t.aiChatAnnounceDone,
                TextDirection.ltr,
              );
            },
          );
    } catch (e) {
      if (mounted) {
        final t = AppLocalizations.of(context);
        aiTurn.appendToken('\n\n[${t.commonErrorWith('$e')}]');
        setState(() => _generating = false);
        _updateCanSend();
      }
    }
  }

  Future<void> _stopGeneration() async {
    await _genSub?.cancel();
    _genSub = null;
    await _gemma.stopGeneration();
    if (!mounted) return;
    setState(() => _generating = false);
    _updateCanSend();
  }

  Future<void> _clearConversation() async {
    if (_generating) await _stopGeneration();
    if (!mounted) return;
    setState(_disposeAllTurns);
  }

  /// Throttle 80ms : un seul postFrame en vol. Évite d'enfiler 30-50
  /// callbacks par seconde pendant le streaming Gemma.
  DateTime? _lastScrollAt;
  bool _scrollScheduled = false;
  void _jumpToBottom() {
    final now = DateTime.now();
    if (_lastScrollAt != null &&
        now.difference(_lastScrollAt!).inMilliseconds < 80) {
      return;
    }
    if (_scrollScheduled) return;
    _scrollScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scrollScheduled = false;
      if (!_scrollCtrl.hasClients) return;
      _lastScrollAt = DateTime.now();
      _scrollCtrl.jumpTo(_scrollCtrl.position.maxScrollExtent);
    });
  }

  void _animateToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.animateTo(
          _scrollCtrl.position.maxScrollExtent,
          duration: accessibleDuration(
            context,
            const Duration(milliseconds: 200),
          ),
          curve: Curves.easeOut,
        );
      }
    });
  }

  // ---------------------------------------------------------------------
  // Build
  // ---------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(t.aiChatTitle),
        actions: [
          if (_phase == _Phase.ready)
            IconButton(
              tooltip: t.aiChatClearConversation,
              icon: const Icon(Icons.refresh),
              onPressed: _turns.isEmpty ? null : _clearConversation,
            ),
        ],
      ),
      body: SafeArea(child: _buildBody()),
    );
  }

  Widget _buildBody() {
    return switch (_phase) {
      _Phase.checking => const Center(child: CircularProgressIndicator()),
      _Phase.notInstalled => _buildNotInstalled(),
      _Phase.importing => _buildImporting(),
      _Phase.warmingUp => _buildWarmingUp(),
      _Phase.ready => _buildChat(),
      _Phase.error => _buildError(),
    };
  }

  Widget _buildNotInstalled() {
    final t = AppLocalizations.of(context);
    return EmptyState(
      icon: Icons.psychology_alt_outlined,
      title: t.aiChatNotInstalledTitle,
      subtitle: _phaseError ?? t.gemmaHowToInstallSubtitle,
      action: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // v1.0.4 fix UX — bouton primaire qui guide vers la section
          // dédiée des Réglages (étapes claires + sources de téléchargement
          // officielles). Plus pédagogique que le file picker direct.
          FilledButton.icon(
            onPressed: _openSettingsForGemma,
            icon: const Icon(Icons.settings_outlined),
            label: Text(t.gemmaHowToInstall),
          ),
          const SizedBox(height: 8),
          // Secondaire : import direct pour l'utilisateur expérimenté qui
          // a déjà le .task dans Téléchargements.
          TextButton.icon(
            onPressed: _pickAndImport,
            icon: const Icon(Icons.folder_open_outlined),
            label: Text(t.gemmaImportFile),
          ),
        ],
      ),
    );
  }

  /// Ouvre l'écran Réglages, qui contient la section dédiée Gemma 3
  /// (statut, sources de téléchargement, import, désinstallation).
  Future<void> _openSettingsForGemma() async {
    await Navigator.of(
      context,
    ).push<void>(MaterialPageRoute(builder: (_) => const SettingsScreen()));
    // L'utilisateur peut avoir importé le modèle depuis Settings ; on
    // re-check l'état au retour.
    if (mounted) await _initialize();
  }

  Widget _buildImporting() {
    final p = _importProgress;
    final ratio = (p == null || p.total == 0) ? 0.0 : p.copied / p.total;
    final mb = (p?.copied ?? 0) ~/ (1024 * 1024);
    final total = (p?.total ?? 0) ~/ (1024 * 1024);
    final t = AppLocalizations.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.download_outlined, size: 48),
            const SizedBox(height: 16),
            Text(
              t.aiChatImportProgress(mb, total),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            LinearProgressIndicator(value: ratio.isFinite ? ratio : null),
          ],
        ),
      ),
    );
  }

  Widget _buildWarmingUp() {
    final t = AppLocalizations.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 16),
            Text(t.aiChatLoadingModel),
          ],
        ),
      ),
    );
  }

  Widget _buildError() {
    final t = AppLocalizations.of(context);
    return EmptyState(
      icon: Icons.error_outline,
      title: t.aiChatErrorTitle,
      subtitle: t.aiChatErrorHelp(_phaseError ?? t.commonError),
      action: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          FilledButton.icon(
            onPressed: _initialize,
            icon: const Icon(Icons.refresh),
            label: Text(t.commonRetry),
          ),
          const SizedBox(height: 8),
          TextButton.icon(
            onPressed: _reinstallModel,
            icon: const Icon(Icons.delete_sweep_outlined),
            label: Text(t.aiChatReinstall),
          ),
        ],
      ),
    );
  }

  Future<void> _reinstallModel() async {
    try {
      await _gemma.uninstall();
    } catch (_) {
      // Best-effort.
    }
    if (!mounted) return;
    setState(() {
      _phase = _Phase.notInstalled;
      _phaseError = null;
    });
  }

  Widget _buildChat() {
    final t = AppLocalizations.of(context);
    return Column(
      children: [
        Expanded(
          child: _turns.isEmpty
              ? EmptyState(
                  icon: Icons.auto_awesome,
                  title: t.aiChatEmptyTitle,
                  subtitle: t.aiChatEmptySubtitle,
                )
              : ListView.builder(
                  controller: _scrollCtrl,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 16,
                  ),
                  itemCount: _turns.length,
                  itemBuilder: (_, i) =>
                      _TurnBubble(turn: _turns[i], generating: _generating),
                ),
        ),
        const Divider(height: 1),
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _inputCtrl,
                  textInputAction: TextInputAction.send,
                  enableSuggestions: false,
                  autocorrect: false,
                  minLines: 1,
                  maxLines: 4,
                  onSubmitted: (_) => _send(),
                  decoration: InputDecoration(
                    labelText: t.aiChatComposerLabel,
                    hintText: t.aiChatHint,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              if (_generating)
                IconButton.filledTonal(
                  onPressed: _stopGeneration,
                  icon: const Icon(Icons.stop),
                  tooltip: t.aiChatStop,
                )
              else
                ValueListenableBuilder<bool>(
                  valueListenable: _canSend,
                  builder: (_, canSend, _) => Tooltip(
                    message: t.aiChatSendTooltip,
                    child: IconButton.filled(
                      onPressed: canSend ? _send : null,
                      icon: const Icon(Icons.send),
                      tooltip: t.aiChatSendTooltip,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------
// Modèle de tour de conversation
// ---------------------------------------------------------------------

enum _Phase { checking, notInstalled, importing, warmingUp, ready, error }

/// Tour de conversation. Le texte de l'assistant est exposé via un
/// `ValueNotifier<String>` pour que seule la bulle correspondante rebuild
/// pendant le streaming token-par-token.
class _ChatTurn {
  _ChatTurn._({required this.role, required String initialText, this.sources})
    : textNotifier = ValueNotifier<String>(initialText);

  factory _ChatTurn.user(String text, {List<SemanticHit> sources = const []}) =>
      _ChatTurn._(role: _Role.user, initialText: text, sources: sources);
  factory _ChatTurn.assistant() =>
      _ChatTurn._(role: _Role.assistant, initialText: '');

  final _Role role;
  final ValueNotifier<String> textNotifier;
  final List<SemanticHit>? sources;

  void appendToken(String chunk) {
    textNotifier.value = textNotifier.value + chunk;
  }

  /// v1.0.7 qual H1 — libère le `ValueNotifier` du turn.
  /// Sans ce dispose, chaque tour de conversation (user + assistant) crée
  /// un notifier jamais libéré → fuite cumulative sur les sessions longues
  /// et sur les clear `_turns` au lifecycle paused.
  void dispose() {
    textNotifier.dispose();
  }
}

enum _Role { user, assistant }

class _TurnBubble extends StatelessWidget {
  const _TurnBubble({required this.turn, required this.generating});
  final _ChatTurn turn;
  final bool generating;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final t = AppLocalizations.of(context);
    final isUser = turn.role == _Role.user;
    final align = isUser ? Alignment.centerRight : Alignment.centerLeft;
    final color = isUser
        ? theme.colorScheme.primary.withValues(alpha: 0.15)
        : theme.cardTheme.color;
    final bubbleSemanticsLabel = isUser
        ? t.aiChatBubbleUser
        : t.aiChatBubbleAssistant;

    // Pendant le streaming, on exclut le SelectableText des Semantics
    // pour éviter que le lecteur d'écran lise chaque token incrémentalement.
    // L'annonce finale est faite par SemanticsService.announce dans onDone.
    final bool excludeStreamingSemantics =
        !isUser && generating && turn.textNotifier.value.isEmpty == false;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Align(
        alignment: align,
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: MediaQuery.sizeOf(context).width * 0.85,
          ),
          child: Semantics(
            label: bubbleSemanticsLabel,
            container: true,
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: theme.dividerColor),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ValueListenableBuilder<String>(
                    valueListenable: turn.textNotifier,
                    builder: (_, text, _) {
                      final selectable = SelectableText(
                        text.isEmpty ? '…' : text,
                        style: theme.textTheme.bodyMedium,
                      );
                      // Pendant le streaming d'une bulle assistant, on
                      // empêche l'annonce token-par-token. La sémantique de
                      // la bulle reste portée par le `Semantics` parent.
                      if (excludeStreamingSemantics) {
                        return ExcludeSemantics(child: selectable);
                      }
                      return selectable;
                    },
                  ),
                  if (isUser && (turn.sources?.isNotEmpty ?? false)) ...[
                    const SizedBox(height: 8),
                    _SourcesRow(sources: turn.sources!),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SourcesRow extends StatelessWidget {
  const _SourcesRow({required this.sources});
  final List<SemanticHit> sources;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        for (final s in sources)
          ActionChip(
            label: Text(
              // v1.0.7 sécu M-02 — défense en profondeur : si une note
              // verrouillée se retrouve dans les sources RAG (ne devrait
              // jamais arriver car embeddings purgés au vault-isation,
              // F1 v1.0.3), on masque son titre. La chip reste cliquable
              // pour amener l'utilisateur à l'unlock sheet.
              s.note.isLocked
                  ? t.noteCardLocked
                  : (s.note.title.isEmpty ? t.noteUntitled : s.note.title),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            avatar: const Icon(Icons.description_outlined, size: 16),
            onPressed: () => _openNote(context, s.note),
          ),
      ],
    );
  }

  void _openNote(BuildContext context, Note note) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => NoteEditorScreen(noteId: note.id),
      ),
    );
  }
}
