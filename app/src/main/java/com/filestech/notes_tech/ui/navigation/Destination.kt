package com.filestech.notes_tech.ui.navigation

import android.net.Uri

/**
 * Les destinations de l'application, et la seule façon de fabriquer leurs routes.
 *
 * ## Pourquoi pas les routes typées de `navigation-compose`
 *
 * Elles exigent `kotlinx-serialization` — un greffon Gradle et une dépendance de plus, pour sept
 * écrans dont un seul porte un argument. Le rapport ne le justifie pas. Cf. `01-DECISIONS.md` D-016.
 *
 * ⚠️ Et elles portent un piège connu : `launchSingleTop` ignore les arguments d'une route typée, si
 * bien que naviguer d'une note vers une autre ne recompose rien. Le contourner demande autant de
 * soin que d'écrire les routes à la main.
 *
 * ## Ce que cette classe garantit
 *
 * Aucune chaîne de route n'est écrite ailleurs. Un `navigate("editor/$id")` dispersé dans un écran
 * compile parfaitement et se casse au premier renommage, sans que rien ne le signale avant
 * l'exécution. Ici, [Editor.route] est la seule origine possible, et [Companion.EDITOR_PATTERN] la seule
 * déclaration.
 */
sealed interface Destination {

    /** La route effective, arguments compris. */
    val route: String

    data object Home : Destination {
        override val route = "home"
    }

    data object Search : Destination {
        override val route = "search"
    }

    data object Trash : Destination {
        override val route = "trash"
    }

    data object Settings : Destination {
        override val route = "settings"
    }

    data object About : Destination {
        override val route = "about"
    }

    data object Legal : Destination {
        override val route = "legal"
    }

    /**
     * L'éditeur d'une note existante.
     *
     * ⚠️ [noteId] est **encodé**. Les identifiants sont des UUID aujourd'hui, donc sans caractère
     * réservé — mais une route est une URI, et se reposer sur la forme actuelle des identifiants
     * pour ne pas encoder revient à faire dépendre la navigation d'un détail du modèle de données.
     */
    data class Editor(val noteId: String) : Destination {
        override val route = "$EDITOR_PREFIX/${Uri.encode(noteId)}"
    }

    companion object {
        const val EDITOR_PREFIX = "editor"
        const val ARG_NOTE_ID = "noteId"

        /** Le motif déclaré au `NavHost`. Une seule déclaration, ici. */
        const val EDITOR_PATTERN = "$EDITOR_PREFIX/{$ARG_NOTE_ID}"
    }
}
