// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for French (`fr`).
class AppLocalizationsFr extends AppLocalizations {
  AppLocalizationsFr([String locale = 'fr']) : super(locale);

  @override
  String get appTitle => 'Notes Tech';

  @override
  String get commonCancel => 'Annuler';

  @override
  String get commonOk => 'OK';

  @override
  String get commonClose => 'Fermer';

  @override
  String get commonDelete => 'Supprimer';

  @override
  String get commonRemove => 'Retirer';

  @override
  String get commonRename => 'Renommer';

  @override
  String get commonShare => 'Partager';

  @override
  String get commonError => 'Erreur';

  @override
  String commonErrorWith(String message) {
    return 'Erreur : $message';
  }

  @override
  String get commonLoading => 'Chargement…';

  @override
  String get commonValidate => 'Valider';

  @override
  String get homeAllNotes => 'Toutes les notes';

  @override
  String get homeFolders => 'Dossiers';

  @override
  String get homeNewNote => 'Nouvelle note';

  @override
  String get homeSearchHint => 'Rechercher une note';

  @override
  String get homeNoNotes => 'Aucune note';

  @override
  String get homeNoNotesIn => 'Aucune note dans ce dossier';

  @override
  String get homeStartWriting =>
      'Tapez le bouton + pour créer votre première note.';

  @override
  String get homeSortMode => 'Trier';

  @override
  String get homeSortRecentFirst => 'Plus récent d\'abord';

  @override
  String get homeSortOldFirst => 'Plus ancien d\'abord';

  @override
  String get homeSortAlphaAsc => 'A → Z';

  @override
  String get homeSortAlphaDesc => 'Z → A';

  @override
  String get homeFolderInbox => 'Boîte de réception';

  @override
  String get homeUnpin => 'Désépingler';

  @override
  String get homeUnfav => 'Retirer des favoris';

  @override
  String homeVaultLostBanner(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count notes de coffre ont perdu leurs dernières modifications',
      one: '1 note de coffre a perdu ses dernières modifications',
    );
    return '$_temp0 (coffre verrouillé pendant l\'enregistrement).';
  }

  @override
  String get drawerTrash => 'Corbeille';

  @override
  String get trashTitle => 'Corbeille';

  @override
  String get trashEmptyTitle => 'Corbeille vide';

  @override
  String get trashEmptySubtitle =>
      'Les notes supprimées apparaissent ici avant leur effacement définitif.';

  @override
  String trashRetentionNotice(int days) {
    return 'Les notes en corbeille sont supprimées automatiquement après $days jours.';
  }

  @override
  String get commonRestore => 'Restaurer';

  @override
  String get trashRestored => 'Note restaurée';

  @override
  String get trashDeleteForever => 'Supprimer définitivement';

  @override
  String get trashDeleteForeverTitle => 'Supprimer définitivement ?';

  @override
  String get trashDeleteForeverBody =>
      'Cette note sera effacée définitivement. Cette action est irréversible.';

  @override
  String get trashDeletedForever => 'Note supprimée définitivement';

  @override
  String get trashEmptyAll => 'Vider la corbeille';

  @override
  String get trashEmptyAllConfirm =>
      'Supprimer définitivement toutes les notes de la corbeille ? Cette action est irréversible.';

  @override
  String get homeAnnounceVaultUnlocked => 'Coffre déverrouillé';

  @override
  String get noteUntitled => 'Sans titre';

  @override
  String get noteEditorTitle => 'Titre';

  @override
  String get noteEditorContent => 'Tapez votre note (Markdown supporté)';

  @override
  String get noteEditorSaved => 'Enregistré';

  @override
  String get noteEditorSaving => 'Enregistrement…';

  @override
  String get noteEditorTooltipPin => 'Épingler la note';

  @override
  String get noteEditorTooltipFav => 'Marquer en favori';

  @override
  String get noteEditorTooltipInsertLink => 'Insérer un lien interne [[Titre]]';

  @override
  String get noteEditorTooltipMore => 'Plus d\'actions';

  @override
  String get noteEditorTooltipDictate => 'Dictée vocale';

  @override
  String get noteEditorTooltipDone => 'Terminé';

  @override
  String get noteEditorMenuMove => 'Déplacer dans un dossier';

  @override
  String get noteEditorMenuExport => 'Exporter en Markdown';

  @override
  String get noteEditorMenuTrash => 'Mettre à la corbeille';

  @override
  String get noteEditorBacklinks => 'Notes qui mentionnent celle-ci';

  @override
  String noteEditorBacklinkDangling(String title) {
    return 'Lien vers une note inexistante : $title';
  }

  @override
  String get noteEditorAnnounceSavedSuccess => 'Note enregistrée';

  @override
  String get searchTitle => 'Rechercher';

  @override
  String get searchHint => 'Mot-clé, début de note, ou question…';

  @override
  String get searchEmpty => 'Aucun résultat';

  @override
  String get searchTryOther => 'Essayez un autre mot-clé.';

  @override
  String get searchClear => 'Effacer la recherche';

  @override
  String get settingsTitle => 'Réglages';

  @override
  String get settingsSectionAppearance => 'Apparence';

  @override
  String get settingsSectionSecurity => 'Sécurité';

  @override
  String get settingsSectionAbout => 'À propos';

  @override
  String get settingsLanguage => 'Langue';

  @override
  String get settingsLanguageSystem => 'Système';

  @override
  String get settingsLanguageFr => 'Français';

  @override
  String get settingsLanguageEn => 'English';

  @override
  String get settingsLanguageChangedFr => 'Langue changée en français';

  @override
  String get settingsLanguageChangedEn => 'Language switched to English';

  @override
  String get settingsTheme => 'Thème';

  @override
  String get settingsThemeSystem => 'Système';

  @override
  String get settingsThemeLight => 'Clair';

  @override
  String get settingsThemeDark => 'Sombre';

  @override
  String get settingsSecureWindow => 'Masquer dans les apps récentes';

  @override
  String get settingsSecureWindowSubtitle =>
      'Empêche la capture d\'écran et masque l\'aperçu de l\'app dans le sélecteur Android.';

  @override
  String get settingsVaultAutoLock => 'Verrouillage automatique des coffres';

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
  String get settingsVaultAutoLockNever => 'Jamais';

  @override
  String get settingsExportAll => 'Exporter toutes mes notes';

  @override
  String get settingsExportSubtitle =>
      'Génère une archive ZIP Markdown organisée par dossier.';

  @override
  String settingsExportDone(int count) {
    return 'Export terminé : $count notes';
  }

  @override
  String settingsExportDonePartial(int count, int skipped) {
    return 'Export terminé : $count notes ($skipped ignorées dans des coffres verrouillés)';
  }

  @override
  String exportNoteFromVault(String folder) {
    return 'Note du coffre : $folder';
  }

  @override
  String settingsExportError(String message) {
    return 'L\'export a échoué : $message';
  }

  @override
  String get settingsPanic => 'Mode panique';

  @override
  String get settingsPanicSubtitle =>
      'Efface définitivement notes, clé, modèles et coffres.';

  @override
  String get settingsAbout => 'À propos de Notes Tech';

  @override
  String get settingsAboutSubtitle => 'Confidentialité, licences, support';

  @override
  String get aboutTitle => 'À propos';

  @override
  String get aboutTagline =>
      'Vos notes restent dans votre poche. Chiffrées, et hors ligne.';

  @override
  String get aboutCheckUpdates => 'Vérifier les mises à jour';

  @override
  String get aboutCheckUpdatesHint =>
      'Ouvre la page des versions sur GitHub dans votre navigateur — l\'app ne se connecte jamais à Internet elle-même.';

  @override
  String get aboutSectionPrivacy => 'Confidentialité';

  @override
  String get aboutPrivacyCardTitle => '100 % privé — zéro surveillance';

  @override
  String get aboutPrivacy1 =>
      'Aucune connexion réseau — vérifiable dans le manifeste';

  @override
  String get aboutPrivacy2 => 'Aucun compte, aucune inscription';

  @override
  String get aboutPrivacy3 => 'Aucun tracker, aucune publicité';

  @override
  String get aboutPrivacy4 =>
      'Notes chiffrées localement (SQLCipher + Android Keystore)';

  @override
  String get aboutPrivacy5 =>
      'Mode « masquer dans les apps récentes » disponible';

  @override
  String get aboutSectionVoice => 'Dictée vocale';

  @override
  String get aboutVoice1 =>
      'Whisper on-device (whisper.cpp via files_tech_voice)';

  @override
  String get aboutVoice2 =>
      'Modèle vérifié SHA-256 au DL et avant chaque chargement';

  @override
  String get aboutVoice3 =>
      'Audio capturé jamais persisté (effacé après transcription)';

  @override
  String get aboutNoticeTitle => 'Notice d\'emploi — activer la dictée';

  @override
  String get aboutNoticeStep1 =>
      '1. Réglages → Dictée vocale → Activer la dictée vocale.';

  @override
  String get aboutNoticeStep2 =>
      '2. Choisissez un modèle (Whisper Base 57 Mo recommandé).';

  @override
  String get aboutNoticeStep3 =>
      '3. Tapez « Télécharger sur ce téléphone » — le navigateur système télécharge le fichier .bin dans Téléchargements. Notes Tech reste sans permission Internet : c\'est votre navigateur qui télécharge, pas l\'app.';

  @override
  String get aboutNoticeStep4 =>
      '4. Tapez « Sélectionner le fichier .bin » — l\'app vérifie l\'empreinte cryptographique puis copie le modèle dans sa zone privée.';

  @override
  String get aboutNoticeStep5 =>
      '5. Dans une note, tapez l\'icône micro 🎤 dans la barre du haut. Parlez, puis tapez « Arrêter ». Le texte transcrit s\'insère au curseur.';

  @override
  String get aboutSectionLicenses => 'Sources, licences et code ouvert';

  @override
  String get aboutLinkRepo => 'Notes Tech (cette app)';

  @override
  String get aboutLinkVoice => 'files_tech_voice (module Whisper STT)';

  @override
  String get aboutLinkWhisper => 'Source des modèles Whisper (.bin)';

  @override
  String get aboutLinkGemma => 'Source du modèle Gemma 3 1B';

  @override
  String get aboutLicense =>
      'Apache License 2.0 — code source ouvert, vérifiable';

  @override
  String get aboutFree => 'Gratuit — pas de version premium, pas d\'abonnement';

  @override
  String get aboutSectionContact => 'Auteur & contact';

  @override
  String get aboutContactQuestions => 'Questions, suggestions, retours';

  @override
  String get aboutSectionLegal => 'Mentions légales';

  @override
  String get aboutLegalLink => 'Voir les mentions légales complètes';

  @override
  String get aboutLegalSubtitle =>
      'Éditeur, données collectées, permissions, droits, licence';

  @override
  String get aboutLinkCopied => 'Lien copié — collez-le dans votre navigateur.';

  @override
  String get legalTitle => 'Mentions légales';

  @override
  String get legalTabPrivacy => 'Confidentialité';

  @override
  String get legalTabTerms => 'Conditions';

  @override
  String get vaultPassCreateTitle => 'Créer un coffre';

  @override
  String get vaultPassCreateBody =>
      'Choisissez une passphrase robuste pour ce dossier. Notez-la dans un endroit sûr — si vous l\'oubliez, les notes verrouillées seront irrécupérables.';

  @override
  String get vaultPassField => 'Passphrase';

  @override
  String get vaultPassConfirmField => 'Confirmer la passphrase';

  @override
  String vaultPassMinLength(int n) {
    return 'Minimum $n caractères.';
  }

  @override
  String get vaultPassMismatch => 'Les deux passphrases ne correspondent pas.';

  @override
  String get vaultPassWarningLost =>
      'Si vous oubliez cette passphrase, les notes verrouillées dans ce dossier seront IRRÉCUPÉRABLES. Notes Tech ne stocke pas la passphrase et ne peut pas la régénérer.';

  @override
  String get vaultPassCreateAction => 'Créer le coffre';

  @override
  String get vaultPassUnlockTitle => 'Déverrouiller le coffre';

  @override
  String vaultPassUnlockBody(String folder) {
    return 'Entrez la passphrase du dossier « $folder ».';
  }

  @override
  String get vaultPassWrong => 'Passphrase incorrecte.';

  @override
  String get vaultPassDeriving => 'Dérivation Argon2id en cours…';

  @override
  String get vaultPassUnlockAction => 'Déverrouiller';

  @override
  String get passphraseShowTooltip => 'Afficher la passphrase';

  @override
  String get passphraseHideTooltip => 'Masquer la passphrase';

  @override
  String get vaultPinCreateTitle => 'Créer un coffre avec un PIN';

  @override
  String get vaultPinConfirmField => 'Confirmer le PIN';

  @override
  String get vaultPinMismatch => 'Les deux PIN ne correspondent pas.';

  @override
  String vaultPinTooShort(int min, int max) {
    return 'Le PIN doit faire entre $min et $max chiffres.';
  }

  @override
  String get vaultPinWarningWipe =>
      'Attention : 5 échecs successifs de saisie du PIN effaceront définitivement les notes verrouillées de ce dossier.';

  @override
  String get vaultPinUnlockTitle => 'Déverrouiller le coffre (PIN)';

  @override
  String vaultPinUnlockBody(String folder) {
    return 'PIN du dossier « $folder ».';
  }

  @override
  String get vaultPinWrong => 'PIN incorrect.';

  @override
  String vaultPinAttemptsLeft(int n) {
    return 'Tentatives restantes : $n';
  }

  @override
  String get vaultPinWiped => 'Trop de tentatives — le coffre a été effacé.';

  @override
  String vaultPinDigitsAnnounce(int filled, int max) {
    return '$filled chiffres saisis sur $max';
  }

  @override
  String vaultPinKeyLabel(String digit) {
    return 'Touche $digit';
  }

  @override
  String get vaultPinKeyDelete => 'Effacer le dernier chiffre';

  @override
  String get vaultModeChoose => 'Choisir le mode de déverrouillage';

  @override
  String get vaultModePassphrase => 'Passphrase';

  @override
  String get vaultModePassphraseDesc =>
      'Recommandée. Plus longue à dériver mais résistante au bruteforce hors-device.';

  @override
  String get vaultModePin => 'PIN (4-6 chiffres)';

  @override
  String get vaultModePinDesc =>
      'Plus rapide. Auto-wipe après 5 échecs. Sécurité device-bound (Keystore).';

  @override
  String get panicConfirmTitle => 'Effacer définitivement toutes les données ?';

  @override
  String get panicConfirmKeyword => 'EFFACER';

  @override
  String get panicConfirmYes => 'Tout effacer';

  @override
  String get panicProgress => 'Effacement en cours…';

  @override
  String get panicProgressSubtitle => 'Veuillez patienter.';

  @override
  String get panicAnnounceDone => 'Effacement terminé';

  @override
  String get panicCompleteTitle => 'Effacement terminé';

  @override
  String get panicCompleteBody =>
      'Toutes les données ont été effacées. Notes Tech redémarre comme au premier lancement.';

  @override
  String get panicCompleteClose => 'Fermer l\'application';

  @override
  String get panicCompleteFooter =>
      'Au prochain lancement, Notes Tech repartira sur une base vierge.';

  @override
  String get panicCompleteBullet1 => 'Clé maître Keystore : détruite';

  @override
  String get panicCompleteBullet2 => 'Base de notes : effacée et écrasée';

  @override
  String get panicCompleteBullet3 =>
      'Modèles IA (Gemma, Whisper) : désinstallés';

  @override
  String get panicCompleteBullet4 => 'Préférences : remises à zéro';

  @override
  String get panicConfirmDestroyIntro =>
      'Vous êtes sur le point de DÉTRUIRE de manière irréversible :';

  @override
  String get panicConfirmItem1 =>
      'Toutes vos notes (chiffrement détruit + fichier écrasé)';

  @override
  String get panicConfirmItem2 =>
      'Tous les modèles IA installés (Gemma, Whisper)';

  @override
  String get panicConfirmItem3 => 'Toutes les préférences et l\'historique';

  @override
  String get panicConfirmIrreversible =>
      'Cette action ne peut PAS être annulée. Aucune sauvegarde, aucune corbeille, aucune récupération forensique possible.';

  @override
  String panicConfirmTypePrompt(String keyword) {
    return 'Pour confirmer, tapez exactement : $keyword';
  }

  @override
  String get panicConfirmFieldLabel => 'Mot de confirmation';

  @override
  String get folderCreateTitle => 'Nouveau dossier';

  @override
  String get folderCreateField => 'Nom du dossier';

  @override
  String get folderRenameTitle => 'Renommer le dossier';

  @override
  String get folderRenameField => 'Nouveau nom';

  @override
  String get folderDeleteTitle => 'Supprimer le dossier ?';

  @override
  String folderDeleteChoiceBody(String name) {
    return 'Que faire des notes de « $name » ?';
  }

  @override
  String get drawerRemoveVaultProtection => 'Retirer la protection';

  @override
  String get drawerRemoveVaultProtectionSubtitle =>
      'Garder le dossier, déchiffrer ses notes';

  @override
  String get folderRemoveVaultTitle => 'Retirer la protection du coffre ?';

  @override
  String folderRemoveVaultBody(String name) {
    return 'Les notes de « $name » seront déchiffrées et écrites en clair dans la base. Le dossier et son contenu sont conservés, mais ils ne seront plus protégés par mot de passe. Action irréversible : les notes auront transité hors chiffrement, même si vous reprotégez le dossier ensuite.';
  }

  @override
  String get folderRemoveVaultConfirm => 'Déchiffrer et retirer';

  @override
  String folderRemoveVaultDone(int n) {
    String _temp0 = intl.Intl.pluralLogic(
      n,
      locale: localeName,
      other: '$n notes déchiffrées. Le dossier n\'\'est plus un coffre.',
      one: '1 note déchiffrée. Le dossier n\'\'est plus un coffre.',
    );
    return '$_temp0';
  }

  @override
  String folderDeleteVaultChoiceBody(String name) {
    return '« $name » est un coffre. Déplacer ses notes vers la Boîte de réception les DÉCHIFFRE toutes et les écrit en clair dans la base, sans protection par mot de passe. Action irréversible : elles auront transité hors chiffrement, même si vous les remettez ensuite dans un coffre.';
  }

  @override
  String get folderDeletePermanent => 'Supprimer définitivement';

  @override
  String get folderDeleteMoveToInbox => 'Déplacer vers Boîte de réception';

  @override
  String get folderDeleteMoveToInboxVault => 'Déchiffrer et déplacer';

  @override
  String folderDeleteDecryptFailed(int n) {
    return 'Déchiffrement impossible pour $n note(s).';
  }

  @override
  String folderDeleteCancelledError(String message) {
    return 'Suppression annulée : $message';
  }

  @override
  String get folderConvertProgressTitle => 'Conversion du coffre…';

  @override
  String get folderConvertProgressBody =>
      'Re-chiffrement des notes verrouillées en cours.';

  @override
  String get drawerHeaderFolders => 'DOSSIERS';

  @override
  String get drawerNewFolder => 'Nouveau dossier';

  @override
  String get drawerFolderOptions => 'Options du dossier';

  @override
  String get drawerConvertToVault => 'Activer un coffre';

  @override
  String get drawerConvertToVaultSubtitle =>
      'Verrouiller ce dossier par passphrase ou PIN';

  @override
  String get drawerLockNow => 'Verrouiller maintenant';

  @override
  String get drawerLockNowSubtitle => 'Re-verrouille le coffre déchiffré';

  @override
  String vaultConvertPartialFail(int failed, int total) {
    return '$failed / $total notes n\'ont pas pu être converties.';
  }

  @override
  String get vaultConvertSuccess => 'Coffre activé.';

  @override
  String vaultConvertSuccessWithCount(int n) {
    return 'Coffre activé. $n note(s) chiffrée(s).';
  }

  @override
  String vaultConvertImpossible(String message) {
    return 'Conversion impossible : $message';
  }

  @override
  String noteEditorOutgoingLinks(int n) {
    return 'Liens ($n)';
  }

  @override
  String get noteCardLocked => '🔒 Note verrouillée';

  @override
  String get voiceMicInitializing => 'Initialisation du micro…';

  @override
  String get voiceTranscribingHint => 'Veuillez patienter…';

  @override
  String get voiceOpenSystemSettings => 'Ouvrir les réglages';

  @override
  String get moveToFolderTitle => 'Déplacer dans un dossier';

  @override
  String get moveToFolderEmpty => 'Aucun autre dossier disponible.';

  @override
  String get linkAutocompleteTitle => 'Insérer un lien';

  @override
  String get linkAutocompleteHint => 'Titre de la note à lier';

  @override
  String get linkAutocompleteEmpty => 'Aucune note ne correspond.';

  @override
  String linkAutocompleteCreateNew(String title) {
    return 'Créer une nouvelle note « $title »';
  }

  @override
  String get aiChatModelLoaded => 'Modèle prêt';

  @override
  String get voiceSetupTitle => 'Dictée vocale';

  @override
  String get voiceSetupSubtitle =>
      'Whisper on-device. L\'audio n\'est jamais persisté.';

  @override
  String get voiceSetupEnable => 'Activer la dictée vocale';

  @override
  String get voiceSetupChooseModel => 'Choisir un modèle Whisper';

  @override
  String get voiceSetupDownload => 'Télécharger sur ce téléphone';

  @override
  String get voiceSetupSelectFile => 'Sélectionner le fichier .bin';

  @override
  String get voiceSetupVerifying => 'Vérification de l\'empreinte…';

  @override
  String voiceSetupInstallOk(String name) {
    return 'Modèle installé : $name';
  }

  @override
  String voiceSetupInstallFail(String message) {
    return 'Installation échouée : $message';
  }

  @override
  String get voiceSetupRemove => 'Retirer le modèle installé';

  @override
  String get voiceRecordingTitle => 'Dictée en cours';

  @override
  String get voiceRecordingHint => 'Parlez. Tapez « Arrêter » pour transcrire.';

  @override
  String get voiceRecordingStop => 'Arrêter';

  @override
  String get voiceTranscribing => 'Transcription…';

  @override
  String get voiceTranscribed => 'Texte inséré.';

  @override
  String get voicePermissionDenied => 'Permission micro refusée.';

  @override
  String exportShareSubject(int count) {
    return 'Notes Tech — export $count notes';
  }

  @override
  String get errorVaultLocked => 'Coffre verrouillé.';

  @override
  String get errorVoiceNoModelInstalled =>
      'Aucun modèle de transcription installé.';

  @override
  String get errorVoiceStartCaptureFailed =>
      'Erreur au démarrage de la capture micro.';

  @override
  String get errorVoiceTranscribeFailed => 'Erreur pendant la transcription.';

  @override
  String get errorVoiceMicCaptureError => 'Erreur de capture micro.';

  @override
  String homeVaultCreateError(String message) {
    return 'Création du coffre échouée : $message';
  }

  @override
  String get homeNoteCreatedInInbox => 'Note créée dans la Boîte de réception';

  @override
  String get homeLoadError => 'Une erreur est survenue lors du chargement.';

  @override
  String get noteEditorErrorNotFound => 'Note introuvable';

  @override
  String get noteEditorErrorVaultFolderMissing => 'Dossier coffre introuvable';

  @override
  String get noteEditorErrorVaultWiped =>
      'Coffre auto-détruit après trop de tentatives ratées. Les notes du dossier sont définitivement perdues.';

  @override
  String get noteEditorErrorVaultRelocked =>
      'Coffre re-verrouillé. Rouvrez la note pour réessayer.';

  @override
  String get noteEditorErrorLoadGeneric =>
      'Une erreur est survenue lors du chargement.';

  @override
  String get noteEditorErrorVaultRelockedDuringEdit =>
      'Coffre re-verrouillé pendant l\'édition. Ré-ouvrez la note pour reprendre.';

  @override
  String get noteEditorErrorSaveFailed => 'Échec de sauvegarde';

  @override
  String get noteEditorCopiedToClipboard => 'Copié dans le presse-papier';

  @override
  String noteEditorExportFailed(String message) {
    return 'Export impossible : $message';
  }

  @override
  String get noteEditorMoved => 'Note déplacée';

  @override
  String noteEditorMoveFailed(String message) {
    return 'Déplacement impossible : $message';
  }

  @override
  String get noteEditorExitVaultTitle => 'Sortir cette note du coffre ?';

  @override
  String get noteEditorExitVaultBody =>
      'Le contenu sera décrypté et écrit en clair dans la base, sans protection par mot de passe. Action irréversible — la note actuelle aura transité hors chiffrement, même si vous la remettez ensuite dans un coffre.';

  @override
  String get noteEditorExitVaultConfirm => 'Sortir du coffre';

  @override
  String get noteEditorMenuCopyMarkdown => 'Copier le Markdown';

  @override
  String get noteEditorContentHint =>
      'Écrivez en Markdown… ([[Titre]] pour lier)';

  @override
  String get searchEmptyTitle => 'Tapez pour rechercher';

  @override
  String get searchEmptySubtitleFts =>
      'Recherche plein texte instantanée et 100% locale.';

  @override
  String get searchErrorGeneric => 'Une erreur est survenue.';

  @override
  String get voiceSetupAppBarTitle => 'Dictée vocale';

  @override
  String get voiceSetupOfflineBanner =>
      '100 % hors-ligne. L\'audio n\'est jamais persisté.';

  @override
  String get voiceSetupHowToTitle => 'Comment activer la dictée';

  @override
  String get voiceSetupStep1Title => '1. Choisir un modèle';

  @override
  String get voiceSetupStep1Text => 'Whisper Base (57 Mo) recommandé.';

  @override
  String get voiceSetupStep2Title => '2. Télécharger';

  @override
  String get voiceSetupStep2Text =>
      'Le navigateur télécharge le .bin dans /Téléchargements. Notes Tech reste sans permission Internet.';

  @override
  String get voiceSetupStep3Title => '3. Importer';

  @override
  String get voiceSetupStep3Text =>
      'Sélectionnez le .bin téléchargé. L\'app vérifie SHA-256 puis copie en privé.';

  @override
  String get voiceSetupCopyLinkTooltip => 'Copier le lien';

  @override
  String get voiceSetupLinkCopied => 'Lien copié dans le presse-papiers';

  @override
  String get voiceSetupPathUnavailable => 'Chemin du fichier non disponible';

  @override
  String get voiceSetupImportErrorTitle => 'Import impossible';

  @override
  String voiceSetupChecksumMismatchBody(String message) {
    return 'Empreinte SHA-256 différente. Le fichier a peut-être été corrompu pendant le téléchargement. Détails : $message';
  }

  @override
  String get voiceSetupBrowserOpenFailed => 'Aucun navigateur disponible';

  @override
  String voiceSetupBrowserOpenError(String message) {
    return 'Impossible d\'ouvrir le navigateur : $message';
  }

  @override
  String get voiceSetupCopying => 'Copie en cours…';

  @override
  String get voiceSetupImportInProgress =>
      'Import en cours, veuillez patienter.';

  @override
  String voiceSetupPickerDialogTitle(String modelId) {
    return 'Choisir le fichier .bin pour $modelId';
  }

  @override
  String get voiceSetupSecurityFooterLabel => 'Promesse';

  @override
  String get voiceSetupSecurityFooterBody =>
      'Audio capturé jamais persisté, transcription locale via whisper.cpp, modèle vérifié SHA-256 avant chaque chargement.';

  @override
  String get errorFolderNameRequired => 'Le nom du dossier est requis.';

  @override
  String get errorInboxNotDeletable =>
      'Le dossier « Boîte de réception » ne peut pas être supprimé.';

  @override
  String get errorNoteTitleTooLong => 'Titre trop long (max 200 caractères).';

  @override
  String get errorVaultAlreadyEnabled => 'Ce dossier est déjà un coffre.';

  @override
  String get errorVaultPassphraseTooShort =>
      'Passphrase trop courte (minimum 8 caractères).';

  @override
  String get errorVaultPassphraseWrong => 'Passphrase incorrecte.';

  @override
  String get errorVaultPinTooShort => 'PIN invalide : 4 à 6 chiffres.';

  @override
  String get errorVaultPinNotDigits => 'PIN invalide : chiffres uniquement.';

  @override
  String get errorVaultPinWrong => 'PIN incorrect.';

  @override
  String get errorVaultPinWiped =>
      'Coffre auto-détruit après trop de tentatives ratées.';

  @override
  String get errorVaultNotPinVault => 'Le dossier n\'est pas un coffre PIN.';

  @override
  String get errorVaultNotAVault => 'Le dossier n\'est pas un coffre.';

  @override
  String get errorVaultEncryptedContentInvalid =>
      'Contenu chiffré invalide (trop court).';

  @override
  String get errorVaultWrapInvalid =>
      'Wrap chiffré invalide (tag GCM tronqué).';

  @override
  String get errorGemmaModelNotInstalled => 'Modèle Gemma non installé.';

  @override
  String get errorGemmaFileNotFound => 'Fichier source introuvable.';

  @override
  String get errorGemmaFileTooSmall =>
      'Fichier trop petit — pas un modèle Gemma valide.';

  @override
  String get errorGemmaFileTooLarge =>
      'Fichier trop gros — au-delà de la limite autorisée.';

  @override
  String get errorGemmaInitFailed => 'Échec d\'initialisation du modèle Gemma.';

  @override
  String get errorGemmaNotLoaded =>
      'Modèle non chargé. Initialisation requise avant utilisation.';

  @override
  String get errorGemmaBusy => 'Une génération est déjà en cours.';

  @override
  String get errorGemmaHashMismatch =>
      'Empreinte SHA-256 inattendue. Le fichier ne correspond pas au modèle officiel.';

  @override
  String get splashTagline => 'La protection maximale\npour vos notes.';

  @override
  String get splashSkipHint => 'Toucher l\'écran pour continuer';

  @override
  String get splashLogoContentDescription => 'Logo Notes Tech';

  @override
  String get splashSemanticsLabel => 'Splash de présentation Notes Tech';
}
