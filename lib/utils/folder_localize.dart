/// Localized display name of a folder.
library;

import '../core/constants.dart';
import '../data/models/folder.dart';
import '../l10n/app_localizations.dart';

/// Name to show for the folder [folderId] stored as [storedName].
///
/// The inbox still carrying a default name is shown in the active language:
/// every install before 2.0.9 seeded it in French, whatever the device
/// language. A folder the user named, the inbox included, keeps its name.
String folderDisplayName(
  String folderId,
  String storedName,
  AppLocalizations t,
) => AppConstants.isDefaultInboxName(folderId, storedName)
    ? t.homeFolderInbox
    : storedName;

extension FolderLocalize on Folder {
  /// See [folderDisplayName].
  String displayName(AppLocalizations t) => folderDisplayName(id, name, t);
}
