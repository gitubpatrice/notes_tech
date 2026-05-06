/// Hiérarchie d'exceptions métier.
///
/// Permet à l'UI de discriminer les erreurs sans matcher sur des messages.
library;

// Pas `sealed` : la hiérarchie est ouverte aux modules services
// (security/folder_vault_service expose ses propres
// `VaultValidationException`, `WrongPassphraseException`, etc. qui
// héritent du tronc commun).
class NotesTechException implements Exception {
  const NotesTechException(this.message, {this.cause});
  final String message;
  final Object? cause;

  @override
  String toString() => '$runtimeType: $message';
}

class DatabaseException extends NotesTechException {
  const DatabaseException(super.message, {super.cause});
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
  const ValidationException(super.message);
}
