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

    /** L'installation du modèle de dictée. Atteinte depuis les réglages. */
    data object VoiceSetup : Destination {
        override val route = "voice-setup"
    }

    /**
     * L'éditeur d'une note existante.
     *
     * ⚠️ [noteId] est **encodé**. Les identifiants sont des UUID aujourd'hui, donc sans caractère
     * réservé — mais une route est une URI, et se reposer sur la forme actuelle des identifiants
     * pour ne pas encoder revient à faire dépendre la navigation d'un détail du modèle de données.
     */
    /**
     * [ecrire]: open the note to be written rather than read (3.1.0) — the selection's "Edit". Absent
     * from the route when false, so every other way into the editor keeps the route it had.
     */
    /**
     * [recherche]: the search of the result the note is opened from (3.2.0), which "Find in note"
     * opens with. Encoded, like [noteId]: it is whatever the user typed. Absent when null or blank.
     */
    /**
     * [dicter]: open the microphone once the note is open (3.4.0, the "Dictation" launcher shortcut,
     * D-041) — as a tap on its button would. Absent from the route when false.
     */
    data class Editor(
        val noteId: String,
        val ecrire: Boolean = false,
        val recherche: String? = null,
        val dicter: Boolean = false,
    ) : Destination {
        override val route = "$EDITOR_PREFIX/${Uri.encode(noteId)}" +
            listOfNotNull(
                "$ARG_ECRIRE=true".takeIf { ecrire },
                recherche?.takeIf { it.isNotBlank() }?.let { "$ARG_RECHERCHE=${Uri.encode(it)}" },
                "$ARG_DICTER=true".takeIf { dicter },
            ).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    }

    companion object {
        const val EDITOR_PREFIX = "editor"
        const val ARG_NOTE_ID = "noteId"
        const val ARG_ECRIRE = "ecrire"
        const val ARG_RECHERCHE = "recherche"
        const val ARG_DICTER = "dicter"

        /** Le motif déclaré au `NavHost`. Une seule déclaration, ici. */
        const val EDITOR_PATTERN =
            "$EDITOR_PREFIX/{$ARG_NOTE_ID}?$ARG_ECRIRE={$ARG_ECRIRE}&$ARG_RECHERCHE={$ARG_RECHERCHE}&$ARG_DICTER={$ARG_DICTER}"
    }
}
