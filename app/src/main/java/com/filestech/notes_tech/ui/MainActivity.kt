package com.filestech.notes_tech.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.prefs.LocalePreference
import com.filestech.notes_tech.data.prefs.ThemePreference
import com.filestech.notes_tech.ui.splash.SplashScreen
import com.filestech.notes_tech.ui.startup.StartupFailureScreen
import com.filestech.notes_tech.ui.startup.StartupState
import com.filestech.notes_tech.ui.startup.StartupViewModel
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        // Posé AVANT `super.onCreate` : c'est la condition pour que l'écran de démarrage prenne la
        // main. Après, la fenêtre est déjà créée et l'appel n'a plus d'effet.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotesTechApp(settings)
        }
    }

    /**
     * Applique la langue choisie **avant** que le moindre `Context` ne serve à résoudre une
     * ressource.
     *
     * ## Pourquoi ici, et pas par `AppCompatDelegate` ou `LocaleManager`
     *
     * `LocaleManager` (langue par application) n'existe qu'à partir de l'API 33 ; le plancher du
     * projet est 24. `AppCompatDelegate.setApplicationLocales` demanderait AppCompat, que ce
     * portage n'embarque pas — l'activité est une `ComponentActivity` et l'interface est en Compose.
     *
     * ⚠️ **Hilt n'est pas encore prêt à ce stade** : `attachBaseContext` s'exécute avant l'injection
     * de l'activité. La préférence est donc relue directement, avec la même classe et les mêmes
     * clés — pas avec une copie de la logique, ce qui produirait deux réponses possibles à la même
     * question.
     */
    override fun attachBaseContext(newBase: Context) {
        val prefs = AppSettings(LegacyPreferences(newBase))
        val langue = when (prefs.localeNow()) {
            LocalePreference.FRENCH -> Locale.FRENCH
            LocalePreference.ENGLISH -> Locale.ENGLISH
            // `SYSTEM` ne force rien : on laisse le contexte tel qu'Android l'a construit, ce qui
            // suit le réglage de l'appareil, y compris quand il change en cours d'exécution.
            LocalePreference.SYSTEM -> null
        }
        super.attachBaseContext(if (langue == null) newBase else newBase.avecLangue(langue))
    }

    private fun Context.avecLangue(locale: Locale): Context {
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        return createConfigurationContext(configuration)
    }
}

@Composable
private fun NotesTechApp(settings: AppSettings) {
    val theme by settings.theme.collectAsStateWithLifecycle(initialValue = settings.themeNow())
    val secureWindow by settings.secureWindow.collectAsStateWithLifecycle(
        // ⚠️ La valeur initiale est LUE. Un défaut à `false` laisserait la fenêtre capturable
        // pendant la fraction de seconde qui précède la première émission du flux — c'est-à-dire
        // exactement le temps que met l'aperçu des applications récentes à se prendre.
        initialValue = settings.secureWindowNow(),
    )

    FenetreProtegee(secureWindow)

    NotesTechTheme(
        darkTheme = when (theme) {
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
        },
    ) {
        ContenuPrincipal(settings)
    }
}

@Composable
private fun ContenuPrincipal(settings: AppSettings) {
    val viewModel: StartupViewModel = hiltViewModel()
    // `collectAsStateWithLifecycle` et non `collectAsState` : sans lui, la collecte continue quand
    // l'application passe en arrière-plan. Pour une application qui verrouille ses coffres sur
    // inactivité, garder des flux actifs hors écran est exactement ce qu'on ne veut pas.
    val state by viewModel.state.collectAsStateWithLifecycle()

    // `rememberSaveable` : l'écran de présentation ne doit pas rejouer à chaque rotation. Le
    // drapeau persistant, lui, ne répond qu'à « l'a-t-il déjà vu une fois », pas à « est-il en
    // train de le voir ».
    var splashTermine by rememberSaveable { mutableStateOf(!settings.shouldShowSplash()) }

    if (!splashTermine) {
        SplashScreen(
            onFinished = {
                splashTermine = true
                settings.markSplashShown()
            },
        )
        return
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (val current = state) {
            StartupState.Opening -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            StartupState.Ready -> NotesTechNavHost(navController = rememberNavController())

            is StartupState.Failed -> StartupFailureScreen(
                reason = current.reason,
                onRetry = viewModel::retry,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * `FLAG_SECURE` : interdit la capture d'écran et masque l'aperçu dans les applications récentes.
 *
 * ⚠️ **Posé et retiré par le même effet.** Un drapeau posé sans être retiré survivrait à la
 * désactivation du réglage jusqu'au prochain redémarrage — l'utilisateur verrait l'interrupteur à
 * « désactivé » et les captures continueraient d'échouer, sans explication.
 *
 * ⚠️ Ce n'est PAS un compteur de références ici, et ce sera à revoir en phase 6 : le mode panique
 * et l'éditeur poseront le même drapeau pour leurs propres raisons, et deux poseurs indépendants
 * qui le retirent chacun de leur côté font disparaître la protection du premier quand le second se
 * termine. Le compteur est listé dans `docs/00-PLAN.md` phase 6.
 */
@Composable
private fun FenetreProtegee(active: Boolean) {
    val contexte = LocalContext.current
    val fenetre = remember(contexte) { (contexte as? ComponentActivity)?.window }
    DisposableEffect(fenetre, active) {
        if (active) {
            fenetre?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            fenetre?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }
}
