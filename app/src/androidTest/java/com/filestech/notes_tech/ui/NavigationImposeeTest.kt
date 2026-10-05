package com.filestech.notes_tech.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 🔴 **Another app cannot steer the navigation through the launch intent** — security audit of
 * 2026-09-26, E1. The intent is built as a hostile caller would: Navigation's own extras, a data URI,
 * on the launcher's action and category.
 */
@RunWith(AndroidJUnit4::class)
class NavigationImposeeTest {

    @Test
    fun the_launch_intent_loses_what_could_steer_the_navigation() {
        val hostile = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(1, 2))
            putExtra("android-support-nav:controller:deepLinkExtras", arrayListOf(Bundle(), Bundle()))
            putExtra("android-support-nav:controller:deepLinkIntent", Intent("x"))
            data = Uri.parse("notestech://note/whatever")
        }

        val nettoye = sansNavigationImposee(hostile)

        assertThat(nettoye.extras).isNull()
        assertThat(nettoye.data).isNull()
        // What launches the app stays.
        assertThat(nettoye.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(nettoye.categories).containsExactly(Intent.CATEGORY_LAUNCHER)
    }
}
