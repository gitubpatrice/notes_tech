import 'dart:async';

import 'package:files_tech_voice/files_tech_voice.dart';
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:provider/provider.dart';

import '../../l10n/app_localizations.dart';
import '../../services/voice/voice_service.dart';

/// Bottom sheet modal qui pilote une capture micro de bout en bout.
///
/// Cycle UX :
/// 1. Au mount, déclenche `voice.startRecording()` (permission + démarrage).
///    Si la permission est refusée → affiche un message + bouton "Fermer".
/// 2. Pendant l'enregistrement → timer mm:ss + bouton stop + bouton annuler.
/// 3. À l'arrêt → loading "Transcription en cours…" puis `Navigator.pop`
///    avec la `SttTranscription` (le caller insère le texte au curseur).
/// 4. À l'annulation → `Navigator.pop(null)` sans transcription.
///
/// Le `WillPopScope` est neutralisé en mode capture/transcribing pour
/// éviter qu'un swipe-to-dismiss laisse le moteur dans un état zombi.
class VoiceRecordingOverlay extends StatefulWidget {
  const VoiceRecordingOverlay({super.key});

  @override
  State<VoiceRecordingOverlay> createState() => _VoiceRecordingOverlayState();
}

class _VoiceRecordingOverlayState extends State<VoiceRecordingOverlay> {
  Timer? _ticker;
  Duration _elapsed = Duration.zero;
  String? _permissionError;
  bool _permissionPermanent = false;

  @override
  void initState() {
    super.initState();
    // Lance la capture après le 1er frame (sinon `context.read` non garanti).
    WidgetsBinding.instance.addPostFrameCallback((_) => _start());
    _ticker = Timer.periodic(const Duration(milliseconds: 250), (_) {
      if (!mounted) return;
      final voice = context.read<VoiceService>();
      if (voice.state == VoiceServiceState.recording) {
        setState(() => _elapsed = _elapsed + const Duration(milliseconds: 250));
      }
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  Future<void> _start() async {
    final voice = context.read<VoiceService>();
    try {
      await voice.startRecording();
    } on SttPermissionDenied catch (e) {
      if (!mounted) return;
      setState(() {
        _permissionError = e.message;
        _permissionPermanent = e.permanently;
      });
    } catch (_) {
      // L'erreur est déjà reflétée dans voice.lastError + state==error.
    }
  }

  Future<void> _stop() async {
    final voice = context.read<VoiceService>();
    final navigator = Navigator.of(context);
    final t = AppLocalizations.of(context);
    try {
      final result = await voice.stopAndTranscribe(language: 'fr');
      if (!mounted) return;
      // Annonce TalkBack que la transcription est terminée et insérée.
      unawaited(
        SemanticsService.announce(t.voiceTranscribed, TextDirection.ltr),
      );
      navigator.pop(result);
    } catch (_) {
      // Même remarque : l'UI affichera l'état error via le Consumer.
    }
  }

  Future<void> _cancel() async {
    final voice = context.read<VoiceService>();
    final navigator = Navigator.of(context);
    await voice.cancelRecording();
    if (!mounted) return;
    navigator.pop();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final voice = context.watch<VoiceService>();
    // En `transcribing`, on bloque totalement le back : la transcription
    // native tourne déjà, on attend qu'elle finisse pour ne pas perdre
    // silencieusement le résultat. En `recording`, on annule + pop.
    final canPop = voice.state != VoiceServiceState.recording &&
        voice.state != VoiceServiceState.transcribing;
    return PopScope(
      canPop: canPop,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        if (voice.state == VoiceServiceState.recording) {
          await _cancel();
        }
        // En `transcribing` on ne fait rien : le pop est déjà bloqué et
        // le widget se rebuildera dès que l'état change vers `idle`.
      },
      child: SafeArea(
        top: false,
        child: Consumer<VoiceService>(
          builder: (context, voice, _) {
            return Container(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
              decoration: BoxDecoration(
                color: cs.surface,
                borderRadius: const BorderRadius.vertical(
                  top: Radius.circular(20),
                ),
              ),
              child: _bodyFor(voice),
            );
          },
        ),
      ),
    );
  }

  Widget _bodyFor(VoiceService voice) {
    final t = AppLocalizations.of(context);
    if (_permissionError != null) {
      return _PermissionErrorBody(
        message: _permissionError!,
        permanentlyDenied: _permissionPermanent,
        onClose: () => Navigator.of(context).pop(),
      );
    }
    switch (voice.state) {
      case VoiceServiceState.recording:
        return _RecordingBody(
          elapsed: _elapsed,
          onStop: _stop,
          onCancel: _cancel,
        );
      case VoiceServiceState.transcribing:
        return const _TranscribingBody();
      case VoiceServiceState.error:
        return _ErrorBody(
          message: voice.lastError ?? t.commonError,
          onClose: () => Navigator.of(context).pop(),
        );
      case VoiceServiceState.ready:
      case VoiceServiceState.needsModel:
        // Phase transitoire (start en cours) → loader court.
        return const _StartingBody();
    }
  }
}

// ---------------------------------------------------------------------------
// Sous-widgets
// ---------------------------------------------------------------------------

class _StartingBody extends StatelessWidget {
  const _StartingBody();

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return SizedBox(
      height: 140,
      child: Center(
        child: Semantics(
          liveRegion: true,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const CircularProgressIndicator(),
              const SizedBox(height: 16),
              Text(t.voiceMicInitializing),
            ],
          ),
        ),
      ),
    );
  }
}

class _RecordingBody extends StatelessWidget {
  const _RecordingBody({
    required this.elapsed,
    required this.onStop,
    required this.onCancel,
  });

  final Duration elapsed;
  final VoidCallback onStop;
  final VoidCallback onCancel;

  String _format(Duration d) {
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const SizedBox(height: 8),
        // Pastille rouge clignotante (animation discrète).
        ExcludeSemantics(
          child: TweenAnimationBuilder<double>(
            tween: Tween(begin: 0.4, end: 1.0),
            duration: const Duration(seconds: 1),
            builder: (context, value, _) => Opacity(
              opacity: value,
              child: Icon(
                Icons.fiber_manual_record,
                color: cs.error,
                size: 18,
              ),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Semantics(
          liveRegion: true,
          header: true,
          child: Text(
            _format(elapsed),
            style: TextStyle(
              fontSize: 36,
              fontWeight: FontWeight.w300,
              fontFeatures: const [FontFeature.tabularFigures()],
              color: cs.onSurface,
            ),
          ),
        ),
        const SizedBox(height: 6),
        Text(
          t.voiceRecordingTitle,
          style: TextStyle(color: cs.onSurfaceVariant),
        ),
        const SizedBox(height: 4),
        Text(
          t.voiceRecordingHint,
          style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 24),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: onCancel,
                style: OutlinedButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                ),
                child: Text(t.commonCancel),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: FilledButton.icon(
                onPressed: onStop,
                icon: const ExcludeSemantics(child: Icon(Icons.stop)),
                label: Text(t.voiceRecordingStop),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _TranscribingBody extends StatelessWidget {
  const _TranscribingBody();

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return SizedBox(
      height: 160,
      child: Center(
        child: Semantics(
          liveRegion: true,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const CircularProgressIndicator(),
              const SizedBox(height: 20),
              Text(
                t.voiceTranscribing,
                style: const TextStyle(fontWeight: FontWeight.w500),
              ),
              const SizedBox(height: 6),
              Text(
                t.voiceTranscribingHint,
                style: const TextStyle(fontSize: 12),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ErrorBody extends StatelessWidget {
  const _ErrorBody({required this.message, required this.onClose});
  final String message;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Semantics(
      liveRegion: true,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SizedBox(height: 8),
          const ExcludeSemantics(child: Icon(Icons.error_outline, size: 40)),
          const SizedBox(height: 12),
          Text(
            message,
            textAlign: TextAlign.center,
            style: const TextStyle(height: 1.4),
          ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: onClose,
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(48),
            ),
            child: Text(t.commonClose),
          ),
        ],
      ),
    );
  }
}

class _PermissionErrorBody extends StatelessWidget {
  const _PermissionErrorBody({
    required this.message,
    required this.permanentlyDenied,
    required this.onClose,
  });
  final String message;
  final bool permanentlyDenied;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Semantics(
      liveRegion: true,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SizedBox(height: 8),
          const ExcludeSemantics(
            child: Icon(Icons.mic_off_outlined, size: 40),
          ),
          const SizedBox(height: 12),
          Semantics(
            header: true,
            child: Text(
              t.voicePermissionDenied,
              textAlign: TextAlign.center,
              style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            message,
            textAlign: TextAlign.center,
            style: const TextStyle(height: 1.4),
          ),
          const SizedBox(height: 20),
          if (permanentlyDenied) ...[
            FilledButton.icon(
              onPressed: () async {
                await context.read<VoiceService>().openSystemAppSettings();
                onClose();
              },
              icon: const ExcludeSemantics(
                child: Icon(Icons.settings_outlined),
              ),
              label: Text(t.voiceOpenSystemSettings),
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(48),
              ),
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: onClose,
              style: OutlinedButton.styleFrom(
                minimumSize: const Size.fromHeight(48),
              ),
              child: Text(t.commonClose),
            ),
          ] else ...[
            FilledButton(
              onPressed: onClose,
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(48),
              ),
              child: Text(t.commonClose),
            ),
          ],
        ],
      ),
    );
  }
}
