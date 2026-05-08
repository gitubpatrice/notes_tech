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
  // Gemma
  gemmaModelNotInstalled,
  gemmaFileNotFound,
  gemmaFileTooSmall,
  gemmaFileTooLarge,
  gemmaInitFailed,
  gemmaNotLoaded,
  gemmaBusy,
  gemmaHashMismatch,
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
