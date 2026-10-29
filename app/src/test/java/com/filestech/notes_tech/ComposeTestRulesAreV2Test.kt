package com.filestech.notes_tech

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **Every instrumented Compose test uses the v2 rules** (`androidx.compose.ui.test.junit4.v2`).
 *
 * The v1 rules compose through an `UnconfinedTestDispatcher`: a coroutine of the screen resumed on
 * another thread — a state published from IO, a Markdown reading back from `Dispatchers.Default` —
 * runs the next frame right there, composition AND layout, while the main thread draws. Measured on
 * the CI emulator (API 29, 2026-10-29), never locally: `AppLockLifecycleTest` created the `NavHost` on
 * `AppLockManager`'s IO thread ("addObserver must be called on the main thread"), then
 * `EcranAProposEtLegalTest` laid out the legal page off the main thread ("performMeasureAndLayout
 * called during measure layout") and the crash took the rest of the suite with it. The app is not
 * affected: its Recomposer runs on the main thread.
 *
 * The v2 rules queue that work on a `StandardTestDispatcher` that the test clock runs only through
 * `runOnUiThread` (read in the Compose 1.12.1 test sources). One v1 rule left is the race back, in
 * whichever test happens to meet it: the check is on the shape of the source.
 */
class ComposeTestRulesAreV2Test {

    @Test
    @DisplayName("no instrumented test creates a v1 Compose rule")
    fun only_v2_rules() {
        val offenders = testSources()
            .filter { source -> V1.containsMatchIn(source.readText()) }
            .map { it.name }
            .sorted()

        assertThat(offenders).isEmpty()
    }

    /** The negative control: a scan that read nothing would also find no offender. */
    @Test
    @DisplayName("the scan actually reads the instrumented tests, and the pattern catches a v1 rule")
    fun the_scan_reads() {
        val sources = testSources()
        assertThat(sources.size).isGreaterThan(MIN_EXPECTED_SOURCES)
        assertThat(sources.count { it.readText().contains("import androidx.compose.ui.test.junit4.v2.") })
            .isGreaterThan(MIN_EXPECTED_RULES)
        assertThat(V1.containsMatchIn("import androidx.compose.ui.test.junit4.createAndroidComposeRule")).isTrue()
        assertThat(V1.containsMatchIn("val r = androidx.compose.ui.test.junit4.createEmptyComposeRule()")).isTrue()
        assertThat(V1.containsMatchIn("import androidx.compose.ui.test.junit4.v2.createComposeRule")).isFalse()
    }

    private fun testSources(): List<File> {
        val root = File(SOURCE_ROOT)
        check(root.isDirectory) {
            "source root not found from ${File(".").absolutePath} — the test would measure nothing"
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        const val SOURCE_ROOT = "src/androidTest/java/com/filestech/notes_tech"
        const val MIN_EXPECTED_SOURCES = 40
        const val MIN_EXPECTED_RULES = 20

        /** A v1 factory, imported or written out; the v2 ones live one package down. */
        val V1 = Regex("""androidx\.compose\.ui\.test\.junit4\.create\w*ComposeRule\b""")
    }
}
