package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.security.applock.AppLockStore
import com.filestech.notes_tech.ui.MainActivity
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
 * **A search result opens with "Find in note" on what was searched** (3.2.0, D-033), through the
 * real app: the route's argument, the editor's `SavedStateHandle`, the bar — what no screen test
 * connects. From the search screen, and from the home list filtered by its field, the same search.
 *
 * ⚠️ It writes to the app's real database: one note, with a random suffix, deleted in [tearDown].
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RechercheDeBoutEnBoutTest {

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
    private val titre = "E2E find $suffixe"
    private val mot = "zorglub$suffixe"
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
            notes.create(folderId = Folder.INBOX_ID, title = titre, content = "Début.\n\nLe mot $mot est ici.")
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking { notes.listAllAlive().filter { it.title == titre }.forEach { notes.deletePermanently(it.id) } }
        if (!presentationDejaVue) prefs.remove(SPLASH_SHOWN_KEY)
    }

    private fun texte(id: Int, vararg arguments: Any): String = context.getString(id, *arguments)

    private fun laNoteSOuvreAvecLaBarre() {
        val compte = listOf(texte(R.string.note_find_count_description, 1, 1))
        compose.waitUntilAtLeastOneExists(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, compte),
            TIMEOUT_MILLIS,
        )
        compose.onNode(hasSetTextAction() and hasText(mot)).assertExists()
    }

    @Test
    fun depuis_l_ecran_de_recherche() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText(titre), TIMEOUT_MILLIS)
        compose.onNodeWithContentDescription(texte(R.string.search_title)).performClick()
        compose.onNode(hasSetTextAction() and hasText(texte(R.string.search_hint))).performTextInput(mot)
        compose.waitUntilAtLeastOneExists(hasText(titre), TIMEOUT_MILLIS)

        compose.onNodeWithText(titre).performClick()

        laNoteSOuvreAvecLaBarre()
    }

    @Test
    fun depuis_la_liste_filtree_de_l_accueil() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText(titre), TIMEOUT_MILLIS)
        compose.onNode(hasSetTextAction() and hasText(texte(R.string.home_search_hint))).performTextInput(mot)
        // The list filtered: the note alone, its card the one carrying its title.
        compose.waitUntilExactlyOneExists(hasText(titre), TIMEOUT_MILLIS)

        compose.onNodeWithText(titre).performClick()

        laNoteSOuvreAvecLaBarre()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L

        /** `AppSettings.KEY_SPLASH_SHOWN`, written out: the test restores exactly what it set. */
        const val SPLASH_SHOWN_KEY = "splash_shown_v1"
    }
}
