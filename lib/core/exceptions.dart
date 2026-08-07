/// Hiérarchie d'exceptions métier.
///
/// Permet à l'UI de discriminer les erreurs sans matcher sur des messages.
///
/// ## Localisation (v1.0.0)
///
/// Chaque erreur destinée à l'utilisateur porte désormais un
/// [NotesErrorCode] permettant à l'UI de localiser le message dans la
/// langue active (FR/EN) au moment de l'affichage. Les anciens
/// constructeurs `(String message)` restent supportés (rétro-compat
/// pour les tests qui inspectent `.message`) mais sont marqués
/// `@Deprecated` côté call-sites métier qui DOIVENT préférer la
/// variante `.coded(code)`.
///
/// ```dart
/// throw const NotesException(NotesErrorCode.folderNameRequired);
/// // côté UI :
/// _showError(e.code.localize(t));
/// ```
library;

/// Codes d'erreur stables identifiant chaque cause d'erreur métier
/// remontée à l'UI. L'UI utilise [NotesErrorCode.localize] pour traduire.
///
/// **Ne jamais réordonner ni renommer** — ces noms sont la clé de
/// jointure entre throw-site et fichier ARB.
enum NotesErrorCode {
  // Dossiers
  folderNameRequired,
  inboxNotDeletable,
  // Notes
  noteTitleTooLong,
  // Vault — passphrase
  vaultAlreadyEnabled,
  vaultPassphraseTooShort,
  vaultPassphraseWrong,
  // Vault — PIN
  vaultPinTooShort,
  vaultPinNotDigits,
  vaultPinWrong,
  vaultPinWiped,
  vaultNotPinVault,
  // Vault — état
  vaultNotAVault,
  vaultLocked,
  vaultEncryptedContentInvalid,
  vaultWrapInvalid,
  // Voice (Whisper)
  voiceNoModelInstalled,
  voiceStartCaptureFailed,
  voiceTranscribeFailed,
  voiceMicCaptureError,
}

// Pas `sealed` : la hiérarchie est ouverte aux modules services
// (security/folder_vault_service expose ses propres
// `VaultValidationException`, `WrongPassphraseException`, etc. qui
// héritent du tronc commun).
class NotesTechException implements Exception {
  const NotesTechException(this.message, {this.cause, this.code});
  final String message;
  final Object? cause;

  /// Code d'erreur stable pour la localisation UI. Optionnel pour
  /// rétro-compat avec les exceptions historiques construites avec
  /// un message brut. Tous les nouveaux throw-sites DOIVENT le fournir.
  final NotesErrorCode? code;

  @override
  String toString() => '$runtimeType: $message';
}

/// Exception générique typée par [NotesErrorCode], destinée aux nouveaux
/// throw-sites. Préférer celle-ci à [ValidationException] (gardée pour
/// rétro-compat des tests).
class NotesException extends NotesTechException {
  const NotesException(NotesErrorCode code, {Object? cause})
    : super('NotesException', cause: cause, code: code);

  @override
  String toString() => 'NotesException(${code?.name})';
}

class DatabaseException extends NotesTechException {
  const DatabaseException(super.message, {super.cause, super.code});
}

class NoteNotFoundException extends NotesTechException {
  const NoteNotFoundException(String noteId)
    : super('Note introuvable : $noteId');
}

/// Tentative d'écrire le contenu EN CLAIR d'une note dans un dossier coffre.
///
/// Signale un **défaut de programmation**, pas une condition utilisateur :
/// c'est pourquoi elle ne porte pas de `NotesErrorCode` localisé. L'invariant
/// « une note d'un dossier coffre n'est jamais persistée en clair » était
/// jusqu'ici réimplémenté par chaque appelant qui pensait à chiffrer ; il est
/// désormais porté par `NotesRepository`, au point d'écriture, et cette
/// exception est ce qui se produit quand un chemin l'oublie.
///
/// Le seul contournement légitime est `NotesRepository.save(...,
/// allowPlaintextInVault: true)`, utilisé avant la suppression d'un coffre
/// pour ne pas perdre les notes qu'il contient.
class VaultPlaintextWriteException extends NotesTechException {
  const VaultPlaintextWriteException({
    required this.noteId,
    required this.folderId,
    required this.operation,
  }) : super(
         'Écriture en clair refusée : la note $noteId appartient au dossier '
         'coffre $folderId (opération « $operation »). Chiffrer via '
         'FolderVaultService.encryptNote avant de persister.',
       );

  final String noteId;
  final String folderId;
  final String operation;
}

class FolderNotFoundException extends NotesTechException {
  const FolderNotFoundException(String folderId)
    : super('Dossier introuvable : $folderId');
}

class ValidationException extends NotesTechException {
  const ValidationException(super.message, {super.code});

  /// Constructeur préféré : code obligatoire, message dérivé du code
  /// (placeholder, l'UI le re-localise via [NotesErrorCode.localize]).
  const ValidationException.coded(NotesErrorCode code)
    : super('ValidationException', code: code);
}
