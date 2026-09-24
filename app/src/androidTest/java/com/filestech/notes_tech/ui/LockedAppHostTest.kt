package com.filestech.notes_tech.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locking must hide the app entirely, and unlocking must give the user back the screen they left.
 *
 * Agenda Tech's test, kept as it is with the component it guards (D-023). It drives the PRODUCTION
 * [LockedAppHost] — the one `MainActivity` uses — with a real `NavHost`, a real `ViewModel` and real
 * `rememberSaveable` state. Both halves are asserted: while locked the content is ABSENT from the
 * tree, not merely covered; after unlocking it is the same destination, the same `ViewModel` instance
 * and the same saved state — a note being typed survives a trip through the lock screen.
 */
@RunWith(AndroidJUnit4::class)
class LockedAppHostTest {

    @get:Rule
    val compose = createComposeRule()

    class ScreenViewModel : ViewModel()

    @Test
    fun unlockingReturnsToTheSameScreenWithItsViewModelAndSavedState() {
        val locked = mutableStateOf(false)
        var navController: NavHostController? = null
        var screenViewModel: ScreenViewModel? = null

        compose.setContent {
            LockedAppHost(
                locked = locked.value,
                lockScreen = { Text("locked", Modifier.testTag(LOCK)) },
            ) { nav ->
                navController = nav
                NavHost(nav, startDestination = HOME) {
                    composable(HOME) { Text("home", Modifier.testTag(HOME)) }
                    composable(SECOND) {
                        screenViewModel = viewModel()
                        var typed by rememberSaveable { mutableStateOf("") }
                        Column {
                            Text("typed=$typed", Modifier.testTag(TYPED))
                            Button(onClick = { typed = "abc" }, Modifier.testTag(TYPE)) { Text("type") }
                        }
                    }
                }
            }
        }

        compose.runOnIdle { navController!!.navigate(SECOND) }
        compose.onNodeWithTag(TYPE).performClick()
        compose.onNodeWithTag(TYPED).assertTextEquals("typed=abc")
        val viewModelBefore = compose.runOnIdle { screenViewModel }

        compose.runOnIdle { locked.value = true }
        compose.onNodeWithTag(LOCK).assertIsDisplayed()
        compose.onNodeWithTag(TYPED).assertDoesNotExist()
        compose.onNodeWithTag(HOME).assertDoesNotExist()

        compose.runOnIdle { locked.value = false }
        compose.onNodeWithTag(LOCK).assertDoesNotExist()
        compose.onNodeWithTag(TYPED).assertTextEquals("typed=abc")
        compose.onNodeWithTag(HOME).assertDoesNotExist()
        compose.runOnIdle { assertThat(screenViewModel).isSameInstanceAs(viewModelBefore) }
    }

    private companion object {
        const val LOCK = "lock"
        const val HOME = "home"
        const val SECOND = "second"
        const val TYPED = "typed"
        const val TYPE = "type"
    }
}
