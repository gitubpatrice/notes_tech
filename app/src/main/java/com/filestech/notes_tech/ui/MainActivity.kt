package com.filestech.notes_tech.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.ui.startup.StartupFailureScreen
import com.filestech.notes_tech.ui.startup.StartupState
import com.filestech.notes_tech.ui.startup.StartupViewModel
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Posé AVANT `super.onCreate` : c'est la condition pour que l'écran de démarrage prenne la
        // main. Après, la fenêtre est déjà créée et l'appel n'a plus d'effet.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotesTechTheme {
                NotesTechApp()
            }
        }
    }
}

@Composable
private fun NotesTechApp() {
    val viewModel: StartupViewModel = hiltViewModel()
    // `collectAsStateWithLifecycle` et non `collectAsState` : sans lui, la collecte continue quand
    // l'application passe en arrière-plan. Pour une application qui verrouille ses coffres sur
    // inactivité, garder des flux actifs hors écran est exactement ce qu'on ne veut pas.
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (val current = state) {
            StartupState.Opening -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            StartupState.Ready -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                // Phase 5 : l'arborescence de navigation prend la place de ce marqueur. Il est
                // volontairement explicite plutôt que joli — personne ne doit le confondre avec un
                // écran d'accueil inachevé.
                Text("Base ouverte — interface en phase 5")
            }

            is StartupState.Failed -> StartupFailureScreen(
                reason = current.reason,
                onRetry = viewModel::retry,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
