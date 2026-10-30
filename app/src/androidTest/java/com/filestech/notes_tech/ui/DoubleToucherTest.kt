package com.filestech.notes_tech.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.security.applock.AppLockStore
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.inject.Inject

/**
 * 🔴 **A second tap reaches a screen that is already leaving** (3.2.1). On 3.1.1 and 3.2.0 as
 * published, two taps on « Fermer » less than ~0.5 s apart left a WHITE screen: the second one
 * popped the list too, and nothing was left to show. A double tap on a note stacked it twice.
 *
 * The clock is paused between the two gestures: the first one's navigation is not drawn yet, the
 * screen it leaves is still there to receive the second — what a quick finger does during the
 * transition. Both tests fail on the unfixed 3.2.0 code (measured). Two quick system Backs, or the
 * arrow then a system Back, already returned to the list there (measured too): the editor's
 * `BackHandler` leaves with its entry, so they are not tested here.
 *
 * ⚠️ It writes to the app's real database: one note, with a random suffix, deleted in [tearDown].
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DoubleToucherTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var notes: NotesRepository

    @Inject
    lateinit var store: AppLockStore

    @Inject
    lateinit var prefs: LegacyPreferences

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val titre = "Double tap ${UUID.randomUUID().toString().take(8)}"
    private var scenario: ActivityScenario<MainActivity>? = null
    private var presentationDejaVue = false
    private var existantes = emptySet<String>()

    @Before
    fun setUp() {
        hilt.inject()
        check(!store.configuredNow()) { "An app lock is configured on this device: turn it off first." }
        val settings = AppSettings(prefs)
        presentationDejaVue = !settings.shouldShowSplash()
        settings.markSplashShown()
        runBlocking {
            notes.create(folderId = Folder.INBOX_ID, title = titre, content = "Body.")
            existantes = notes.listAllAlive().map { it.id }.toSet()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText(titre), TIMEOUT_MILLIS)
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        scenario?.close()
        runBlocking {
            // The test's note, and those « Nouvelle note » created.
            notes.listAllAlive().filter { it.title == titre || it.id !in existantes }
                .forEach { notes.deletePermanently(it.id) }
        }
        if (!presentationDejaVue) prefs.remove(SPLASH_SHOWN_KEY)
    }

    private fun texte(id: Int): String = context.getString(id)

    private fun ouvrirLaNote() {
        compose.onNodeWithText(titre).performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription(texte(R.string.common_close)), TIMEOUT_MILLIS)
    }

    /** The list, not a white screen and not another editor: its search field is there. */
    private fun laListeEstLa() {
        compose.waitUntilAtLeastOneExists(hasText(texte(R.string.home_search_hint)), TIMEOUT_MILLIS)
        compose.onNodeWithText(titre).assertExists()
    }

    @Test
    fun two_quick_taps_on_close_return_to_the_list() {
        ouvrirLaNote()
        val fermer = compose.onNodeWithContentDescription(texte(R.string.common_close))

        compose.mainClock.autoAdvance = false
        fermer.performClick()
        fermer.performClick()
        compose.mainClock.autoAdvance = true

        laListeEstLa()
    }

    @Test
    fun a_double_tap_on_a_note_opens_it_once() {
        val carte = compose.onNodeWithText(titre)

        compose.mainClock.autoAdvance = false
        carte.performClick()
        carte.performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntilAtLeastOneExists(hasContentDescription(texte(R.string.common_close)), TIMEOUT_MILLIS)

        // One close is enough: there is one editor, not two stacked.
        compose.onNodeWithContentDescription(texte(R.string.common_close)).performClick()
        laListeEstLa()
    }

    @Test
    fun a_double_tap_on_new_note_creates_and_opens_one_note() {
        val nouvelle = compose.onNodeWithContentDescription(texte(R.string.home_new_note))

        compose.mainClock.autoAdvance = false
        nouvelle.performClick()
        nouvelle.performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntilAtLeastOneExists(hasContentDescription(texte(R.string.common_close)), TIMEOUT_MILLIS)

        laListeApresUnSeulFermer()
    }

    /** The second tap comes once the note exists and the editor is opening: the screen drops it. */
    @Test
    fun a_tap_on_new_note_while_the_editor_opens_is_dropped() {
        val nouvelle = compose.onNodeWithContentDescription(texte(R.string.home_new_note))

        compose.mainClock.autoAdvance = false
        nouvelle.performClick()
        compose.waitUntil(TIMEOUT_MILLIS) { creees() == 1 }
        // The creation's event is collected and the navigation made — no frame drawn yet.
        compose.waitForIdle()
        nouvelle.performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntilAtLeastOneExists(hasContentDescription(texte(R.string.common_close)), TIMEOUT_MILLIS)

        laListeApresUnSeulFermer()
    }

    private fun creees(): Int = runBlocking { notes.listAllAlive().count { it.id !in existantes } }

    /** One editor, one note: a single close is enough to come back, and one note was created. */
    private fun laListeApresUnSeulFermer() {
        compose.onNodeWithContentDescription(texte(R.string.common_close)).performClick()
        laListeEstLa()
        assertThat(creees()).isEqualTo(1)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L

        /** `AppSettings.KEY_SPLASH_SHOWN`, written out: the test restores exactly what it set. */
        const val SPLASH_SHOWN_KEY = "splash_shown_v1"
    }
}
