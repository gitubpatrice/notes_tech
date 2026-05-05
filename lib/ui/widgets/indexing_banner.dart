/// Bandeau discret affichant la progression d'indexation des embeddings.
///
/// - Invisible quand `progress` est null ou terminée.
/// - Auto-hide via `AnimatedSize` pour un appearing fluide.
/// - Non-intrusif : pas de bloqueur d'interaction, juste informatif.
library;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../services/indexing_service.dart';

class IndexingBanner extends StatelessWidget {
  const IndexingBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final indexing = context.read<IndexingService>();
    return StreamBuilder<IndexingProgress?>(
      stream: indexing.progress,
      initialData: indexing.currentProgress,
      builder: (context, snap) {
        final p = snap.data;
        final visible = p != null && !p.finished;
        return AnimatedSize(
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
          alignment: Alignment.topCenter,
          child: visible
              ? _BannerBody(progress: p)
              : const SizedBox(width: double.infinity),
        );
      },
    );
  }
}

class _BannerBody extends StatelessWidget {
  const _BannerBody({required this.progress});
  final IndexingProgress progress;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isMiniLm = progress.modelId.startsWith('minilm');
    final label = isMiniLm
        ? 'Indexation sémantique'
        : 'Indexation';
    return Material(
      color: theme.colorScheme.primary.withValues(alpha: 0.08),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '$label · ${progress.done}/${progress.total}',
                    style: theme.textTheme.labelMedium,
                  ),
                  const SizedBox(height: 4),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(2),
                    child: LinearProgressIndicator(
                      value: progress.ratio,
                      minHeight: 3,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
