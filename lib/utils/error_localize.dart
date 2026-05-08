/// Extension de localisation des [NotesErrorCode] vers des messages
/// utilisateur dans la langue active (FR/EN).
///
/// Pattern d'usage côté UI :
///
/// ```dart
/// } on NotesTechException catch (e) {
///   final code = e.code;
///   _showError(code != null ? code.localize(t) : t.commonErrorWith('$e'));
/// }
/// ```
library;

import '../core/exceptions.dart';
import '../l10n/app_localizations.dart';

extension NotesErrorLocalize on NotesErrorCode {
  /// Renvoie le message utilisateur localisé pour ce code, dans la
  /// langue active de l'app.
  String localize(AppLocalizations t) {
    switch (this) {
      case NotesErrorCode.folderNameRequired:
        return t.errorFolderNameRequired;
      case NotesErrorCode.inboxNotDeletable:
        return t.errorInboxNotDeletable;
      case NotesErrorCode.noteTitleTooLong:
        return t.errorNoteTitleTooLong;
      case NotesErrorCode.vaultAlreadyEnabled:
        return t.errorVaultAlreadyEnabled;
      case NotesErrorCode.vaultPassphraseTooShort:
        return t.errorVaultPassphraseTooShort;
      case NotesErrorCode.vaultPassphraseWrong:
        return t.errorVaultPassphraseWrong;
      case NotesErrorCode.vaultPinTooShort:
        return t.errorVaultPinTooShort;
      case NotesErrorCode.vaultPinNotDigits:
        return t.errorVaultPinNotDigits;
      case NotesErrorCode.vaultPinWrong:
        return t.errorVaultPinWrong;
      case NotesErrorCode.vaultPinWiped:
        return t.errorVaultPinWiped;
      case NotesErrorCode.vaultNotPinVault:
        return t.errorVaultNotPinVault;
      case NotesErrorCode.vaultNotAVault:
        return t.errorVaultNotAVault;
      case NotesErrorCode.vaultLocked:
        return t.errorVaultLocked;
      case NotesErrorCode.vaultEncryptedContentInvalid:
        return t.errorVaultEncryptedContentInvalid;
      case NotesErrorCode.vaultWrapInvalid:
        return t.errorVaultWrapInvalid;
      case NotesErrorCode.gemmaModelNotInstalled:
        return t.errorGemmaModelNotInstalled;
      case NotesErrorCode.gemmaFileNotFound:
        return t.errorGemmaFileNotFound;
      case NotesErrorCode.gemmaFileTooSmall:
        return t.errorGemmaFileTooSmall;
      case NotesErrorCode.gemmaFileTooLarge:
        return t.errorGemmaFileTooLarge;
      case NotesErrorCode.gemmaInitFailed:
        return t.errorGemmaInitFailed;
      case NotesErrorCode.gemmaNotLoaded:
        return t.errorGemmaNotLoaded;
      case NotesErrorCode.gemmaBusy:
        return t.errorGemmaBusy;
      case NotesErrorCode.gemmaHashMismatch:
        return t.errorGemmaHashMismatch;
    }
  }
}
