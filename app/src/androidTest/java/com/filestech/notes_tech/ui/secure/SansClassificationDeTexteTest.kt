package com.filestech.notes_tech.ui.secure

import android.view.ContextThemeWrapper
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationContext
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.filestech.notes_tech.ui.MainActivity
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 🔴 **The selection toolbar's "Translate" sent a note's text to another app** (3.1.1) — the text
 * classifier is off for the whole of [MainActivity]. See [sansClassificationDeTexte].
 *
 * On the real activity, where the fields live: the manager Compose reads, a session made from it as
 * Compose makes one, and the context a dialog or a sheet wraps. The control — the application's own
 * manager keeps the system classifier — shows the first test can fail, and that the setting stays
 * local to the activity.
 */
@SdkSuppress(minSdkVersion = 28)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SansClassificationDeTexteTest {

    // ⚠️ Order 0: Hilt builds its component before anything launches `MainActivity`.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    private var scene: ActivityScenario<MainActivity>? = null

    @Before
    fun lancer() {
        hilt.inject()
        scene = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun fermer() {
        scene?.close()
        scene = null
    }

    @Test
    fun the_activity_turns_the_text_classifier_off() {
        assertThat(gestionnaireDeLActivite().textClassifier).isSameInstanceAs(TextClassifier.NO_OP)
    }

    /** What Compose does before showing the toolbar: a session, then a classification. */
    @Test
    fun a_session_made_from_the_activity_offers_no_action() {
        val session = gestionnaireDeLActivite().createTextClassificationSession(
            TextClassificationContext.Builder(
                ApplicationProvider.getApplicationContext<android.content.Context>().packageName,
                TextClassifier.WIDGET_TYPE_EDITTEXT,
            ).build(),
        )
        val classement = session.classifyText(TextClassification.Request.Builder(MOT, 0, MOT.length).build())
        session.destroy()

        assertThat(classement.actions).isEmpty()
    }

    /** A Compose dialog or bottom sheet wraps the activity's context: it reads the same manager. */
    @Test
    fun a_window_wrapping_the_activity_reads_the_same_manager() {
        var deLaFenetre: TextClassificationManager? = null
        var deLActivite: TextClassificationManager? = null
        scene!!.onActivity { activite ->
            deLActivite = activite.getSystemService(TextClassificationManager::class.java)
            deLaFenetre = ContextThemeWrapper(activite, android.R.style.Theme_DeviceDefault_Dialog)
                .getSystemService(TextClassificationManager::class.java)
        }
        assertThat(deLaFenetre).isSameInstanceAs(deLActivite)
    }

    @Test
    fun the_control_the_application_manager_keeps_the_system_classifier() {
        val application = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(TextClassificationManager::class.java)
        assertThat(application.textClassifier).isNotSameInstanceAs(TextClassifier.NO_OP)
    }

    /**
     * Compose must list no other app's text action, on any version: package visibility is granted
     * per app. See [sansActionsDeTexteDesAutresApps].
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun compose_lists_no_other_app_text_action() {
        gestionnaireDeLActivite() // the activity has run its onCreate
        assertThat(ComposeFoundationFlags.isSmartSelectionEnabled).isFalse()
    }

    private fun gestionnaireDeLActivite(): TextClassificationManager {
        var gestionnaire: TextClassificationManager? = null
        scene!!.onActivity { gestionnaire = it.getSystemService(TextClassificationManager::class.java) }
        return gestionnaire!!
    }

    private companion object {
        const val MOT = "Intensive"
    }
}
