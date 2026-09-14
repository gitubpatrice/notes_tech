/// Localized labels of the speech recognition models.
///
/// `files_tech_voice` is shared by several Files Tech apps and carries its
/// model names and descriptions in French. The app shows its own translations
/// instead, keyed on the model id.
library;

import 'package:files_tech_voice/files_tech_voice.dart';

import '../l10n/app_localizations.dart';

extension SttModelLocalize on SttModel {
  /// Name in the active language; the package label for a model this app
  /// does not know yet.
  String localizedName(AppLocalizations t) {
    if (id == SttModelCatalog.whisperBaseQ5.id) return t.voiceModelBaseName;
    if (id == SttModelCatalog.whisperTinyQ5.id) return t.voiceModelTinyName;
    return displayName;
  }

  /// Description in the active language; the package text for a model this
  /// app does not know yet.
  String localizedNotes(AppLocalizations t) {
    if (id == SttModelCatalog.whisperBaseQ5.id) return t.voiceModelBaseNotes;
    if (id == SttModelCatalog.whisperTinyQ5.id) return t.voiceModelTinyNotes;
    return notes;
  }
}
