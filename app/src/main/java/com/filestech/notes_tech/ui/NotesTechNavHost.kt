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
import com.filestech.notes_tech.ui.voice.VoiceSetupRoute

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
            NoteEditorRoute(
                onBack = { navController.popBackStack() },
                // Ouvrir une note liée **empile** une entrée, comme l'application publiée : le
                // chemin parcouru se remonte lien par lien avec le bouton retour.
                onOpenNote = { navController.navigate(Destination.Editor(it).route) },
                // 🔴 **Le micro sans modèle EMMÈNE ici**, il ne se contente pas de dire qu'il
                // manque quelque chose. Cet écran n'était atteignable que depuis les réglages, et
                // rien dans l'éditeur ne l'indiquait. Cf. `ControleurDeDictee.demarrer`.
                onInstallerLaDictee = { navController.navigate(Destination.VoiceSetup.route) },
            )
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
                onOpenVoiceSetup = { navController.navigate(Destination.VoiceSetup.route) },
            )
        }

        composable(Destination.About.route) {
            AboutRoute(
                onBack = { navController.popBackStack() },
                onOpenLegal = { navController.navigate(Destination.Legal.route) },
            )
        }

        composable(Destination.VoiceSetup.route) {
            VoiceSetupRoute(onBack = { navController.popBackStack() })
        }

        composable(Destination.Legal.route) {
            LegalRoute(onBack = { navController.popBackStack() })
        }
    }
}
