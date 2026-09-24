package com.filestech.notes_tech.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder

/**
 * The name to SHOW for a folder: a default inbox name in the app's language, any other name as
 * stored — notes_tech 2.0.9's `folderDisplayName` (`lib/utils/folder_localize.dart`).
 *
 * Pure, so the rule is tested on the JVM against the published app's vectors
 * (`test/inbox_default_name_test.dart`).
 *
 * ⚠️ [inboxLabel] must come from the ACTIVITY's resources — `stringResource` in a composable — never
 * from the application context: the language the user chose in the app is set on the activity
 * (`MainActivity.attachBaseContext`), and the application context answers in the phone's language.
 */
fun folderDisplayName(folderId: String, storedName: String, inboxLabel: String): String =
    if (Folder.isDefaultInboxName(folderId, storedName)) inboxLabel else storedName

/** [folderDisplayName] for a folder at hand. */
@Composable
fun Folder.displayName(): String = folderDisplayName(id, name, stringResource(R.string.home_folder_inbox))

/**
 * [folderDisplayName] as a function, for screens that hold a map `id → stored name` built by a
 * ViewModel. The map stays raw on purpose: translating inside the ViewModel would freeze the name in
 * the language of the moment the flow was built.
 */
@Composable
fun rememberFolderDisplayName(): (folderId: String, storedName: String) -> String {
    val inboxLabel = stringResource(R.string.home_folder_inbox)
    return remember(inboxLabel) { { folderId, storedName -> folderDisplayName(folderId, storedName, inboxLabel) } }
}
