package com.filestech.notes_tech.ui.share

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which URIs a share may make Notes Tech read (3.2.0): another app's `content://`, never this app's own
 * providers — on real `Uri` parsing, where the `0@` bypass of the 3.2.0 security review (L1) lives.
 */
@RunWith(AndroidJUnit4::class)
class FournisseurDUnPartageTest {

    private val paquet = "com.filestech.notes_tech"

    @Test
    fun another_apps_content_uri_is_read() {
        lu("content://com.android.providers.downloads.documents/document/42", attendu = true)
        lu("content://org.example.notes.fileprovider/shared/idee.md", attendu = true)
    }

    @Test
    fun this_apps_own_providers_are_never_read_however_written() {
        lu("content://$paquet.exports/exports/1/notes-tech-export.zip", attendu = false)
        lu("content://$paquet.next.debug.exports/exports/1/a.md", attendu = false)
        lu("content://${paquet.uppercase()}.EXPORTS/exports/a.md", attendu = false)
        // L1: the user-id syntax the content resolver strips before resolving the provider.
        lu("content://0@$paquet.exports/exports/1/a.md", attendu = false)
        lu("content://10@$paquet.exports/exports/1/a.md", attendu = false)
    }

    @Test
    fun anything_but_another_apps_content_uri_is_refused() {
        lu("file:///data/data/$paquet/databases/notes_tech.db", attendu = false)
        lu("file:///sdcard/Download/idee.md", attendu = false)
        lu("http://example.org/idee.md", attendu = false)
        // A user-info on another app's authority: the cross-user form, refused too.
        lu("content://0@org.example.notes.fileprovider/shared/idee.md", attendu = false)
    }

    private fun lu(uri: String, attendu: Boolean) {
        assertWithMessage(
            uri,
        ).that(RecevoirUnPartageActivity.estDUneAutreAppli(Uri.parse(uri), paquet)).isEqualTo(attendu)
    }
}
