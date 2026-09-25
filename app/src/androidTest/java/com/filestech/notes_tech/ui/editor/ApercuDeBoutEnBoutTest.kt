package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.security.applock.AppLockStore
import com.filestech.notes_tech.ui.MainActivity
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
 * **A `[[Title]]` tapped in the preview, through the real app** (D-024, parity line A2).
 *
 * The screen tests stop at the callback: this one goes through the real `NoteEditorViewModel`, the
 * real database and the real navigation — the only place where "created, **then opened**" can be
 * seen, and where a second tap can be seen to reopen the note instead of creating another.
 *
 * ⚠️ It writes to the app's real database: two notes, with a random suffix, deleted in [tearDown].
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ApercuDeBoutEnBoutTest {

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
    private val suffixe = UUID.randomUUID().toString().take(8)
    private val titreSource = "E2E source $suffixe"
    private val titreCible = "E2E target $suffixe"
    private val texteAffiche = "See $titreCible here."
    private var scenario: ActivityScenario<MainActivity>? = null
    private var presentationDejaVue = false

    @Before
    fun setUp() {
        hilt.inject()
        check(!store.configuredNow()) { "An app lock is configured on this device: turn it off first." }
        val settings = AppSettings(prefs)
        presentationDejaVue = !settings.shouldShowSplash()
        settings.markSplashShown()
        runBlocking {
            val source = notes.create(
                folderId = Folder.INBOX_ID,
                title = titreSource,
                content = "See [[$titreCible]] here.",
            )
            // Pinned: at the top of the list whatever else the device holds.
            notes.setPinned(source.id, true)
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking {
            notes.listAllAlive().filter { it.title == titreSource || it.title == titreCible }
                .forEach { notes.deletePermanently(it.id) }
        }
        if (!presentationDejaVue) prefs.remove(SPLASH_SHOWN_KEY)
    }

    @Test
    fun a_tapped_link_to_a_missing_note_creates_it_opens_it_and_a_second_tap_reopens_it() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText(titreSource), TIMEOUT_MILLIS)
        compose.onNodeWithText(titreSource).performClick()

        ouvrirLApercu()
        toucherLeLien()

        // Created, in the source's folder — and OPEN: its title is in the editor's title field.
        compose.waitUntilAtLeastOneExists(hasSetTextAction() and hasText(titreCible), TIMEOUT_MILLIS)
        val creees = runBlocking { notes.listAllAlive().filter { it.title == titreCible } }
        assertThat(creees).hasSize(1)
        assertThat(creees.single().folderId).isEqualTo(Folder.INBOX_ID)

        // Back to the source, still on its preview (saved state), and the same link tapped again:
        // the note now exists, so it is opened — nothing is created a second time.
        Espresso.pressBack()
        compose.waitUntilAtLeastOneExists(hasText(texteAffiche), TIMEOUT_MILLIS)
        toucherLeLien()
        compose.waitUntilAtLeastOneExists(hasSetTextAction() and hasText(titreCible), TIMEOUT_MILLIS)
        assertThat(runBlocking { notes.listAllAlive().count { it.title == titreCible } }).isEqualTo(1)
    }

    private fun ouvrirLApercu() {
        val apercu = context.getString(R.string.note_editor_mode_preview)
        compose.waitUntilAtLeastOneExists(hasText(apercu), TIMEOUT_MILLIS)
        compose.onNodeWithText(apercu).performClick()
        compose.waitUntilAtLeastOneExists(hasText(texteAffiche), TIMEOUT_MILLIS)
    }

    private fun toucherLeLien() {
        compose.onNode(hasText(texteAffiche), useUnmergedTree = true).onChildren().filter(hasClickAction()).onFirst()
            .performClick()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val SPLASH_SHOWN_KEY = "splash_shown_v1"
    }
}
