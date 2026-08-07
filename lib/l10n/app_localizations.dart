import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_fr.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('fr'),
  ];

  /// No description provided for @appTitle.
  ///
  /// In en, this message translates to:
  /// **'Notes Tech'**
  String get appTitle;

  /// No description provided for @commonCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get commonCancel;

  /// No description provided for @commonOk.
  ///
  /// In en, this message translates to:
  /// **'OK'**
  String get commonOk;

  /// No description provided for @commonClose.
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get commonClose;

  /// No description provided for @commonDelete.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get commonDelete;

  /// No description provided for @commonRemove.
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get commonRemove;

  /// No description provided for @commonRename.
  ///
  /// In en, this message translates to:
  /// **'Rename'**
  String get commonRename;

  /// No description provided for @commonShare.
  ///
  /// In en, this message translates to:
  /// **'Share'**
  String get commonShare;

  /// No description provided for @commonError.
  ///
  /// In en, this message translates to:
  /// **'Error'**
  String get commonError;

  /// No description provided for @commonErrorWith.
  ///
  /// In en, this message translates to:
  /// **'Error: {message}'**
  String commonErrorWith(String message);

  /// No description provided for @commonLoading.
  ///
  /// In en, this message translates to:
  /// **'Loading…'**
  String get commonLoading;

  /// No description provided for @commonValidate.
  ///
  /// In en, this message translates to:
  /// **'Confirm'**
  String get commonValidate;

  /// No description provided for @homeAllNotes.
  ///
  /// In en, this message translates to:
  /// **'All notes'**
  String get homeAllNotes;

  /// No description provided for @homeFolders.
  ///
  /// In en, this message translates to:
  /// **'Folders'**
  String get homeFolders;

  /// No description provided for @homeNewNote.
  ///
  /// In en, this message translates to:
  /// **'New note'**
  String get homeNewNote;

  /// No description provided for @homeSearchHint.
  ///
  /// In en, this message translates to:
  /// **'Search a note'**
  String get homeSearchHint;

  /// No description provided for @homeNoNotes.
  ///
  /// In en, this message translates to:
  /// **'No notes yet'**
  String get homeNoNotes;

  /// No description provided for @homeNoNotesIn.
  ///
  /// In en, this message translates to:
  /// **'No notes in this folder'**
  String get homeNoNotesIn;

  /// No description provided for @homeStartWriting.
  ///
  /// In en, this message translates to:
  /// **'Tap the + button to create your first note.'**
  String get homeStartWriting;

  /// No description provided for @homeSortMode.
  ///
  /// In en, this message translates to:
  /// **'Sort'**
  String get homeSortMode;

  /// No description provided for @homeSortRecentFirst.
  ///
  /// In en, this message translates to:
  /// **'Most recent first'**
  String get homeSortRecentFirst;

  /// No description provided for @homeSortOldFirst.
  ///
  /// In en, this message translates to:
  /// **'Oldest first'**
  String get homeSortOldFirst;

  /// No description provided for @homeSortAlphaAsc.
  ///
  /// In en, this message translates to:
  /// **'A → Z'**
  String get homeSortAlphaAsc;

  /// No description provided for @homeSortAlphaDesc.
  ///
  /// In en, this message translates to:
  /// **'Z → A'**
  String get homeSortAlphaDesc;

  /// No description provided for @homeFolderInbox.
  ///
  /// In en, this message translates to:
  /// **'Inbox'**
  String get homeFolderInbox;

  /// No description provided for @homeUnpin.
  ///
  /// In en, this message translates to:
  /// **'Unpin'**
  String get homeUnpin;

  /// No description provided for @homeUnfav.
  ///
  /// In en, this message translates to:
  /// **'Remove from favorites'**
  String get homeUnfav;

  /// No description provided for @homeVaultLostBanner.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 vault note lost its latest changes} other{{count} vault notes lost their latest changes}} (vault locked during save).'**
  String homeVaultLostBanner(int count);

  /// No description provided for @drawerTrash.
  ///
  /// In en, this message translates to:
  /// **'Trash'**
  String get drawerTrash;

  /// No description provided for @trashTitle.
  ///
  /// In en, this message translates to:
  /// **'Trash'**
  String get trashTitle;

  /// No description provided for @trashEmptyTitle.
  ///
  /// In en, this message translates to:
  /// **'Trash is empty'**
  String get trashEmptyTitle;

  /// No description provided for @trashEmptySubtitle.
  ///
  /// In en, this message translates to:
  /// **'Deleted notes appear here before permanent removal.'**
  String get trashEmptySubtitle;

  /// No description provided for @trashRetentionNotice.
  ///
  /// In en, this message translates to:
  /// **'Notes in the trash are automatically deleted after {days} days.'**
  String trashRetentionNotice(int days);

  /// No description provided for @commonRestore.
  ///
  /// In en, this message translates to:
  /// **'Restore'**
  String get commonRestore;

  /// No description provided for @trashRestored.
  ///
  /// In en, this message translates to:
  /// **'Note restored'**
  String get trashRestored;

  /// No description provided for @trashDeleteForever.
  ///
  /// In en, this message translates to:
  /// **'Delete permanently'**
  String get trashDeleteForever;

  /// No description provided for @trashDeleteForeverTitle.
  ///
  /// In en, this message translates to:
  /// **'Delete permanently?'**
  String get trashDeleteForeverTitle;

  /// No description provided for @trashDeleteForeverBody.
  ///
  /// In en, this message translates to:
  /// **'This note will be permanently erased. This cannot be undone.'**
  String get trashDeleteForeverBody;

  /// No description provided for @trashDeletedForever.
  ///
  /// In en, this message translates to:
  /// **'Note permanently deleted'**
  String get trashDeletedForever;

  /// No description provided for @trashEmptyAll.
  ///
  /// In en, this message translates to:
  /// **'Empty trash'**
  String get trashEmptyAll;

  /// No description provided for @trashEmptyAllConfirm.
  ///
  /// In en, this message translates to:
  /// **'Permanently delete all notes in the trash? This cannot be undone.'**
  String get trashEmptyAllConfirm;

  /// No description provided for @homeAnnounceVaultUnlocked.
  ///
  /// In en, this message translates to:
  /// **'Vault unlocked'**
  String get homeAnnounceVaultUnlocked;

  /// No description provided for @noteUntitled.
  ///
  /// In en, this message translates to:
  /// **'Untitled'**
  String get noteUntitled;

  /// No description provided for @noteEditorTitle.
  ///
  /// In en, this message translates to:
  /// **'Title'**
  String get noteEditorTitle;

  /// No description provided for @noteEditorContent.
  ///
  /// In en, this message translates to:
  /// **'Type your note (Markdown supported)'**
  String get noteEditorContent;

  /// No description provided for @noteEditorSaved.
  ///
  /// In en, this message translates to:
  /// **'Saved'**
  String get noteEditorSaved;

  /// No description provided for @noteEditorSaving.
  ///
  /// In en, this message translates to:
  /// **'Saving…'**
  String get noteEditorSaving;

  /// No description provided for @noteEditorTooltipPin.
  ///
  /// In en, this message translates to:
  /// **'Pin note'**
  String get noteEditorTooltipPin;

  /// No description provided for @noteEditorTooltipFav.
  ///
  /// In en, this message translates to:
  /// **'Mark as favorite'**
  String get noteEditorTooltipFav;

  /// No description provided for @noteEditorTooltipInsertLink.
  ///
  /// In en, this message translates to:
  /// **'Insert internal link [[Title]]'**
  String get noteEditorTooltipInsertLink;

  /// No description provided for @noteEditorTooltipMore.
  ///
  /// In en, this message translates to:
  /// **'More actions'**
  String get noteEditorTooltipMore;

  /// No description provided for @noteEditorTooltipDictate.
  ///
  /// In en, this message translates to:
  /// **'Voice dictation'**
  String get noteEditorTooltipDictate;

  /// No description provided for @noteEditorTooltipDone.
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get noteEditorTooltipDone;

  /// No description provided for @noteEditorMenuMove.
  ///
  /// In en, this message translates to:
  /// **'Move to folder'**
  String get noteEditorMenuMove;

  /// No description provided for @noteEditorMenuExport.
  ///
  /// In en, this message translates to:
  /// **'Export to Markdown'**
  String get noteEditorMenuExport;

  /// No description provided for @noteEditorMenuTrash.
  ///
  /// In en, this message translates to:
  /// **'Move to trash'**
  String get noteEditorMenuTrash;

  /// No description provided for @noteEditorBacklinks.
  ///
  /// In en, this message translates to:
  /// **'Notes linking here'**
  String get noteEditorBacklinks;

  /// No description provided for @noteEditorBacklinkDangling.
  ///
  /// In en, this message translates to:
  /// **'Link to non-existing note: {title}'**
  String noteEditorBacklinkDangling(String title);

  /// No description provided for @noteEditorAnnounceSavedSuccess.
  ///
  /// In en, this message translates to:
  /// **'Note saved'**
  String get noteEditorAnnounceSavedSuccess;

  /// No description provided for @searchTitle.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get searchTitle;

  /// No description provided for @searchHint.
  ///
  /// In en, this message translates to:
  /// **'Keyword, title, or note content…'**
  String get searchHint;

  /// No description provided for @searchEmpty.
  ///
  /// In en, this message translates to:
  /// **'No results'**
  String get searchEmpty;

  /// No description provided for @searchTryOther.
  ///
  /// In en, this message translates to:
  /// **'Try another keyword.'**
  String get searchTryOther;

  /// No description provided for @searchClear.
  ///
  /// In en, this message translates to:
  /// **'Clear search'**
  String get searchClear;

  /// No description provided for @settingsTitle.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settingsTitle;

  /// No description provided for @settingsSectionAppearance.
  ///
  /// In en, this message translates to:
  /// **'Appearance'**
  String get settingsSectionAppearance;

  /// No description provided for @settingsSectionSecurity.
  ///
  /// In en, this message translates to:
  /// **'Security'**
  String get settingsSectionSecurity;

  /// No description provided for @settingsSectionAbout.
  ///
  /// In en, this message translates to:
  /// **'About'**
  String get settingsSectionAbout;

  /// No description provided for @settingsLanguage.
  ///
  /// In en, this message translates to:
  /// **'Language'**
  String get settingsLanguage;

  /// No description provided for @settingsLanguageSystem.
  ///
  /// In en, this message translates to:
  /// **'Follow system'**
  String get settingsLanguageSystem;

  /// No description provided for @settingsLanguageFr.
  ///
  /// In en, this message translates to:
  /// **'Français'**
  String get settingsLanguageFr;

  /// No description provided for @settingsLanguageEn.
  ///
  /// In en, this message translates to:
  /// **'English'**
  String get settingsLanguageEn;

  /// No description provided for @settingsLanguageChangedFr.
  ///
  /// In en, this message translates to:
  /// **'Langue changée en français'**
  String get settingsLanguageChangedFr;

  /// No description provided for @settingsLanguageChangedEn.
  ///
  /// In en, this message translates to:
  /// **'Language switched to English'**
  String get settingsLanguageChangedEn;

  /// No description provided for @settingsTheme.
  ///
  /// In en, this message translates to:
  /// **'Theme'**
  String get settingsTheme;

  /// No description provided for @settingsThemeSystem.
  ///
  /// In en, this message translates to:
  /// **'Follow system'**
  String get settingsThemeSystem;

  /// No description provided for @settingsThemeLight.
  ///
  /// In en, this message translates to:
  /// **'Light'**
  String get settingsThemeLight;

  /// No description provided for @settingsThemeDark.
  ///
  /// In en, this message translates to:
  /// **'Dark'**
  String get settingsThemeDark;

  /// No description provided for @settingsSecureWindow.
  ///
  /// In en, this message translates to:
  /// **'Hide in recent apps'**
  String get settingsSecureWindow;

  /// No description provided for @settingsSecureWindowSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Prevents screenshots and hides the app preview in the Android task switcher.'**
  String get settingsSecureWindowSubtitle;

  /// No description provided for @settingsVaultAutoLock.
  ///
  /// In en, this message translates to:
  /// **'Vault auto-lock'**
  String get settingsVaultAutoLock;

  /// No description provided for @settingsVaultAutoLockMinutes.
  ///
  /// In en, this message translates to:
  /// **'{n} {n, plural, =1{minute} other{minutes}}'**
  String settingsVaultAutoLockMinutes(int n);

  /// No description provided for @settingsVaultAutoLockNever.
  ///
  /// In en, this message translates to:
  /// **'Never'**
  String get settingsVaultAutoLockNever;

  /// No description provided for @settingsExportAll.
  ///
  /// In en, this message translates to:
  /// **'Export all my notes'**
  String get settingsExportAll;

  /// No description provided for @settingsExportSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Generates a Markdown ZIP archive organized by folder.'**
  String get settingsExportSubtitle;

  /// No description provided for @settingsExportDone.
  ///
  /// In en, this message translates to:
  /// **'Export complete: {count} notes'**
  String settingsExportDone(int count);

  /// No description provided for @settingsExportDonePartial.
  ///
  /// In en, this message translates to:
  /// **'Export complete: {count} notes ({skipped} skipped in locked vaults)'**
  String settingsExportDonePartial(int count, int skipped);

  /// No description provided for @exportNoteFromVault.
  ///
  /// In en, this message translates to:
  /// **'Note from vault: {folder}'**
  String exportNoteFromVault(String folder);

  /// No description provided for @settingsExportError.
  ///
  /// In en, this message translates to:
  /// **'Export failed: {message}'**
  String settingsExportError(String message);

  /// No description provided for @settingsPanic.
  ///
  /// In en, this message translates to:
  /// **'Panic mode'**
  String get settingsPanic;

  /// No description provided for @settingsPanicSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Permanently wipes notes, key, models and vaults.'**
  String get settingsPanicSubtitle;

  /// No description provided for @settingsAbout.
  ///
  /// In en, this message translates to:
  /// **'About Notes Tech'**
  String get settingsAbout;

  /// No description provided for @settingsAboutSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Privacy, licenses, support'**
  String get settingsAboutSubtitle;

  /// No description provided for @aboutTitle.
  ///
  /// In en, this message translates to:
  /// **'About'**
  String get aboutTitle;

  /// No description provided for @aboutTagline.
  ///
  /// In en, this message translates to:
  /// **'Your notes stay in your pocket. Encrypted, and offline.'**
  String get aboutTagline;

  /// No description provided for @aboutCheckUpdates.
  ///
  /// In en, this message translates to:
  /// **'Check for updates'**
  String get aboutCheckUpdates;

  /// No description provided for @aboutCheckUpdatesHint.
  ///
  /// In en, this message translates to:
  /// **'Opens the releases page on GitHub in your browser — the app itself never connects to the Internet.'**
  String get aboutCheckUpdatesHint;

  /// No description provided for @aboutSectionPrivacy.
  ///
  /// In en, this message translates to:
  /// **'Privacy'**
  String get aboutSectionPrivacy;

  /// No description provided for @aboutPrivacyCardTitle.
  ///
  /// In en, this message translates to:
  /// **'100% private — zero surveillance'**
  String get aboutPrivacyCardTitle;

  /// No description provided for @aboutPrivacy1.
  ///
  /// In en, this message translates to:
  /// **'No network connection — verifiable in the manifest'**
  String get aboutPrivacy1;

  /// No description provided for @aboutPrivacy2.
  ///
  /// In en, this message translates to:
  /// **'No account, no sign-up'**
  String get aboutPrivacy2;

  /// No description provided for @aboutPrivacy3.
  ///
  /// In en, this message translates to:
  /// **'No tracker, no advertising'**
  String get aboutPrivacy3;

  /// No description provided for @aboutPrivacy4.
  ///
  /// In en, this message translates to:
  /// **'Notes encrypted locally (SQLCipher + Android Keystore)'**
  String get aboutPrivacy4;

  /// No description provided for @aboutPrivacy5.
  ///
  /// In en, this message translates to:
  /// **'“Hide in recent apps” mode available'**
  String get aboutPrivacy5;

  /// No description provided for @aboutSectionVoice.
  ///
  /// In en, this message translates to:
  /// **'Voice dictation'**
  String get aboutSectionVoice;

  /// No description provided for @aboutVoice1.
  ///
  /// In en, this message translates to:
  /// **'On-device Whisper (whisper.cpp via files_tech_voice)'**
  String get aboutVoice1;

  /// No description provided for @aboutVoice2.
  ///
  /// In en, this message translates to:
  /// **'Model SHA-256 verified at download and before each load'**
  String get aboutVoice2;

  /// No description provided for @aboutVoice3.
  ///
  /// In en, this message translates to:
  /// **'Captured audio never persisted (wiped after transcription)'**
  String get aboutVoice3;

  /// No description provided for @aboutNoticeTitle.
  ///
  /// In en, this message translates to:
  /// **'How to enable dictation'**
  String get aboutNoticeTitle;

  /// No description provided for @aboutNoticeStep1.
  ///
  /// In en, this message translates to:
  /// **'Settings → Voice dictation → Enable voice dictation.'**
  String get aboutNoticeStep1;

  /// No description provided for @aboutNoticeStep2.
  ///
  /// In en, this message translates to:
  /// **'Choose a model (Whisper Base 57 MB recommended).'**
  String get aboutNoticeStep2;

  /// No description provided for @aboutNoticeStep3.
  ///
  /// In en, this message translates to:
  /// **'Tap “Download to this phone” — the system browser downloads the .bin file to Downloads. Notes Tech still has no Internet permission: it\'s your browser that downloads, not the app.'**
  String get aboutNoticeStep3;

  /// No description provided for @aboutNoticeStep4.
  ///
  /// In en, this message translates to:
  /// **'Tap “Select the .bin file” — the app verifies the cryptographic fingerprint then copies the model to its private area.'**
  String get aboutNoticeStep4;

  /// No description provided for @aboutNoticeStep5.
  ///
  /// In en, this message translates to:
  /// **'In a note, tap the mic icon 🎤 in the top bar. Speak, then tap “Stop”. The transcribed text is inserted at the cursor.'**
  String get aboutNoticeStep5;

  /// No description provided for @aboutSectionLicenses.
  ///
  /// In en, this message translates to:
  /// **'Sources, licenses and open code'**
  String get aboutSectionLicenses;

  /// No description provided for @aboutLinkRepo.
  ///
  /// In en, this message translates to:
  /// **'Notes Tech (this app)'**
  String get aboutLinkRepo;

  /// No description provided for @aboutLinkVoice.
  ///
  /// In en, this message translates to:
  /// **'files_tech_voice (Whisper STT module)'**
  String get aboutLinkVoice;

  /// No description provided for @aboutLinkWhisper.
  ///
  /// In en, this message translates to:
  /// **'Source of Whisper models (.bin)'**
  String get aboutLinkWhisper;

  /// No description provided for @aboutLicense.
  ///
  /// In en, this message translates to:
  /// **'Apache License 2.0 — open source code, verifiable'**
  String get aboutLicense;

  /// No description provided for @aboutFree.
  ///
  /// In en, this message translates to:
  /// **'Free — no premium tier, no subscription'**
  String get aboutFree;

  /// No description provided for @aboutSectionContact.
  ///
  /// In en, this message translates to:
  /// **'Author & contact'**
  String get aboutSectionContact;

  /// No description provided for @aboutContactEmail.
  ///
  /// In en, this message translates to:
  /// **'Email us'**
  String get aboutContactEmail;

  /// No description provided for @aboutContactQuestions.
  ///
  /// In en, this message translates to:
  /// **'Questions, suggestions, feedback'**
  String get aboutContactQuestions;

  /// No description provided for @aboutSectionLegal.
  ///
  /// In en, this message translates to:
  /// **'Legal'**
  String get aboutSectionLegal;

  /// No description provided for @aboutLegalLink.
  ///
  /// In en, this message translates to:
  /// **'View full legal notice'**
  String get aboutLegalLink;

  /// No description provided for @aboutLegalSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Publisher, data collected, permissions, rights, license'**
  String get aboutLegalSubtitle;

  /// No description provided for @aboutLinkCopied.
  ///
  /// In en, this message translates to:
  /// **'Link copied — paste it in your browser.'**
  String get aboutLinkCopied;

  /// No description provided for @legalTitle.
  ///
  /// In en, this message translates to:
  /// **'Legal notice'**
  String get legalTitle;

  /// No description provided for @legalTabPrivacy.
  ///
  /// In en, this message translates to:
  /// **'Privacy'**
  String get legalTabPrivacy;

  /// No description provided for @legalTabTerms.
  ///
  /// In en, this message translates to:
  /// **'Terms'**
  String get legalTabTerms;

  /// No description provided for @vaultPassCreateTitle.
  ///
  /// In en, this message translates to:
  /// **'Create a vault'**
  String get vaultPassCreateTitle;

  /// No description provided for @vaultPassCreateBody.
  ///
  /// In en, this message translates to:
  /// **'Choose a strong passphrase for this folder. Write it down somewhere safe — if you forget it, the locked notes will be unrecoverable.'**
  String get vaultPassCreateBody;

  /// No description provided for @vaultPassField.
  ///
  /// In en, this message translates to:
  /// **'Passphrase'**
  String get vaultPassField;

  /// No description provided for @vaultPassConfirmField.
  ///
  /// In en, this message translates to:
  /// **'Confirm passphrase'**
  String get vaultPassConfirmField;

  /// No description provided for @vaultPassMinLength.
  ///
  /// In en, this message translates to:
  /// **'Minimum {n} characters.'**
  String vaultPassMinLength(int n);

  /// No description provided for @vaultPassMismatch.
  ///
  /// In en, this message translates to:
  /// **'The two passphrases do not match.'**
  String get vaultPassMismatch;

  /// No description provided for @vaultPassWarningLost.
  ///
  /// In en, this message translates to:
  /// **'If you forget this passphrase, the locked notes in this folder will be UNRECOVERABLE. Notes Tech does not store the passphrase and cannot regenerate it.'**
  String get vaultPassWarningLost;

  /// No description provided for @vaultPassCreateAction.
  ///
  /// In en, this message translates to:
  /// **'Create vault'**
  String get vaultPassCreateAction;

  /// No description provided for @vaultPassUnlockTitle.
  ///
  /// In en, this message translates to:
  /// **'Unlock vault'**
  String get vaultPassUnlockTitle;

  /// No description provided for @vaultPassUnlockBody.
  ///
  /// In en, this message translates to:
  /// **'Enter the passphrase for folder “{folder}”.'**
  String vaultPassUnlockBody(String folder);

  /// No description provided for @vaultPassWrong.
  ///
  /// In en, this message translates to:
  /// **'Incorrect passphrase.'**
  String get vaultPassWrong;

  /// No description provided for @vaultPassDeriving.
  ///
  /// In en, this message translates to:
  /// **'Argon2id derivation in progress…'**
  String get vaultPassDeriving;

  /// No description provided for @vaultPassUnlockAction.
  ///
  /// In en, this message translates to:
  /// **'Unlock'**
  String get vaultPassUnlockAction;

  /// No description provided for @passphraseShowTooltip.
  ///
  /// In en, this message translates to:
  /// **'Show passphrase'**
  String get passphraseShowTooltip;

  /// No description provided for @passphraseHideTooltip.
  ///
  /// In en, this message translates to:
  /// **'Hide passphrase'**
  String get passphraseHideTooltip;

  /// No description provided for @vaultPinCreateTitle.
  ///
  /// In en, this message translates to:
  /// **'Create a PIN vault'**
  String get vaultPinCreateTitle;

  /// No description provided for @vaultPinConfirmField.
  ///
  /// In en, this message translates to:
  /// **'Confirm PIN'**
  String get vaultPinConfirmField;

  /// No description provided for @vaultPinMismatch.
  ///
  /// In en, this message translates to:
  /// **'The two PINs do not match.'**
  String get vaultPinMismatch;

  /// No description provided for @vaultPinTooShort.
  ///
  /// In en, this message translates to:
  /// **'PIN must be {min} to {max} digits.'**
  String vaultPinTooShort(int min, int max);

  /// No description provided for @vaultPinWarningWipe.
  ///
  /// In en, this message translates to:
  /// **'Warning: 5 successive PIN failures will permanently wipe the locked notes in this folder.'**
  String get vaultPinWarningWipe;

  /// No description provided for @vaultPinUnlockTitle.
  ///
  /// In en, this message translates to:
  /// **'Unlock vault (PIN)'**
  String get vaultPinUnlockTitle;

  /// No description provided for @vaultPinUnlockBody.
  ///
  /// In en, this message translates to:
  /// **'PIN for folder “{folder}”.'**
  String vaultPinUnlockBody(String folder);

  /// No description provided for @vaultPinWrong.
  ///
  /// In en, this message translates to:
  /// **'Incorrect PIN.'**
  String get vaultPinWrong;

  /// No description provided for @vaultPinAttemptsLeft.
  ///
  /// In en, this message translates to:
  /// **'Attempts remaining: {n}'**
  String vaultPinAttemptsLeft(int n);

  /// No description provided for @vaultPinWiped.
  ///
  /// In en, this message translates to:
  /// **'Too many attempts — the vault has been wiped.'**
  String get vaultPinWiped;

  /// No description provided for @vaultPinDigitsAnnounce.
  ///
  /// In en, this message translates to:
  /// **'{filled} digits entered out of {max}'**
  String vaultPinDigitsAnnounce(int filled, int max);

  /// No description provided for @vaultPinKeyLabel.
  ///
  /// In en, this message translates to:
  /// **'Key {digit}'**
  String vaultPinKeyLabel(String digit);

  /// No description provided for @vaultPinKeyDelete.
  ///
  /// In en, this message translates to:
  /// **'Delete last digit'**
  String get vaultPinKeyDelete;

  /// No description provided for @vaultModeChoose.
  ///
  /// In en, this message translates to:
  /// **'Choose unlock mode'**
  String get vaultModeChoose;

  /// No description provided for @vaultModePassphrase.
  ///
  /// In en, this message translates to:
  /// **'Passphrase'**
  String get vaultModePassphrase;

  /// No description provided for @vaultModePassphraseDesc.
  ///
  /// In en, this message translates to:
  /// **'Recommended. Slower derivation but resistant to off-device bruteforce.'**
  String get vaultModePassphraseDesc;

  /// No description provided for @vaultModePin.
  ///
  /// In en, this message translates to:
  /// **'PIN (4-6 digits)'**
  String get vaultModePin;

  /// No description provided for @vaultModePinDesc.
  ///
  /// In en, this message translates to:
  /// **'Faster. Auto-wipe after 5 failures. Device-bound security (Keystore).'**
  String get vaultModePinDesc;

  /// No description provided for @panicConfirmTitle.
  ///
  /// In en, this message translates to:
  /// **'Permanently wipe all data?'**
  String get panicConfirmTitle;

  /// No description provided for @panicConfirmKeyword.
  ///
  /// In en, this message translates to:
  /// **'WIPE'**
  String get panicConfirmKeyword;

  /// No description provided for @panicConfirmYes.
  ///
  /// In en, this message translates to:
  /// **'Wipe everything'**
  String get panicConfirmYes;

  /// No description provided for @panicProgress.
  ///
  /// In en, this message translates to:
  /// **'Wiping…'**
  String get panicProgress;

  /// No description provided for @panicProgressSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Please wait.'**
  String get panicProgressSubtitle;

  /// No description provided for @panicAnnounceDone.
  ///
  /// In en, this message translates to:
  /// **'Wipe complete'**
  String get panicAnnounceDone;

  /// No description provided for @panicCompleteTitle.
  ///
  /// In en, this message translates to:
  /// **'Wipe complete'**
  String get panicCompleteTitle;

  /// No description provided for @panicCompleteBody.
  ///
  /// In en, this message translates to:
  /// **'All data has been wiped. Notes Tech restarts as on first launch.'**
  String get panicCompleteBody;

  /// No description provided for @panicCompleteClose.
  ///
  /// In en, this message translates to:
  /// **'Close the app'**
  String get panicCompleteClose;

  /// No description provided for @panicCompleteFooter.
  ///
  /// In en, this message translates to:
  /// **'On next launch, Notes Tech will start over on a blank slate.'**
  String get panicCompleteFooter;

  /// No description provided for @panicCompleteBullet1.
  ///
  /// In en, this message translates to:
  /// **'Keystore master key: destroyed'**
  String get panicCompleteBullet1;

  /// No description provided for @panicCompleteBullet2.
  ///
  /// In en, this message translates to:
  /// **'Notes database: wiped and overwritten'**
  String get panicCompleteBullet2;

  /// No description provided for @panicCompleteBullet3.
  ///
  /// In en, this message translates to:
  /// **'Voice dictation model: uninstalled'**
  String get panicCompleteBullet3;

  /// No description provided for @panicCompleteBullet4.
  ///
  /// In en, this message translates to:
  /// **'Preferences: reset'**
  String get panicCompleteBullet4;

  /// No description provided for @panicConfirmDestroyIntro.
  ///
  /// In en, this message translates to:
  /// **'You are about to IRREVERSIBLY DESTROY:'**
  String get panicConfirmDestroyIntro;

  /// No description provided for @panicConfirmItem1.
  ///
  /// In en, this message translates to:
  /// **'All your notes (encryption destroyed + file overwritten)'**
  String get panicConfirmItem1;

  /// No description provided for @panicConfirmItem2.
  ///
  /// In en, this message translates to:
  /// **'The installed voice dictation model'**
  String get panicConfirmItem2;

  /// No description provided for @panicConfirmItem3.
  ///
  /// In en, this message translates to:
  /// **'All preferences and history'**
  String get panicConfirmItem3;

  /// No description provided for @panicConfirmIrreversible.
  ///
  /// In en, this message translates to:
  /// **'This action CANNOT be undone. No backup, no trash, no forensic recovery possible.'**
  String get panicConfirmIrreversible;

  /// No description provided for @panicConfirmTypePrompt.
  ///
  /// In en, this message translates to:
  /// **'To confirm, type exactly: {keyword}'**
  String panicConfirmTypePrompt(String keyword);

  /// No description provided for @panicConfirmFieldLabel.
  ///
  /// In en, this message translates to:
  /// **'Confirmation word'**
  String get panicConfirmFieldLabel;

  /// No description provided for @folderCreateTitle.
  ///
  /// In en, this message translates to:
  /// **'New folder'**
  String get folderCreateTitle;

  /// No description provided for @folderCreateField.
  ///
  /// In en, this message translates to:
  /// **'Folder name'**
  String get folderCreateField;

  /// No description provided for @folderRenameTitle.
  ///
  /// In en, this message translates to:
  /// **'Rename folder'**
  String get folderRenameTitle;

  /// No description provided for @folderRenameField.
  ///
  /// In en, this message translates to:
  /// **'New name'**
  String get folderRenameField;

  /// No description provided for @folderDeleteTitle.
  ///
  /// In en, this message translates to:
  /// **'Delete folder?'**
  String get folderDeleteTitle;

  /// No description provided for @folderDeleteChoiceBody.
  ///
  /// In en, this message translates to:
  /// **'What to do with notes from “{name}”?'**
  String folderDeleteChoiceBody(String name);

  /// No description provided for @drawerRemoveVaultProtection.
  ///
  /// In en, this message translates to:
  /// **'Remove protection'**
  String get drawerRemoveVaultProtection;

  /// No description provided for @drawerRemoveVaultProtectionSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Keep the folder, decrypt its notes'**
  String get drawerRemoveVaultProtectionSubtitle;

  /// No description provided for @folderRemoveVaultTitle.
  ///
  /// In en, this message translates to:
  /// **'Remove vault protection?'**
  String get folderRemoveVaultTitle;

  /// No description provided for @folderRemoveVaultBody.
  ///
  /// In en, this message translates to:
  /// **'The notes in \"{name}\" will be decrypted and written to the database in the clear. The folder and its contents are kept, but they will no longer be protected by a passphrase. This cannot be undone: the notes will have existed unencrypted, even if you protect the folder again afterwards.'**
  String folderRemoveVaultBody(String name);

  /// No description provided for @folderRemoveVaultConfirm.
  ///
  /// In en, this message translates to:
  /// **'Decrypt and remove'**
  String get folderRemoveVaultConfirm;

  /// No description provided for @folderRemoveVaultDone.
  ///
  /// In en, this message translates to:
  /// **'{n, plural, =1{1 note decrypted. This folder is no longer a vault.} other{{n} notes decrypted. This folder is no longer a vault.}}'**
  String folderRemoveVaultDone(int n);

  /// No description provided for @folderDeleteVaultChoiceBody.
  ///
  /// In en, this message translates to:
  /// **'\"{name}\" is a vault. Moving its notes to the Inbox DECRYPTS every one of them and writes them to the database in the clear, with no passphrase protection. This cannot be undone: they will have existed unencrypted, even if you put them back into a vault afterwards.'**
  String folderDeleteVaultChoiceBody(String name);

  /// No description provided for @folderDeletePermanent.
  ///
  /// In en, this message translates to:
  /// **'Delete permanently'**
  String get folderDeletePermanent;

  /// No description provided for @folderDeleteMoveToInbox.
  ///
  /// In en, this message translates to:
  /// **'Move to Inbox'**
  String get folderDeleteMoveToInbox;

  /// No description provided for @folderDeleteMoveToInboxVault.
  ///
  /// In en, this message translates to:
  /// **'Decrypt and move'**
  String get folderDeleteMoveToInboxVault;

  /// No description provided for @folderDeleteDecryptFailed.
  ///
  /// In en, this message translates to:
  /// **'Cannot decrypt {n} note(s).'**
  String folderDeleteDecryptFailed(int n);

  /// No description provided for @folderDeleteCancelledError.
  ///
  /// In en, this message translates to:
  /// **'Deletion cancelled: {message}'**
  String folderDeleteCancelledError(String message);

  /// No description provided for @folderConvertProgressTitle.
  ///
  /// In en, this message translates to:
  /// **'Converting vault…'**
  String get folderConvertProgressTitle;

  /// No description provided for @folderConvertProgressBody.
  ///
  /// In en, this message translates to:
  /// **'Re-encrypting locked notes.'**
  String get folderConvertProgressBody;

  /// No description provided for @drawerHeaderFolders.
  ///
  /// In en, this message translates to:
  /// **'FOLDERS'**
  String get drawerHeaderFolders;

  /// No description provided for @drawerNewFolder.
  ///
  /// In en, this message translates to:
  /// **'New folder'**
  String get drawerNewFolder;

  /// No description provided for @drawerFolderOptions.
  ///
  /// In en, this message translates to:
  /// **'Folder options'**
  String get drawerFolderOptions;

  /// No description provided for @drawerConvertToVault.
  ///
  /// In en, this message translates to:
  /// **'Enable vault'**
  String get drawerConvertToVault;

  /// No description provided for @drawerConvertToVaultSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Lock this folder with a passphrase or PIN'**
  String get drawerConvertToVaultSubtitle;

  /// No description provided for @drawerLockNow.
  ///
  /// In en, this message translates to:
  /// **'Lock now'**
  String get drawerLockNow;

  /// No description provided for @drawerLockNowSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Re-locks the decrypted vault'**
  String get drawerLockNowSubtitle;

  /// No description provided for @vaultConvertPartialFail.
  ///
  /// In en, this message translates to:
  /// **'{failed} of {total} notes could not be converted.'**
  String vaultConvertPartialFail(int failed, int total);

  /// No description provided for @vaultConvertSuccess.
  ///
  /// In en, this message translates to:
  /// **'Vault enabled.'**
  String get vaultConvertSuccess;

  /// No description provided for @vaultConvertSuccessWithCount.
  ///
  /// In en, this message translates to:
  /// **'Vault enabled. {n} note(s) encrypted.'**
  String vaultConvertSuccessWithCount(int n);

  /// No description provided for @vaultConvertImpossible.
  ///
  /// In en, this message translates to:
  /// **'Conversion failed: {message}'**
  String vaultConvertImpossible(String message);

  /// No description provided for @noteEditorOutgoingLinks.
  ///
  /// In en, this message translates to:
  /// **'Links ({n})'**
  String noteEditorOutgoingLinks(int n);

  /// No description provided for @noteCardLocked.
  ///
  /// In en, this message translates to:
  /// **'🔒 Locked note'**
  String get noteCardLocked;

  /// No description provided for @voiceMicInitializing.
  ///
  /// In en, this message translates to:
  /// **'Initializing microphone…'**
  String get voiceMicInitializing;

  /// No description provided for @voiceTranscribingHint.
  ///
  /// In en, this message translates to:
  /// **'Please wait…'**
  String get voiceTranscribingHint;

  /// No description provided for @voiceOpenSystemSettings.
  ///
  /// In en, this message translates to:
  /// **'Open settings'**
  String get voiceOpenSystemSettings;

  /// No description provided for @moveToFolderTitle.
  ///
  /// In en, this message translates to:
  /// **'Move to folder'**
  String get moveToFolderTitle;

  /// No description provided for @moveToFolderEmpty.
  ///
  /// In en, this message translates to:
  /// **'No other folder available.'**
  String get moveToFolderEmpty;

  /// No description provided for @linkAutocompleteTitle.
  ///
  /// In en, this message translates to:
  /// **'Insert link'**
  String get linkAutocompleteTitle;

  /// No description provided for @linkAutocompleteHint.
  ///
  /// In en, this message translates to:
  /// **'Title of the note to link'**
  String get linkAutocompleteHint;

  /// No description provided for @linkAutocompleteEmpty.
  ///
  /// In en, this message translates to:
  /// **'No matching note.'**
  String get linkAutocompleteEmpty;

  /// No description provided for @linkAutocompleteCreateNew.
  ///
  /// In en, this message translates to:
  /// **'Create a new note “{title}”'**
  String linkAutocompleteCreateNew(String title);

  /// No description provided for @aiChatModelLoaded.
  ///
  /// In en, this message translates to:
  /// **'Model ready'**
  String get aiChatModelLoaded;

  /// No description provided for @voiceSetupTitle.
  ///
  /// In en, this message translates to:
  /// **'Voice dictation'**
  String get voiceSetupTitle;

  /// No description provided for @voiceSetupSubtitle.
  ///
  /// In en, this message translates to:
  /// **'On-device Whisper. Audio is never persisted.'**
  String get voiceSetupSubtitle;

  /// No description provided for @voiceSetupEnable.
  ///
  /// In en, this message translates to:
  /// **'Enable voice dictation'**
  String get voiceSetupEnable;

  /// No description provided for @voiceSetupChooseModel.
  ///
  /// In en, this message translates to:
  /// **'Choose a Whisper model'**
  String get voiceSetupChooseModel;

  /// No description provided for @voiceSetupDownload.
  ///
  /// In en, this message translates to:
  /// **'Download to this phone'**
  String get voiceSetupDownload;

  /// No description provided for @voiceSetupSelectFile.
  ///
  /// In en, this message translates to:
  /// **'Select the .bin file'**
  String get voiceSetupSelectFile;

  /// No description provided for @voiceSetupVerifying.
  ///
  /// In en, this message translates to:
  /// **'Verifying fingerprint…'**
  String get voiceSetupVerifying;

  /// No description provided for @voiceSetupInstallOk.
  ///
  /// In en, this message translates to:
  /// **'Model installed: {name}'**
  String voiceSetupInstallOk(String name);

  /// No description provided for @voiceSetupInstallFail.
  ///
  /// In en, this message translates to:
  /// **'Install failed: {message}'**
  String voiceSetupInstallFail(String message);

  /// No description provided for @voiceSetupRemove.
  ///
  /// In en, this message translates to:
  /// **'Remove installed model'**
  String get voiceSetupRemove;

  /// No description provided for @voiceRecordingTitle.
  ///
  /// In en, this message translates to:
  /// **'Recording'**
  String get voiceRecordingTitle;

  /// No description provided for @voiceRecordingHint.
  ///
  /// In en, this message translates to:
  /// **'Speak. Tap “Stop” to transcribe.'**
  String get voiceRecordingHint;

  /// No description provided for @voiceRecordingStop.
  ///
  /// In en, this message translates to:
  /// **'Stop'**
  String get voiceRecordingStop;

  /// No description provided for @voiceTranscribing.
  ///
  /// In en, this message translates to:
  /// **'Transcribing…'**
  String get voiceTranscribing;

  /// No description provided for @voiceTranscribed.
  ///
  /// In en, this message translates to:
  /// **'Text inserted.'**
  String get voiceTranscribed;

  /// No description provided for @voicePermissionDenied.
  ///
  /// In en, this message translates to:
  /// **'Microphone permission denied.'**
  String get voicePermissionDenied;

  /// No description provided for @exportShareSubject.
  ///
  /// In en, this message translates to:
  /// **'Notes Tech — export {count} notes'**
  String exportShareSubject(int count);

  /// No description provided for @errorVaultLocked.
  ///
  /// In en, this message translates to:
  /// **'Vault locked.'**
  String get errorVaultLocked;

  /// No description provided for @errorVoiceNoModelInstalled.
  ///
  /// In en, this message translates to:
  /// **'No transcription model installed.'**
  String get errorVoiceNoModelInstalled;

  /// No description provided for @errorVoiceStartCaptureFailed.
  ///
  /// In en, this message translates to:
  /// **'Failed to start microphone capture.'**
  String get errorVoiceStartCaptureFailed;

  /// No description provided for @errorVoiceTranscribeFailed.
  ///
  /// In en, this message translates to:
  /// **'Transcription failed.'**
  String get errorVoiceTranscribeFailed;

  /// No description provided for @errorVoiceMicCaptureError.
  ///
  /// In en, this message translates to:
  /// **'Microphone capture error.'**
  String get errorVoiceMicCaptureError;

  /// No description provided for @homeVaultCreateError.
  ///
  /// In en, this message translates to:
  /// **'Vault creation failed: {message}'**
  String homeVaultCreateError(String message);

  /// No description provided for @homeNoteCreatedInInbox.
  ///
  /// In en, this message translates to:
  /// **'Note created in Inbox'**
  String get homeNoteCreatedInInbox;

  /// No description provided for @homeLoadError.
  ///
  /// In en, this message translates to:
  /// **'An error occurred while loading.'**
  String get homeLoadError;

  /// No description provided for @noteEditorErrorNotFound.
  ///
  /// In en, this message translates to:
  /// **'Note not found'**
  String get noteEditorErrorNotFound;

  /// No description provided for @noteEditorErrorVaultFolderMissing.
  ///
  /// In en, this message translates to:
  /// **'Vault folder not found'**
  String get noteEditorErrorVaultFolderMissing;

  /// No description provided for @noteEditorErrorVaultWiped.
  ///
  /// In en, this message translates to:
  /// **'Vault auto-wiped after too many failed attempts. Notes in this folder are permanently lost.'**
  String get noteEditorErrorVaultWiped;

  /// No description provided for @noteEditorErrorVaultRelocked.
  ///
  /// In en, this message translates to:
  /// **'Vault re-locked. Reopen the note to retry.'**
  String get noteEditorErrorVaultRelocked;

  /// No description provided for @noteEditorErrorLoadGeneric.
  ///
  /// In en, this message translates to:
  /// **'An error occurred while loading.'**
  String get noteEditorErrorLoadGeneric;

  /// No description provided for @noteEditorErrorVaultRelockedDuringEdit.
  ///
  /// In en, this message translates to:
  /// **'Vault re-locked while editing. Reopen the note to resume.'**
  String get noteEditorErrorVaultRelockedDuringEdit;

  /// No description provided for @noteEditorErrorSaveFailed.
  ///
  /// In en, this message translates to:
  /// **'Save failed'**
  String get noteEditorErrorSaveFailed;

  /// No description provided for @noteEditorCopiedToClipboard.
  ///
  /// In en, this message translates to:
  /// **'Copied to clipboard'**
  String get noteEditorCopiedToClipboard;

  /// No description provided for @noteEditorExportFailed.
  ///
  /// In en, this message translates to:
  /// **'Export failed: {message}'**
  String noteEditorExportFailed(String message);

  /// No description provided for @noteEditorMoved.
  ///
  /// In en, this message translates to:
  /// **'Note moved'**
  String get noteEditorMoved;

  /// No description provided for @noteEditorMoveFailed.
  ///
  /// In en, this message translates to:
  /// **'Move failed: {message}'**
  String noteEditorMoveFailed(String message);

  /// No description provided for @noteEditorExitVaultTitle.
  ///
  /// In en, this message translates to:
  /// **'Move note out of vault?'**
  String get noteEditorExitVaultTitle;

  /// No description provided for @noteEditorExitVaultBody.
  ///
  /// In en, this message translates to:
  /// **'The content will be decrypted and stored in cleartext in the database, without password protection. Irreversible — the current note will have transited outside encryption, even if you later move it back into a vault.'**
  String get noteEditorExitVaultBody;

  /// No description provided for @noteEditorExitVaultConfirm.
  ///
  /// In en, this message translates to:
  /// **'Leave vault'**
  String get noteEditorExitVaultConfirm;

  /// No description provided for @noteEditorMenuCopyMarkdown.
  ///
  /// In en, this message translates to:
  /// **'Copy Markdown'**
  String get noteEditorMenuCopyMarkdown;

  /// No description provided for @noteEditorContentHint.
  ///
  /// In en, this message translates to:
  /// **'Write in Markdown… ([[Title]] to link)'**
  String get noteEditorContentHint;

  /// No description provided for @searchEmptyTitle.
  ///
  /// In en, this message translates to:
  /// **'Type to search'**
  String get searchEmptyTitle;

  /// No description provided for @searchEmptySubtitleFts.
  ///
  /// In en, this message translates to:
  /// **'Instant 100% local full-text search.'**
  String get searchEmptySubtitleFts;

  /// No description provided for @searchErrorGeneric.
  ///
  /// In en, this message translates to:
  /// **'An error occurred.'**
  String get searchErrorGeneric;

  /// No description provided for @voiceSetupAppBarTitle.
  ///
  /// In en, this message translates to:
  /// **'Voice dictation'**
  String get voiceSetupAppBarTitle;

  /// No description provided for @voiceSetupOfflineBanner.
  ///
  /// In en, this message translates to:
  /// **'100% offline. Audio is never persisted.'**
  String get voiceSetupOfflineBanner;

  /// No description provided for @voiceSetupHowToTitle.
  ///
  /// In en, this message translates to:
  /// **'How to enable dictation'**
  String get voiceSetupHowToTitle;

  /// No description provided for @voiceSetupStep1Title.
  ///
  /// In en, this message translates to:
  /// **'1. Pick a model'**
  String get voiceSetupStep1Title;

  /// No description provided for @voiceSetupStep1Text.
  ///
  /// In en, this message translates to:
  /// **'Whisper Base (57 MB) recommended.'**
  String get voiceSetupStep1Text;

  /// No description provided for @voiceSetupStep2Title.
  ///
  /// In en, this message translates to:
  /// **'2. Download'**
  String get voiceSetupStep2Title;

  /// No description provided for @voiceSetupStep2Text.
  ///
  /// In en, this message translates to:
  /// **'Your browser downloads the .bin into /Downloads. Notes Tech still has no Internet permission.'**
  String get voiceSetupStep2Text;

  /// No description provided for @voiceSetupStep3Title.
  ///
  /// In en, this message translates to:
  /// **'3. Import'**
  String get voiceSetupStep3Title;

  /// No description provided for @voiceSetupStep3Text.
  ///
  /// In en, this message translates to:
  /// **'Select the downloaded .bin. The app verifies SHA-256 then copies privately.'**
  String get voiceSetupStep3Text;

  /// No description provided for @voiceSetupCopyLinkTooltip.
  ///
  /// In en, this message translates to:
  /// **'Copy link'**
  String get voiceSetupCopyLinkTooltip;

  /// No description provided for @voiceSetupLinkCopied.
  ///
  /// In en, this message translates to:
  /// **'Link copied to clipboard'**
  String get voiceSetupLinkCopied;

  /// No description provided for @voiceSetupPathUnavailable.
  ///
  /// In en, this message translates to:
  /// **'File path unavailable'**
  String get voiceSetupPathUnavailable;

  /// No description provided for @voiceSetupImportErrorTitle.
  ///
  /// In en, this message translates to:
  /// **'Import failed'**
  String get voiceSetupImportErrorTitle;

  /// No description provided for @voiceSetupChecksumMismatchBody.
  ///
  /// In en, this message translates to:
  /// **'SHA-256 fingerprint mismatch. File may have been corrupted during download. Details: {message}'**
  String voiceSetupChecksumMismatchBody(String message);

  /// No description provided for @voiceSetupBrowserOpenFailed.
  ///
  /// In en, this message translates to:
  /// **'No browser available'**
  String get voiceSetupBrowserOpenFailed;

  /// No description provided for @voiceSetupBrowserOpenError.
  ///
  /// In en, this message translates to:
  /// **'Cannot open browser: {message}'**
  String voiceSetupBrowserOpenError(String message);

  /// No description provided for @voiceSetupCopying.
  ///
  /// In en, this message translates to:
  /// **'Copying…'**
  String get voiceSetupCopying;

  /// No description provided for @voiceSetupImportInProgress.
  ///
  /// In en, this message translates to:
  /// **'Import in progress, please wait.'**
  String get voiceSetupImportInProgress;

  /// No description provided for @voiceSetupPickerDialogTitle.
  ///
  /// In en, this message translates to:
  /// **'Pick the .bin file for {modelId}'**
  String voiceSetupPickerDialogTitle(String modelId);

  /// No description provided for @voiceSetupSecurityFooterLabel.
  ///
  /// In en, this message translates to:
  /// **'Promise'**
  String get voiceSetupSecurityFooterLabel;

  /// No description provided for @voiceSetupSecurityFooterBody.
  ///
  /// In en, this message translates to:
  /// **'Audio never persisted, local transcription via whisper.cpp, model SHA-256 verified before each load.'**
  String get voiceSetupSecurityFooterBody;

  /// No description provided for @errorFolderNameRequired.
  ///
  /// In en, this message translates to:
  /// **'Folder name is required.'**
  String get errorFolderNameRequired;

  /// No description provided for @errorInboxNotDeletable.
  ///
  /// In en, this message translates to:
  /// **'“Inbox” folder cannot be deleted.'**
  String get errorInboxNotDeletable;

  /// No description provided for @errorNoteTitleTooLong.
  ///
  /// In en, this message translates to:
  /// **'Title too long (max 200 characters).'**
  String get errorNoteTitleTooLong;

  /// No description provided for @errorVaultAlreadyEnabled.
  ///
  /// In en, this message translates to:
  /// **'This folder is already a vault.'**
  String get errorVaultAlreadyEnabled;

  /// No description provided for @errorVaultPassphraseTooShort.
  ///
  /// In en, this message translates to:
  /// **'Passphrase too short (minimum 8 characters).'**
  String get errorVaultPassphraseTooShort;

  /// No description provided for @errorVaultPassphraseWrong.
  ///
  /// In en, this message translates to:
  /// **'Wrong passphrase.'**
  String get errorVaultPassphraseWrong;

  /// No description provided for @errorVaultPinTooShort.
  ///
  /// In en, this message translates to:
  /// **'Invalid PIN: 4 to 6 digits.'**
  String get errorVaultPinTooShort;

  /// No description provided for @errorVaultPinNotDigits.
  ///
  /// In en, this message translates to:
  /// **'Invalid PIN: digits only.'**
  String get errorVaultPinNotDigits;

  /// No description provided for @errorVaultPinWrong.
  ///
  /// In en, this message translates to:
  /// **'Wrong PIN.'**
  String get errorVaultPinWrong;

  /// No description provided for @errorVaultPinWiped.
  ///
  /// In en, this message translates to:
  /// **'Vault self-destructed after too many failed attempts.'**
  String get errorVaultPinWiped;

  /// No description provided for @errorVaultNotPinVault.
  ///
  /// In en, this message translates to:
  /// **'This folder is not a PIN vault.'**
  String get errorVaultNotPinVault;

  /// No description provided for @errorVaultNotAVault.
  ///
  /// In en, this message translates to:
  /// **'This folder is not a vault.'**
  String get errorVaultNotAVault;

  /// No description provided for @errorVaultEncryptedContentInvalid.
  ///
  /// In en, this message translates to:
  /// **'Encrypted content invalid (too short).'**
  String get errorVaultEncryptedContentInvalid;

  /// No description provided for @errorVaultWrapInvalid.
  ///
  /// In en, this message translates to:
  /// **'Encrypted wrap invalid (truncated GCM tag).'**
  String get errorVaultWrapInvalid;

  /// Tagline shown under the title on the first-launch splash (Files Tech, mirror of Pass Tech).
  ///
  /// In en, this message translates to:
  /// **'Maximum protection\nfor your notes.'**
  String get splashTagline;

  /// Discreet hint at the bottom of the splash for instant skip.
  ///
  /// In en, this message translates to:
  /// **'Tap the screen to continue'**
  String get splashSkipHint;

  /// TalkBack content description for the logo image on the splash.
  ///
  /// In en, this message translates to:
  /// **'Notes Tech logo'**
  String get splashLogoContentDescription;

  /// TalkBack announcement for the full splash screen.
  ///
  /// In en, this message translates to:
  /// **'Notes Tech splash screen'**
  String get splashSemanticsLabel;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'fr'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'fr':
      return AppLocalizationsFr();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
