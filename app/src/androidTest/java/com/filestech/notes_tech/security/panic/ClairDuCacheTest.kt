package com.filestech.notes_tech.security.panic

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 🔴 **What notes_tech 2.x left in the cache is plaintext, and goes** — security audit of 2026-09-26,
 * P1 and P2. The purge is the one run at every startup and at the plaintext rank of the panic.
 *
 * The files are laid out as 2.x leaves them, in the app's real cache: share_plus 10.1.4's copy of the
 * last export, a note exported alone at the root, a dictation of `files_tech_voice`. The control: what
 * the definition does not call plaintext — a library's cache — stays.
 */
@RunWith(AndroidJUnit4::class)
class ClairDuCacheTest {

    private lateinit var context: Context
    private lateinit var neutres: List<File>

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        neutres = listOf(
            File(context.cacheDir, "image_cache_test/tile.bin"),
            File(context.cacheDir, "fonts_test.bin"),
        )
    }

    @After
    fun tearDown() {
        neutres.forEach { (it.parentFile?.takeIf { p -> p != context.cacheDir } ?: it).deleteRecursively() }
    }

    @Test
    fun the_plaintext_left_by_the_published_app_is_purged_and_nothing_else() {
        val heritages = listOf(
            File(context.cacheDir, "share_plus/notes-tech-export-1700000000000.zip"),
            File(context.cacheDir, "Bank codes [unlocked].md"),
            File(context.cacheDir, "stt_capture_1700000000000.wav"),
            File(context.cacheDir, "exports/notes-tech-export-1700000000001.zip"),
        )
        (heritages + neutres).forEach { fichier ->
            fichier.parentFile?.mkdirs()
            fichier.writeText("PIN 4242")
        }

        val survivants = ClairDuCache.purger(context)

        assertThat(survivants).isEmpty()
        heritages.forEach { assertThat(it.exists()).isFalse() }
        assertThat(File(context.cacheDir, "share_plus").exists()).isFalse()
        neutres.forEach { assertThat(it.exists()).isTrue() }
    }

    @Test
    fun the_definition_names_the_share_plus_directory_by_its_own_name() {
        assertThat(ClairDuCache.estUnArtefactSensible(context, "share_plus")).isTrue()
        assertThat(ClairDuCache.estUnArtefactSensible(context, "Note.MD")).isTrue()
        assertThat(ClairDuCache.estUnArtefactSensible(context, "image_cache")).isFalse()
    }
}
