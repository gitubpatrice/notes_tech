package com.filestech.notes_tech.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.filestech.notes_tech.ui.about.AboutRoute
import com.filestech.notes_tech.ui.about.LegalRoute
import com.filestech.notes_tech.ui.editor.NoteEditorRoute
import com.filestech.notes_tech.ui.home.HomeRoute
import com.filestech.notes_tech.ui.navigation.Destination
import com.filestech.notes_tech.ui.search.SearchRoute
import com.filestech.notes_tech.ui.settings.SettingsRoute
import com.filestech.notes_tech.ui.trash.TrashRoute

/**
 * L'arborescence de navigation.
 *
 * ⚠️ **Aucune chaîne de route n'est écrite ici** : elles viennent toutes de [Destination]. Un
 * `navigate("editor/$id")` dispersé dans un écran compilerait et se casserait au premier renommage,
 * sans que rien ne le signale avant l'exécution.
 */
@Composable
fun NotesTechNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Destination.Home.route) {
        composable(Destination.Home.route) {
            HomeRoute(
                onOpenNote = { navController.navigate(Destination.Editor(it.id).route) },
                onOpenSearch = { navController.navigate(Destination.Search.route) },
                onOpenTrash = { navController.navigate(Destination.Trash.route) },
                onOpenSettings = { navController.navigate(Destination.Settings.route) },
                onOpenAbout = { navController.navigate(Destination.About.route) },
            )
        }

        composable(
            route = Destination.EDITOR_PATTERN,
            arguments = listOf(navArgument(Destination.ARG_NOTE_ID) { type = NavType.StringType }),
        ) {
            // L'identifiant n'est pas relu ici : `SavedStateHandle` le remet au ViewModel, qui est
            // le seul à en avoir besoin. Le faire transiter par le composable ajouterait un second
            // chemin pour la même valeur.
            NoteEditorRoute(onBack = { navController.popBackStack() })
        }

        composable(Destination.Search.route) {
            SearchRoute(
                onBack = { navController.popBackStack() },
                onOpenNote = { navController.navigate(Destination.Editor(it.id).route) },
            )
        }

        composable(Destination.Trash.route) {
            TrashRoute(onBack = { navController.popBackStack() })
        }

        composable(Destination.Settings.route) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(Destination.About.route) },
                onOpenLegal = { navController.navigate(Destination.Legal.route) },
            )
        }

        composable(Destination.About.route) {
            AboutRoute(
                onBack = { navController.popBackStack() },
                onOpenLegal = { navController.navigate(Destination.Legal.route) },
            )
        }

        composable(Destination.Legal.route) {
            LegalRoute(onBack = { navController.popBackStack() })
        }
    }
}
