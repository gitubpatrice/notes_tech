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
import 'package:provider/provider.dart';

import '../../core/a11y.dart';
import '../../data/models/note.dart';
import '../../services/ai/gemma_service.dart';
import '../../services/ai/rag_service.dart';
import '../../services/ml/ml_memory_guard.dart';
import '../../services/semantic_search_service.dart';
import '../../services/settings_service.dart';
import '../widgets/empty_state.dart';
import 'note_editor_screen.dart';

class AiChatScreen extends StatefulWidget {
  const AiChatScreen({super.key});

  @override
  State<AiChatScreen> createState() => _AiChatScreenState();
}

class _AiChatScreenState extends State<AiChatScreen> {
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
    _gemma = context.read<GemmaService>();
    _rag = RagService(search: context.read<SemanticSearchService>());
    _inputCtrl.addListener(_updateCanSend);
    _initialize();
  }

  @override
  void dispose() {
    _inputCtrl.removeListener(_updateCanSend);
    _genSub?.cancel();
    // Si une génération tournait, on la coupe côté natif aussi.
    if (_generating) _gemma.stopGeneration();
    _inputCtrl.dispose();
    _scrollCtrl.dispose();
    _canSend.dispose();
    super.dispose();
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
        setState(() {
          _phase = _Phase.error;
          _phaseError = 'Chargement du modèle échoué : $e';
        });
      }
    }
  }

  /// Import via Storage Access Framework uniquement.
  /// Pas de chemin direct codé en dur : sur Android 13+ la lecture
  /// dans `/storage/emulated/0/Download` exige `READ_MEDIA_*` ou
  /// `MANAGE_EXTERNAL_STORAGE`, permissions volontairement absentes
  /// du manifest. Le SAF traverse cette barrière sans permission.
  Future<void> _pickAndImport() async {
    // Lecture du toggle AVANT tout `await` — évite l'usage de `context`
    // après async gap (analyse statique stricte).
    final acceptUnknownHash =
        context.read<SettingsService>().acceptUnknownGemmaHash;

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
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['task'],
      allowMultiple: false,
      initialDirectory: '/storage/emulated/0/Download',
      dialogTitle: 'Sélectionnez gemma3-1b-it-int4.task',
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
    final ctx = await _rag.build(question);
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
      _genSub = _gemma.ask(prompt).listen(
        (token) {
          if (!mounted) return;
          aiTurn.appendToken(token);
          _jumpToBottom();
        },
        onError: (Object e) {
          if (!mounted) return;
          aiTurn.appendToken('\n\n[Erreur de génération : $e]');
          setState(() => _generating = false);
          _updateCanSend();
        },
        onDone: () {
          if (!mounted) return;
          setState(() => _generating = false);
          _updateCanSend();
          _animateToBottom();
        },
      );
    } catch (e) {
      if (mounted) {
        aiTurn.appendToken('\n\n[Erreur : $e]');
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
    setState(_turns.clear);
  }

  void _jumpToBottom() {
    if (!_scrollCtrl.hasClients) return;
    _scrollCtrl.jumpTo(_scrollCtrl.position.maxScrollExtent);
  }

  void _animateToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.animateTo(
          _scrollCtrl.position.maxScrollExtent,
          duration: accessibleDuration(
              context, const Duration(milliseconds: 200)),
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
    return Scaffold(
      appBar: AppBar(
        title: const Text('Demander à mes notes'),
        actions: [
          if (_phase == _Phase.ready)
            IconButton(
              tooltip: 'Effacer la conversation',
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
    return EmptyState(
      icon: Icons.psychology_alt_outlined,
      title: 'Modèle IA non installé',
      subtitle: _phaseError ??
          'Importe le fichier gemma3-1b-it-int4.task (≈ 530 Mo) '
              "depuis ton téléphone. Il sera copié dans l'app et "
              'fonctionnera 100% hors ligne.',
      action: FilledButton.icon(
        onPressed: _pickAndImport,
        icon: const Icon(Icons.file_open_outlined),
        label: const Text('Importer le modèle'),
      ),
    );
  }

  Widget _buildImporting() {
    final p = _importProgress;
    final ratio = (p == null || p.total == 0) ? 0.0 : p.copied / p.total;
    final mb = (p?.copied ?? 0) ~/ (1024 * 1024);
    final total = (p?.total ?? 0) ~/ (1024 * 1024);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.download_outlined, size: 48),
            const SizedBox(height: 16),
            Text(
              'Import en cours… $mb / $total Mo',
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
    return const Center(
      child: Padding(
        padding: EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Chargement du modèle Gemma…'),
          ],
        ),
      ),
    );
  }

  Widget _buildError() {
    return EmptyState(
      icon: Icons.error_outline,
      title: 'Chargement impossible',
      subtitle: '${_phaseError ?? "Erreur inconnue"}\n\n'
          'Si le modèle est corrompu, supprimez-le et réimportez le fichier.',
      action: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          FilledButton.icon(
            onPressed: _initialize,
            icon: const Icon(Icons.refresh),
            label: const Text('Réessayer'),
          ),
          const SizedBox(height: 8),
          TextButton.icon(
            onPressed: _reinstallModel,
            icon: const Icon(Icons.delete_sweep_outlined),
            label: const Text('Supprimer et réimporter'),
          ),
        ],
      ),
    );
  }

  Future<void> _reinstallModel() async {
    try {
      await _gemma.uninstall();
    } catch (_) {
      // Best-effort : si la suppression échoue, on enchaîne quand même.
    }
    if (!mounted) return;
    setState(() {
      _phase = _Phase.notInstalled;
      _phaseError = null;
    });
  }

  Widget _buildChat() {
    return Column(
      children: [
        Expanded(
          child: _turns.isEmpty
              ? const EmptyState(
                  icon: Icons.auto_awesome,
                  title: 'Pose une question sur tes notes',
                  subtitle:
                      'Le modèle répond uniquement à partir des notes les '
                      'plus proches de ta question.',
                )
              : ListView.builder(
                  controller: _scrollCtrl,
                  padding: const EdgeInsets.symmetric(
                      horizontal: 12, vertical: 16),
                  itemCount: _turns.length,
                  itemBuilder: (_, i) => _TurnBubble(turn: _turns[i]),
                ),
        ),
        const Divider(height: 1),
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
          child: Row(
            children: [
              Expanded(
                child: Semantics(
                  label: 'Question à poser à l\'assistant',
                  textField: true,
                  child: TextField(
                    controller: _inputCtrl,
                    textInputAction: TextInputAction.send,
                    enableSuggestions: false,
                    autocorrect: false,
                    minLines: 1,
                    maxLines: 4,
                    onSubmitted: (_) => _send(),
                    decoration: const InputDecoration(
                      hintText: 'Pose une question…',
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              if (_generating)
                IconButton.filledTonal(
                  onPressed: _stopGeneration,
                  icon: const Icon(Icons.stop),
                  tooltip: 'Arrêter',
                )
              else
                ValueListenableBuilder<bool>(
                  valueListenable: _canSend,
                  builder: (_, canSend, _) => IconButton.filled(
                    onPressed: canSend ? _send : null,
                    icon: const Icon(Icons.send),
                    tooltip: 'Envoyer la question',
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
}

enum _Role { user, assistant }

class _TurnBubble extends StatelessWidget {
  const _TurnBubble({required this.turn});
  final _ChatTurn turn;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isUser = turn.role == _Role.user;
    final align = isUser ? Alignment.centerRight : Alignment.centerLeft;
    final color = isUser
        ? theme.colorScheme.primary.withValues(alpha: 0.15)
        : theme.cardTheme.color;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Align(
        alignment: align,
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: MediaQuery.of(context).size.width * 0.85,
          ),
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
                  builder: (_, text, _) => SelectableText(
                    text.isEmpty ? '…' : text,
                    style: theme.textTheme.bodyMedium,
                  ),
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
    );
  }
}

class _SourcesRow extends StatelessWidget {
  const _SourcesRow({required this.sources});
  final List<SemanticHit> sources;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        for (final s in sources)
          ActionChip(
            label: Text(
              s.note.title.isEmpty ? 'Sans titre' : s.note.title,
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
      MaterialPageRoute<void>(builder: (_) => NoteEditorScreen(noteId: note.id)),
    );
  }
}
