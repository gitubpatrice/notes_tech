package com.filestech.notes_tech.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
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
 *
 * 🔴 **Every navigation goes through [depuis]**: a screen navigates only while it is the one on top.
 */
@Composable
fun NotesTechNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Destination.Home.route) {
        composable(Destination.Home.route) { entree ->
            HomeRoute(
                onOpenNote = { note, recherche ->
                    navController.depuis(entree) { navigate(Destination.Editor(note.id, recherche = recherche).route) }
                },
                onEditNote = {
                    navController.depuis(entree) { navigate(Destination.Editor(it.id, ecrire = true).route) }
                },
                onOpenSearch = { navController.depuis(entree) { navigate(Destination.Search.route) } },
                onOpenTrash = { navController.depuis(entree) { navigate(Destination.Trash.route) } },
                onOpenSettings = { navController.depuis(entree) { navigate(Destination.Settings.route) } },
                onOpenAbout = { navController.depuis(entree) { navigate(Destination.About.route) } },
            )
        }

        composable(
            route = Destination.EDITOR_PATTERN,
            arguments = listOf(
                navArgument(Destination.ARG_NOTE_ID) { type = NavType.StringType },
                navArgument(Destination.ARG_ECRIRE) {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument(Destination.ARG_RECHERCHE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entree ->
            // L'identifiant n'est pas relu ici : `SavedStateHandle` le remet au ViewModel, qui est
            // le seul à en avoir besoin. Le faire transiter par le composable ajouterait un second
            // chemin pour la même valeur.
            NoteEditorRoute(
                onBack = { navController.depuis(entree) { popBackStack() } },
                // Ouvrir une note liée **empile** une entrée, comme l'application publiée : le
                // chemin parcouru se remonte lien par lien avec le bouton retour.
                onOpenNote = { navController.depuis(entree) { navigate(Destination.Editor(it).route) } },
                // 🔴 **Le micro sans modèle EMMÈNE ici**, il ne se contente pas de dire qu'il
                // manque quelque chose. Cet écran n'était atteignable que depuis les réglages, et
                // rien dans l'éditeur ne l'indiquait. Cf. `ControleurDeDictee.demarrer`.
                onInstallerLaDictee = { navController.depuis(entree) { navigate(Destination.VoiceSetup.route) } },
            )
        }

        composable(Destination.Search.route) { entree ->
            SearchRoute(
                onBack = { navController.depuis(entree) { popBackStack() } },
                // The note opens with "Find in note" on what was searched (3.2.0, D-033).
                onOpenNote = { note, recherche ->
                    navController.depuis(entree) { navigate(Destination.Editor(note.id, recherche = recherche).route) }
                },
            )
        }

        composable(Destination.Trash.route) { entree ->
            TrashRoute(onBack = { navController.depuis(entree) { popBackStack() } })
        }

        composable(Destination.Settings.route) { entree ->
            SettingsRoute(
                onBack = { navController.depuis(entree) { popBackStack() } },
                onOpenAbout = { navController.depuis(entree) { navigate(Destination.About.route) } },
                onOpenVoiceSetup = { navController.depuis(entree) { navigate(Destination.VoiceSetup.route) } },
            )
        }

        composable(Destination.About.route) { entree ->
            AboutRoute(
                onBack = { navController.depuis(entree) { popBackStack() } },
                onOpenLegal = { navController.depuis(entree) { navigate(Destination.Legal.route) } },
            )
        }

        composable(Destination.VoiceSetup.route) { entree ->
            VoiceSetupRoute(onBack = { navController.depuis(entree) { popBackStack() } })
        }

        composable(Destination.Legal.route) { entree ->
            LegalRoute(onBack = { navController.depuis(entree) { popBackStack() } })
        }
    }
}

/**
 * Runs [navigation] only if [entree] is still the screen on top.
 *
 * 🔴 **A screen that is leaving must not navigate again** (3.2.1). During the exit transition
 * (~0.5 s) the screen being left is still drawn and still takes taps. On 3.1.1 and 3.2.0 as
 * published, a second tap on « Fermer » reached `popBackStack()` from the editor already popped,
 * and popped the note list too: an empty NavHost, a WHITE screen, left only by Back. A double tap
 * on a note or a menu entry stacked its screen twice. Measured on the emulator, `DoubleToucherTest`.
 *
 * Not `dropUnlessResumed`: it also drops what the editor navigates on its own while its entry is
 * not resumed yet (a deletion done, the vault-unlock sheet dismissed, a linked note created), and
 * the note would stay on screen. The screen on top may always navigate, whatever its state.
 */
private inline fun NavHostController.depuis(entree: NavBackStackEntry, navigation: NavHostController.() -> Unit) {
    if (currentBackStackEntry === entree) navigation()
}
