package com.filestech.notes_tech.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **`rememberAppResultLauncher` is the only seam** through which the app opens an activity for a
 * result (taken from Agenda Tech, with the class it guards).
 *
 * A launcher written with the platform API still works — its picker opens and returns — but the app
 * lock locks behind it, and the flow waiting for the result is lost after the PIN. That is the defect
 * Agenda Tech fixed across eight launchers. The check is on the shape of the source, because the
 * defect is a copy that behaves correctly on its own.
 */
class AppResultLauncherIsTheOnlySeamTest {

    @Test
    @DisplayName("nothing outside the seam registers an activity result launcher by hand")
    fun only_the_seam() {
        val offenders = kotlinSources()
            .filter { it.name != SEAM }
            .filter { source -> FORBIDDEN.any { source.readText().contains(it) } }
            .map { it.name }
            .sorted()

        assertThat(offenders).isEmpty()
    }

    /** The negative control: a scan that read nothing would also find no offender. */
    @Test
    @DisplayName("the scan actually reads the sources, the seam included")
    fun the_scan_reads() {
        val sources = kotlinSources()
        assertThat(sources.size).isGreaterThan(MIN_EXPECTED_SOURCES)
        val seam = sources.filter { it.name == SEAM }
        assertThat(seam).hasSize(1)
        assertThat(seam.single().readText()).contains("rememberLauncherForActivityResult(")
        // And the two launchers the app has go through it.
        val users = sources.filter { it.readText().contains("rememberAppResultLauncher(") }.map { it.name }
        assertThat(users).containsAtLeast("VoiceSetupScreen.kt", "ControleurDeDictee.kt")
    }

    private fun kotlinSources(): List<File> {
        val root = File(SOURCE_ROOT)
        check(root.isDirectory) {
            "source root not found from ${File(".").absolutePath} — the test would measure nothing"
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        const val SOURCE_ROOT = "src/main/java/com/filestech/notes_tech"
        const val MIN_EXPECTED_SOURCES = 100
        const val SEAM = "AppResultLauncher.kt"
        val FORBIDDEN = listOf("rememberLauncherForActivityResult(", "registerForActivityResult(")
    }
}
