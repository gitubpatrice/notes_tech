/// Dialogues utilitaires pour la gestion des dossiers : création,
/// renommage, confirmation de suppression, déplacement en masse.
///
/// Concentrer ces helpers dans un fichier dédié évite que `folders_drawer`
/// devienne tentaculaire et permet de les réutiliser depuis l'éditeur de
/// note (action « Déplacer vers… ») sans dupliquer la logique.
library;

import 'package:flutter/material.dart';

import '../../core/constants.dart';
import '../../data/repositories/notes_repository.dart';

/// Demande à l'utilisateur un nom de dossier (création ou renommage).
///
/// Retourne le nom **trimmé** (jamais vide) ou `null` si annulé.
/// Le bouton de validation est désactivé tant que la saisie est vide ou
/// identique à [initial].
Future<String?> showFolderNameDialog({
  required BuildContext context,
  required String title,
  required String hint,
  String? initial,
}) async {
  final controller = TextEditingController(text: initial ?? '');
  // ValueNotifier pour activer/désactiver le bouton sans setState dans
  // un dialogue (qui n'est pas un StatefulWidget).
  final canSubmit = ValueNotifier<bool>(_isValidName(controller.text, initial));
  controller.addListener(() {
    canSubmit.value = _isValidName(controller.text, initial);
  });

  try {
    return await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          autofocus: true,
          textInputAction: TextInputAction.done,
          maxLength: 64,
          decoration: InputDecoration(hintText: hint),
          onSubmitted: (_) {
            if (canSubmit.value) {
              Navigator.of(ctx).pop(controller.text.trim());
            }
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Annuler'),
          ),
          ValueListenableBuilder<bool>(
            valueListenable: canSubmit,
            builder: (_, ok, _) => FilledButton(
              onPressed: ok
                  ? () => Navigator.of(ctx).pop(controller.text.trim())
                  : null,
              child: const Text('Valider'),
            ),
          ),
        ],
      ),
    );
  } finally {
    controller.dispose();
    canSubmit.dispose();
  }
}

bool _isValidName(String raw, String? initial) {
  final trimmed = raw.trim();
  if (trimmed.isEmpty) return false;
  if (initial != null && trimmed == initial) return false; // pas de no-op
  return true;
}

/// Choix retourné par [confirmDeleteFolder] : déplacement préalable des
/// notes vers la Boîte de réception, OU suppression directe (cascade SQL
/// sur les notes du dossier).
enum FolderDeletionChoice {
  /// Déplacer toutes les notes vers `inbox` puis supprimer le dossier.
  /// Recommandé pour ne pas perdre de données.
  moveToInbox,

  /// Supprimer le dossier ET toutes ses notes (ON DELETE CASCADE en SQL).
  /// L'utilisateur a explicitement confirmé.
  cascadeDelete,
}

/// Boîte de dialogue de confirmation avant suppression d'un dossier.
/// Retourne `null` si annulé, sinon le choix de l'utilisateur.
///
/// Garde-fou UX :
/// - Action **par défaut visuelle** = la non-destructrice (Déplacer vers
///   Boîte de réception), affichée en bouton plein primary.
/// - Action destructrice = `TextButton` discret en rouge, libellé
///   explicite « Supprimer DÉFINITIVEMENT le dossier et son contenu »
///   sans corbeille possible. L'utilisateur doit le viser.
/// - Annuler reste prioritaire en haut de la pile (UX Material).
Future<FolderDeletionChoice?> confirmDeleteFolder({
  required BuildContext context,
  required String folderName,
}) async {
  return showDialog<FolderDeletionChoice>(
    context: context,
    builder: (ctx) {
      final cs = Theme.of(ctx).colorScheme;
      return AlertDialog(
        icon: const Icon(Icons.delete_outline),
        title: Text('Supprimer "$folderName" ?'),
        content: const Text(
          'Choisissez ce qu\'il advient des notes contenues dans ce dossier.\n\n'
          '• Déplacer vers Boîte de réception : aucune note n\'est perdue.\n'
          '• Supprimer définitivement : dossier ET notes effacés sans '
          'corbeille — opération irréversible.',
        ),
        actionsOverflowDirection: VerticalDirection.down,
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Annuler'),
          ),
          TextButton.icon(
            onPressed: () =>
                Navigator.of(ctx).pop(FolderDeletionChoice.cascadeDelete),
            icon: Icon(Icons.warning_amber_outlined, color: cs.error),
            label: Text(
              'Supprimer définitivement',
              style: TextStyle(color: cs.error),
            ),
          ),
          FilledButton.icon(
            onPressed: () =>
                Navigator.of(ctx).pop(FolderDeletionChoice.moveToInbox),
            icon: const Icon(Icons.inbox_outlined),
            label: const Text('Déplacer vers Boîte de réception'),
          ),
        ],
      );
    },
  );
}

/// Déplace en une seule transaction SQL **toutes** les notes du dossier
/// source vers la Boîte de réception — y compris notes archivées et en
/// corbeille. C'est le garde-fou avant `FoldersRepository.delete` :
/// le `ON DELETE CASCADE` SQL effacerait sinon définitivement les notes
/// en corbeille (bypass de la rétention 30 jours).
///
/// Implémentation batch (UPDATE atomique) plutôt qu'une boucle de
/// `save()` : sur 100 notes l'ancienne version freezait l'UI 5-15 s sur
/// S9 et émettait 100 events de réindexation. Le batch émet 1 seul
/// event `bulk` que le coordinateur d'embeddings sait coalescer.
///
/// Prend [NotesRepository] explicitement (et non un BuildContext) :
/// rend la fonction testable sans monter de Widget tree.
///
/// Idempotent : retourne 0 si le dossier source est vide ou identique
/// à la cible.
Future<int> moveAllNotesToInbox(
  NotesRepository notes, {
  required String fromFolderId,
}) async {
  if (fromFolderId == AppConstants.inboxFolderId) return 0;
  return notes.reassignFolder(
    fromFolderId: fromFolderId,
    toFolderId: AppConstants.inboxFolderId,
  );
}
