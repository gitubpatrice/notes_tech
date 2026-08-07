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
import '../../l10n/app_localizations.dart';

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
      builder: (ctx) {
        final t = AppLocalizations.of(ctx);
        return AlertDialog(
          title: Semantics(header: true, child: Text(title)),
          content: TextField(
            controller: controller,
            autofocus: true,
            textInputAction: TextInputAction.done,
            maxLength: 64,
            decoration: InputDecoration(labelText: hint, hintText: hint),
            onSubmitted: (_) {
              if (canSubmit.value) {
                Navigator.of(ctx).pop(controller.text.trim());
              }
            },
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: Text(t.commonCancel),
            ),
            ValueListenableBuilder<bool>(
              valueListenable: canSubmit,
              builder: (_, ok, _) => FilledButton(
                onPressed: ok
                    ? () => Navigator.of(ctx).pop(controller.text.trim())
                    : null,
                child: Text(t.commonValidate),
              ),
            ),
          ],
        );
      },
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
/// [isVault] change la nature de l'avertissement, pas seulement son ton.
///
/// ⚠️ Pour un coffre, « Déplacer vers Boîte de réception » DÉCHIFFRE toutes
/// les notes et les écrit en clair en base (`decryptAllNotesInFolder`). Le
/// dialog demandait « Que faire des notes de X ? » sans le dire, et
/// présentait ce choix en `FilledButton` — c'est-à-dire comme l'option sûre.
/// L'action la plus destructrice pour la confidentialité était celle qui
/// avait l'air la plus anodine.
///
/// Sortir UNE note d'un coffre, elle, était confirmée par un dialog explicite
/// depuis la v1.1.0 (`noteEditorExitVaultBody`). Vider un coffre entier ne
/// l'était pas : jumeau asymétrique, et du mauvais côté.
Future<FolderDeletionChoice?> confirmDeleteFolder({
  required BuildContext context,
  required String folderName,
  bool isVault = false,
}) async {
  return showDialog<FolderDeletionChoice>(
    context: context,
    builder: (ctx) {
      final cs = Theme.of(ctx).colorScheme;
      final t = AppLocalizations.of(ctx);
      return AlertDialog(
        icon: ExcludeSemantics(
          child: Icon(
            isVault ? Icons.lock_open : Icons.delete_outline,
            color: isVault ? cs.error : null,
          ),
        ),
        title: Semantics(header: true, child: Text(t.folderDeleteTitle)),
        content: Text(
          isVault
              ? t.folderDeleteVaultChoiceBody(folderName)
              : t.folderDeleteChoiceBody(folderName),
        ),
        actionsOverflowDirection: VerticalDirection.down,
        actions: [
          TextButton(
            // Autofocus sur Annuler, comme les autres dialogs destructifs
            // (corbeille, sortie de coffre) — c'était le seul à ne pas
            // l'avoir, alors qu'il porte le choix le plus lourd.
            autofocus: true,
            onPressed: () => Navigator.of(ctx).pop(),
            child: Text(t.commonCancel),
          ),
          TextButton.icon(
            onPressed: () =>
                Navigator.of(ctx).pop(FolderDeletionChoice.cascadeDelete),
            icon: ExcludeSemantics(
              child: Icon(Icons.warning_amber_outlined, color: cs.error),
            ),
            label: Text(
              t.folderDeletePermanent,
              style: TextStyle(color: cs.error),
            ),
          ),
          // Pour un coffre, ce choix n'est PAS l'option sûre : il déchiffre
          // tout. Il perd donc son statut de bouton par défaut et prend les
          // couleurs d'avertissement, comme la sortie de coffre d'une note.
          if (isVault)
            FilledButton.icon(
              style: FilledButton.styleFrom(
                backgroundColor: cs.errorContainer,
                foregroundColor: cs.onErrorContainer,
              ),
              onPressed: () =>
                  Navigator.of(ctx).pop(FolderDeletionChoice.moveToInbox),
              icon: const ExcludeSemantics(child: Icon(Icons.lock_open)),
              label: Text(t.folderDeleteMoveToInboxVault),
            )
          else
            FilledButton.icon(
              onPressed: () =>
                  Navigator.of(ctx).pop(FolderDeletionChoice.moveToInbox),
              icon: const ExcludeSemantics(child: Icon(Icons.inbox_outlined)),
              label: Text(t.folderDeleteMoveToInbox),
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
/// event `bulk`, pour ne pas noyer les services en aval sous N
/// notifications là où l'utilisateur n'a fait qu'une action.
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
