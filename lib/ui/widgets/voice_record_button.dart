import 'package:files_tech_voice/files_tech_voice.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../services/voice/voice_service.dart';
import '../screens/voice_setup_screen.dart';
import 'voice_recording_overlay.dart';

/// Callback : appelé avec le texte transcrit propre, à insérer au curseur.
typedef OnTranscriptionInsert = void Function(String text);

/// Bouton micro pour l'éditeur de note.
///
/// Comportements :
/// - Si aucun modèle Whisper n'est installé → ouvre [VoiceSetupScreen].
/// - Sinon → démarre une capture, affiche [VoiceRecordingOverlay] modal.
/// - À l'arrêt de la capture, [onInsert] reçoit le texte transcrit.
///
/// Le bouton est volontairement compact (icon button) pour s'intégrer dans
/// la barre d'outils de l'éditeur sans alourdir l'UI.
class VoiceRecordButton extends StatelessWidget {
  const VoiceRecordButton({
    super.key,
    required this.onInsert,
    this.tooltip = 'Dicter une note',
  });

  final OnTranscriptionInsert onInsert;
  final String tooltip;

  Future<void> _handlePress(BuildContext context) async {
    final voice = context.read<VoiceService>();

    if (voice.state == VoiceServiceState.needsModel) {
      await Navigator.of(context).push<void>(
        MaterialPageRoute<void>(builder: (_) => const VoiceSetupScreen()),
      );
      return;
    }

    // Démarrage capture + overlay modal. L'overlay s'occupe d'appeler
    // stopAndTranscribe ou cancelRecording selon l'action utilisateur.
    final result = await showModalBottomSheet<SttTranscription>(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      enableDrag: false,
      backgroundColor: Colors.transparent,
      builder: (_) => const VoiceRecordingOverlay(),
    );

    if (result != null && result.text.isNotEmpty) {
      onInsert(result.text);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<VoiceService>(
      builder: (context, voice, _) {
        // L'icône change selon l'état pour donner un feedback visuel
        // immédiat même en dehors de l'overlay.
        IconData icon;
        switch (voice.state) {
          case VoiceServiceState.needsModel:
            icon = Icons.mic_off_outlined;
          case VoiceServiceState.recording:
            icon = Icons.fiber_manual_record;
          case VoiceServiceState.transcribing:
            icon = Icons.auto_awesome;
          case VoiceServiceState.error:
            icon = Icons.error_outline;
          case VoiceServiceState.ready:
            icon = Icons.mic_none_outlined;
        }
        return IconButton(
          tooltip: tooltip,
          icon: Icon(icon),
          onPressed: () => _handlePress(context),
        );
      },
    );
  }
}
