// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Notes Tech';

  @override
  String get commonCancel => 'Cancel';

  @override
  String get commonOk => 'OK';

  @override
  String get commonClose => 'Close';

  @override
  String get commonDelete => 'Delete';

  @override
  String get commonRemove => 'Remove';

  @override
  String get commonRename => 'Rename';

  @override
  String get commonShare => 'Share';

  @override
  String get commonError => 'Error';

  @override
  String commonErrorWith(String message) {
    return 'Error: $message';
  }

  @override
  String get commonLoading => 'Loading…';

  @override
  String get commonValidate => 'Confirm';

  @override
  String get homeAllNotes => 'All notes';

  @override
  String get homeFolders => 'Folders';

  @override
  String get homeNewNote => 'New note';

  @override
  String get homeSearchHint => 'Search a note';

  @override
  String get homeNoNotes => 'No notes yet';

  @override
  String get homeNoNotesIn => 'No notes in this folder';

  @override
  String get homeStartWriting => 'Tap the + button to create your first note.';

  @override
  String get homeSortMode => 'Sort';

  @override
  String get homeSortRecentFirst => 'Most recent first';

  @override
  String get homeSortOldFirst => 'Oldest first';

  @override
  String get homeSortAlphaAsc => 'A → Z';

  @override
  String get homeSortAlphaDesc => 'Z → A';

  @override
  String get homeFolderInbox => 'Inbox';

  @override
  String get homeUnpin => 'Unpin';

  @override
  String get homeUnfav => 'Remove from favorites';

  @override
  String homeVaultLostBanner(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count vault notes lost their latest changes',
      one: '1 vault note lost its latest changes',
    );
    return '$_temp0 (vault locked during save).';
  }

  @override
  String get drawerTrash => 'Trash';

  @override
  String get trashTitle => 'Trash';

  @override
  String get trashEmptyTitle => 'Trash is empty';

  @override
  String get trashEmptySubtitle =>
      'Deleted notes appear here before permanent removal.';

  @override
  String trashRetentionNotice(int days) {
    return 'Notes in the trash are automatically deleted after $days days.';
  }

  @override
  String get commonRestore => 'Restore';

  @override
  String get trashRestored => 'Note restored';

  @override
  String get trashDeleteForever => 'Delete permanently';

  @override
  String get trashDeleteForeverTitle => 'Delete permanently?';

  @override
  String get trashDeleteForeverBody =>
      'This note will be permanently erased. This cannot be undone.';

  @override
  String get trashDeletedForever => 'Note permanently deleted';

  @override
  String get trashEmptyAll => 'Empty trash';

  @override
  String get trashEmptyAllConfirm =>
      'Permanently delete all notes in the trash? This cannot be undone.';

  @override
  String get homeAnnounceVaultUnlocked => 'Vault unlocked';

  @override
  String get noteUntitled => 'Untitled';

  @override
  String get noteEditorTitle => 'Title';

  @override
  String get noteEditorContent => 'Type your note (Markdown supported)';

  @override
  String get noteEditorSaved => 'Saved';

  @override
  String get noteEditorSaving => 'Saving…';

  @override
  String get noteEditorTooltipPin => 'Pin note';

  @override
  String get noteEditorTooltipFav => 'Mark as favorite';

  @override
  String get noteEditorTooltipInsertLink => 'Insert internal link [[Title]]';

  @override
  String get noteEditorTooltipMore => 'More actions';

  @override
  String get noteEditorTooltipDictate => 'Voice dictation';

  @override
  String get noteEditorTooltipDone => 'Done';

  @override
  String get noteEditorMenuMove => 'Move to folder';

  @override
  String get noteEditorMenuExport => 'Export to Markdown';

  @override
  String get noteEditorMenuTrash => 'Move to trash';

  @override
  String get noteEditorBacklinks => 'Notes linking here';

  @override
  String noteEditorBacklinkDangling(String title) {
    return 'Link to non-existing note: $title';
  }

  @override
  String get noteEditorAnnounceSavedSuccess => 'Note saved';

  @override
  String get searchTitle => 'Search';

  @override
  String get searchHint => 'Keyword, title, or note content…';

  @override
  String get searchEmpty => 'No results';

  @override
  String get searchTryOther => 'Try another keyword.';

  @override
  String get searchClear => 'Clear search';

  @override
  String get settingsTitle => 'Settings';

  @override
  String get settingsSectionAppearance => 'Appearance';

  @override
  String get settingsSectionSecurity => 'Security';

  @override
  String get settingsSectionAbout => 'About';

  @override
  String get settingsLanguage => 'Language';

  @override
  String get settingsLanguageSystem => 'Follow system';

  @override
  String get settingsLanguageFr => 'Français';

  @override
  String get settingsLanguageEn => 'English';

  @override
  String get settingsLanguageChangedFr => 'Langue changée en français';

  @override
  String get settingsLanguageChangedEn => 'Language switched to English';

  @override
  String get settingsTheme => 'Theme';

  @override
  String get settingsThemeSystem => 'Follow system';

  @override
  String get settingsThemeLight => 'Light';

  @override
  String get settingsThemeDark => 'Dark';

  @override
  String get settingsSecureWindow => 'Hide in recent apps';

  @override
  String get settingsSecureWindowSubtitle =>
      'Prevents screenshots and hides the app preview in the Android task switcher.';

  @override
  String get settingsVaultAutoLock => 'Vault auto-lock';

  @override
  String settingsVaultAutoLockMinutes(int n) {
    String _temp0 = intl.Intl.pluralLogic(
      n,
      locale: localeName,
      other: 'minutes',
      one: 'minute',
    );
    return '$n $_temp0';
  }

  @override
  String get settingsVaultAutoLockNever => 'Never';

  @override
  String get settingsExportAll => 'Export all my notes';

  @override
  String get settingsExportSubtitle =>
      'Generates a Markdown ZIP archive organized by folder.';

  @override
  String settingsExportDone(int count) {
    return 'Export complete: $count notes';
  }

  @override
  String settingsExportDonePartial(int count, int skipped) {
    return 'Export complete: $count notes ($skipped skipped in locked vaults)';
  }

  @override
  String exportNoteFromVault(String folder) {
    return 'Note from vault: $folder';
  }

  @override
  String settingsExportError(String message) {
    return 'Export failed: $message';
  }

  @override
  String get settingsPanic => 'Panic mode';

  @override
  String get settingsPanicSubtitle =>
      'Permanently wipes notes, key, models and vaults.';

  @override
  String get settingsAbout => 'About Notes Tech';

  @override
  String get settingsAboutSubtitle => 'Privacy, licenses, support';

  @override
  String get aboutTitle => 'About';

  @override
  String get aboutTagline =>
      'Your notes stay in your pocket. Encrypted, and offline.';

  @override
  String get aboutCheckUpdates => 'Check for updates';

  @override
  String get aboutCheckUpdatesHint =>
      'Opens the releases page on GitHub in your browser — the app itself never connects to the Internet.';

  @override
  String get aboutSectionPrivacy => 'Privacy';

  @override
  String get aboutPrivacyCardTitle => '100% private — zero surveillance';

  @override
  String get aboutPrivacy1 =>
      'No network connection — verifiable in the manifest';

  @override
  String get aboutPrivacy2 => 'No account, no sign-up';

  @override
  String get aboutPrivacy3 => 'No tracker, no advertising';

  @override
  String get aboutPrivacy4 =>
      'Notes encrypted locally (SQLCipher + Android Keystore)';

  @override
  String get aboutPrivacy5 => '“Hide in recent apps” mode available';

  @override
  String get aboutSectionVoice => 'Voice dictation';

  @override
  String get aboutVoice1 =>
      'On-device Whisper (whisper.cpp via files_tech_voice)';

  @override
  String get aboutVoice2 =>
      'Model SHA-256 verified at download and before each load';

  @override
  String get aboutVoice3 =>
      'Captured audio never persisted (wiped after transcription)';

  @override
  String get aboutNoticeTitle => 'How to enable dictation';

  @override
  String get aboutNoticeStep1 =>
      'Settings → Voice dictation → Enable voice dictation.';

  @override
  String get aboutNoticeStep2 =>
      'Choose a model (Whisper Base 57 MB recommended).';

  @override
  String get aboutNoticeStep3 =>
      'Tap “Download to this phone” — the system browser downloads the .bin file to Downloads. Notes Tech still has no Internet permission: it\'s your browser that downloads, not the app.';

  @override
  String get aboutNoticeStep4 =>
      'Tap “Select the .bin file” — the app verifies the cryptographic fingerprint then copies the model to its private area.';

  @override
  String get aboutNoticeStep5 =>
      'In a note, tap the mic icon 🎤 in the top bar. Speak, then tap “Stop”. The transcribed text is inserted at the cursor.';

  @override
  String get aboutSectionLicenses => 'Sources, licenses and open code';

  @override
  String get aboutLinkRepo => 'Notes Tech (this app)';

  @override
  String get aboutLinkVoice => 'files_tech_voice (Whisper STT module)';

  @override
  String get aboutLinkWhisper => 'Source of Whisper models (.bin)';

  @override
  String get aboutLicense =>
      'Apache License 2.0 — open source code, verifiable';

  @override
  String get aboutFree => 'Free — no premium tier, no subscription';

  @override
  String get aboutSectionContact => 'Author & contact';

  @override
  String get aboutContactEmail => 'Email us';

  @override
  String get aboutContactQuestions => 'Questions, suggestions, feedback';

  @override
  String get aboutSectionLegal => 'Legal';

  @override
  String get aboutLegalLink => 'View full legal notice';

  @override
  String get aboutLegalSubtitle =>
      'Publisher, data collected, permissions, rights, license';

  @override
  String get aboutLinkCopied => 'Link copied — paste it in your browser.';

  @override
  String get legalTitle => 'Legal notice';

  @override
  String get legalTabPrivacy => 'Privacy';

  @override
  String get legalTabTerms => 'Terms';

  @override
  String get vaultPassCreateTitle => 'Create a vault';

  @override
  String get vaultPassCreateBody =>
      'Choose a strong passphrase for this folder. Write it down somewhere safe — if you forget it, the locked notes will be unrecoverable.';

  @override
  String get vaultPassField => 'Passphrase';

  @override
  String get vaultPassConfirmField => 'Confirm passphrase';

  @override
  String vaultPassMinLength(int n) {
    return 'Minimum $n characters.';
  }

  @override
  String get vaultPassMismatch => 'The two passphrases do not match.';

  @override
  String get vaultPassWarningLost =>
      'If you forget this passphrase, the locked notes in this folder will be UNRECOVERABLE. Notes Tech does not store the passphrase and cannot regenerate it.';

  @override
  String get vaultPassCreateAction => 'Create vault';

  @override
  String get vaultPassUnlockTitle => 'Unlock vault';

  @override
  String vaultPassUnlockBody(String folder) {
    return 'Enter the passphrase for folder “$folder”.';
  }

  @override
  String get vaultPassWrong => 'Incorrect passphrase.';

  @override
  String get vaultPassDeriving => 'Argon2id derivation in progress…';

  @override
  String get vaultPassUnlockAction => 'Unlock';

  @override
  String get passphraseShowTooltip => 'Show passphrase';

  @override
  String get passphraseHideTooltip => 'Hide passphrase';

  @override
  String get vaultPinCreateTitle => 'Create a PIN vault';

  @override
  String get vaultPinConfirmField => 'Confirm PIN';

  @override
  String get vaultPinMismatch => 'The two PINs do not match.';

  @override
  String vaultPinTooShort(int min, int max) {
    return 'PIN must be $min to $max digits.';
  }

  @override
  String get vaultPinWarningWipe =>
      'Warning: 5 successive PIN failures will permanently wipe the locked notes in this folder.';

  @override
  String get vaultPinUnlockTitle => 'Unlock vault (PIN)';

  @override
  String vaultPinUnlockBody(String folder) {
    return 'PIN for folder “$folder”.';
  }

  @override
  String get vaultPinWrong => 'Incorrect PIN.';

  @override
  String vaultPinAttemptsLeft(int n) {
    return 'Attempts remaining: $n';
  }

  @override
  String get vaultPinWiped => 'Too many attempts — the vault has been wiped.';

  @override
  String vaultPinDigitsAnnounce(int filled, int max) {
    return '$filled digits entered out of $max';
  }

  @override
  String vaultPinKeyLabel(String digit) {
    return 'Key $digit';
  }

  @override
  String get vaultPinKeyDelete => 'Delete last digit';

  @override
  String get vaultModeChoose => 'Choose unlock mode';

  @override
  String get vaultModePassphrase => 'Passphrase';

  @override
  String get vaultModePassphraseDesc =>
      'Recommended. Slower derivation but resistant to off-device bruteforce.';

  @override
  String get vaultModePin => 'PIN (4-6 digits)';

  @override
  String get vaultModePinDesc =>
      'Faster. Auto-wipe after 5 failures. Device-bound security (Keystore).';

  @override
  String get panicConfirmTitle => 'Permanently wipe all data?';

  @override
  String get panicConfirmKeyword => 'WIPE';

  @override
  String get panicConfirmYes => 'Wipe everything';

  @override
  String panicIncomplete(int count) {
    return 'Wipe INCOMPLETE: $count step(s) failed. Some of your data was NOT destroyed. Try again before parting with the device.';
  }

  @override
  String get panicProgress => 'Wiping…';

  @override
  String get panicProgressSubtitle => 'Please wait.';

  @override
  String get panicAnnounceDone => 'Wipe complete';

  @override
  String get panicCompleteTitle => 'Wipe complete';

  @override
  String get panicCompleteBody =>
      'All data has been wiped. Notes Tech restarts as on first launch.';

  @override
  String get panicCompleteClose => 'Close the app';

  @override
  String get panicCompleteFooter =>
      'On next launch, Notes Tech will start over on a blank slate.';

  @override
  String get panicCompleteBullet1 => 'Keystore master key: destroyed';

  @override
  String get panicCompleteBullet2 => 'Notes database: wiped and overwritten';

  @override
  String get panicCompleteBullet3 => 'Voice dictation model: uninstalled';

  @override
  String get panicCompleteBullet4 => 'Preferences: reset';

  @override
  String get panicConfirmDestroyIntro =>
      'You are about to IRREVERSIBLY DESTROY:';

  @override
  String get panicConfirmItem1 =>
      'All your notes (encryption destroyed + file overwritten)';

  @override
  String get panicConfirmItem2 => 'The installed voice dictation model';

  @override
  String get panicConfirmItem3 => 'All preferences and history';

  @override
  String get panicConfirmIrreversible =>
      'This action CANNOT be undone. No backup, no trash, no forensic recovery possible.';

  @override
  String panicConfirmTypePrompt(String keyword) {
    return 'To confirm, type exactly: $keyword';
  }

  @override
  String get panicConfirmFieldLabel => 'Confirmation word';

  @override
  String get folderCreateTitle => 'New folder';

  @override
  String get folderCreateField => 'Folder name';

  @override
  String get folderRenameTitle => 'Rename folder';

  @override
  String get folderRenameField => 'New name';

  @override
  String get folderDeleteTitle => 'Delete folder?';

  @override
  String folderDeleteChoiceBody(String name) {
    return 'What to do with notes from “$name”?';
  }

  @override
  String get drawerRemoveVaultProtection => 'Remove protection';

  @override
  String get drawerRemoveVaultProtectionSubtitle =>
      'Keep the folder, decrypt its notes';

  @override
  String get folderRemoveVaultTitle => 'Remove vault protection?';

  @override
  String folderRemoveVaultBody(String name) {
    return 'The notes in \"$name\" will be decrypted and written to the database in the clear. The folder and its contents are kept, but they will no longer be protected by a passphrase. This cannot be undone: the notes will have existed unencrypted, even if you protect the folder again afterwards.';
  }

  @override
  String get folderRemoveVaultConfirm => 'Decrypt and remove';

  @override
  String folderRemoveVaultDone(int n) {
    String _temp0 = intl.Intl.pluralLogic(
      n,
      locale: localeName,
      other: '$n notes decrypted. This folder is no longer a vault.',
      one: '1 note decrypted. This folder is no longer a vault.',
    );
    return '$_temp0';
  }

  @override
  String folderDeleteVaultChoiceBody(String name) {
    return '\"$name\" is a vault. Moving its notes to the Inbox DECRYPTS every one of them and writes them to the database in the clear, with no passphrase protection. This cannot be undone: they will have existed unencrypted, even if you put them back into a vault afterwards.';
  }

  @override
  String get folderDeletePermanent => 'Delete permanently';

  @override
  String get folderDeleteMoveToInbox => 'Move to Inbox';

  @override
  String get folderDeleteMoveToInboxVault => 'Decrypt and move';

  @override
  String folderDeleteDecryptFailed(int n) {
    return 'Cannot decrypt $n note(s).';
  }

  @override
  String folderDeleteCancelledError(String message) {
    return 'Deletion cancelled: $message';
  }

  @override
  String get folderConvertProgressTitle => 'Converting vault…';

  @override
  String get folderConvertProgressBody => 'Re-encrypting locked notes.';

  @override
  String get drawerHeaderFolders => 'FOLDERS';

  @override
  String get drawerNewFolder => 'New folder';

  @override
  String get drawerFolderOptions => 'Folder options';

  @override
  String get drawerConvertToVault => 'Enable vault';

  @override
  String get drawerConvertToVaultSubtitle =>
      'Lock this folder with a passphrase or PIN';

  @override
  String get drawerLockNow => 'Lock now';

  @override
  String get drawerLockNowSubtitle => 'Re-locks the decrypted vault';

  @override
  String vaultConvertPartialFail(int failed, int total) {
    return '$failed of $total notes could not be converted.';
  }

  @override
  String get vaultConvertSuccess => 'Vault enabled.';

  @override
  String vaultConvertSuccessWithCount(int n) {
    return 'Vault enabled. $n note(s) encrypted.';
  }

  @override
  String vaultConvertImpossible(String message) {
    return 'Conversion failed: $message';
  }

  @override
  String noteEditorOutgoingLinks(int n) {
    return 'Links ($n)';
  }

  @override
  String get noteCardLocked => '🔒 Locked note';

  @override
  String get voiceMicInitializing => 'Initializing microphone…';

  @override
  String get voiceTranscribingHint => 'Please wait…';

  @override
  String get voiceOpenSystemSettings => 'Open settings';

  @override
  String get moveToFolderTitle => 'Move to folder';

  @override
  String get moveToFolderEmpty => 'No other folder available.';

  @override
  String get linkAutocompleteTitle => 'Insert link';

  @override
  String get linkAutocompleteHint => 'Title of the note to link';

  @override
  String get linkAutocompleteEmpty => 'No matching note.';

  @override
  String linkAutocompleteCreateNew(String title) {
    return 'Create a new note “$title”';
  }

  @override
  String get aiChatModelLoaded => 'Model ready';

  @override
  String get voiceSetupTitle => 'Voice dictation';

  @override
  String get voiceSetupSubtitle =>
      'On-device Whisper. Audio is never persisted.';

  @override
  String get voiceSetupEnable => 'Enable voice dictation';

  @override
  String get voiceSetupChooseModel => 'Choose a Whisper model';

  @override
  String get voiceSetupDownload => 'Download to this phone';

  @override
  String get voiceSetupSelectFile => 'Select the .bin file';

  @override
  String get voiceSetupVerifying => 'Verifying fingerprint…';

  @override
  String voiceSetupInstallOk(String name) {
    return 'Model installed: $name';
  }

  @override
  String voiceSetupInstallFail(String message) {
    return 'Install failed: $message';
  }

  @override
  String get voiceSetupRemove => 'Remove installed model';

  @override
  String get voiceRecordingTitle => 'Recording';

  @override
  String get voiceRecordingHint => 'Speak. Tap “Stop” to transcribe.';

  @override
  String get voiceRecordingStop => 'Stop';

  @override
  String get voiceTranscribing => 'Transcribing…';

  @override
  String get voiceTranscribed => 'Text inserted.';

  @override
  String get voicePermissionDenied => 'Microphone permission denied.';

  @override
  String exportShareSubject(int count) {
    return 'Notes Tech — export $count notes';
  }

  @override
  String get errorVaultLocked => 'Vault locked.';

  @override
  String get errorVoiceNoModelInstalled => 'No transcription model installed.';

  @override
  String get errorVoiceStartCaptureFailed =>
      'Failed to start microphone capture.';

  @override
  String get errorVoiceTranscribeFailed => 'Transcription failed.';

  @override
  String get errorVoiceMicCaptureError => 'Microphone capture error.';

  @override
  String homeVaultCreateError(String message) {
    return 'Vault creation failed: $message';
  }

  @override
  String get homeNoteCreatedInInbox => 'Note created in Inbox';

  @override
  String get homeLoadError => 'An error occurred while loading.';

  @override
  String get noteEditorErrorNotFound => 'Note not found';

  @override
  String get noteEditorErrorVaultFolderMissing => 'Vault folder not found';

  @override
  String get noteEditorErrorVaultWiped =>
      'Vault auto-wiped after too many failed attempts. Notes in this folder are permanently lost.';

  @override
  String get noteEditorErrorVaultRelocked =>
      'Vault re-locked. Reopen the note to retry.';

  @override
  String get noteEditorErrorLoadGeneric => 'An error occurred while loading.';

  @override
  String get noteEditorErrorVaultRelockedDuringEdit =>
      'Vault re-locked while editing. Reopen the note to resume.';

  @override
  String get noteEditorErrorSaveFailed => 'Save failed';

  @override
  String get noteEditorCopiedToClipboard => 'Copied to clipboard';

  @override
  String noteEditorExportFailed(String message) {
    return 'Export failed: $message';
  }

  @override
  String get noteEditorMoved => 'Note moved';

  @override
  String noteEditorMoveFailed(String message) {
    return 'Move failed: $message';
  }

  @override
  String get noteEditorExitVaultTitle => 'Move note out of vault?';

  @override
  String get noteEditorExitVaultBody =>
      'The content will be decrypted and stored in cleartext in the database, without password protection. Irreversible — the current note will have transited outside encryption, even if you later move it back into a vault.';

  @override
  String get noteEditorExitVaultConfirm => 'Leave vault';

  @override
  String get noteEditorMenuCopyMarkdown => 'Copy Markdown';

  @override
  String get noteEditorContentHint => 'Write in Markdown… ([[Title]] to link)';

  @override
  String get searchEmptyTitle => 'Type to search';

  @override
  String get searchEmptySubtitleFts => 'Instant 100% local full-text search.';

  @override
  String get searchErrorGeneric => 'An error occurred.';

  @override
  String get voiceSetupAppBarTitle => 'Voice dictation';

  @override
  String get voiceSetupOfflineBanner =>
      '100% offline. Audio is never persisted.';

  @override
  String get voiceSetupHowToTitle => 'How to enable dictation';

  @override
  String get voiceSetupStep1Title => '1. Pick a model';

  @override
  String get voiceSetupStep1Text => 'Whisper Base (57 MB) recommended.';

  @override
  String get voiceSetupStep2Title => '2. Download';

  @override
  String get voiceSetupStep2Text =>
      'Your browser downloads the .bin into /Downloads. Notes Tech still has no Internet permission.';

  @override
  String get voiceSetupStep3Title => '3. Import';

  @override
  String get voiceSetupStep3Text =>
      'Select the downloaded .bin. The app verifies SHA-256 then copies privately.';

  @override
  String get voiceSetupCopyLinkTooltip => 'Copy link';

  @override
  String get voiceSetupLinkCopied => 'Link copied to clipboard';

  @override
  String get voiceSetupPathUnavailable => 'File path unavailable';

  @override
  String get voiceSetupImportErrorTitle => 'Import failed';

  @override
  String voiceSetupChecksumMismatchBody(String message) {
    return 'SHA-256 fingerprint mismatch. File may have been corrupted during download. Details: $message';
  }

  @override
  String get voiceSetupBrowserOpenFailed => 'No browser available';

  @override
  String voiceSetupBrowserOpenError(String message) {
    return 'Cannot open browser: $message';
  }

  @override
  String get voiceSetupCopying => 'Copying…';

  @override
  String get voiceSetupImportInProgress => 'Import in progress, please wait.';

  @override
  String voiceSetupPickerDialogTitle(String modelId) {
    return 'Pick the .bin file for $modelId';
  }

  @override
  String get voiceSetupSecurityFooterLabel => 'Promise';

  @override
  String get voiceSetupSecurityFooterBody =>
      'Audio never persisted, local transcription via whisper.cpp, model SHA-256 verified before each load.';

  @override
  String get errorFolderNameRequired => 'Folder name is required.';

  @override
  String get errorInboxNotDeletable => '“Inbox” folder cannot be deleted.';

  @override
  String get errorNoteTitleTooLong => 'Title too long (max 200 characters).';

  @override
  String get errorVaultAlreadyEnabled => 'This folder is already a vault.';

  @override
  String get errorVaultPassphraseTooShort =>
      'Passphrase too short (minimum 8 characters).';

  @override
  String get errorVaultPassphraseWrong => 'Wrong passphrase.';

  @override
  String get errorVaultPinTooShort => 'Invalid PIN: 4 to 6 digits.';

  @override
  String get errorVaultPinNotDigits => 'Invalid PIN: digits only.';

  @override
  String get errorVaultPinWrong => 'Wrong PIN.';

  @override
  String get errorVaultPinWiped =>
      'Vault self-destructed after too many failed attempts.';

  @override
  String get errorVaultNotPinVault => 'This folder is not a PIN vault.';

  @override
  String get errorVaultNotAVault => 'This folder is not a vault.';

  @override
  String get errorVaultEncryptedContentInvalid =>
      'Encrypted content invalid (too short).';

  @override
  String get errorVaultWrapInvalid =>
      'Encrypted wrap invalid (truncated GCM tag).';

  @override
  String get splashTagline => 'Maximum protection\nfor your notes.';

  @override
  String get splashSkipHint => 'Tap the screen to continue';

  @override
  String get splashLogoContentDescription => 'Notes Tech logo';

  @override
  String get splashSemanticsLabel => 'Notes Tech splash screen';
}
