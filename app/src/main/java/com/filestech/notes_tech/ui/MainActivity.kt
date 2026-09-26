package com.filestech.notes_tech.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.prefs.ThemePreference
import com.filestech.notes_tech.security.applock.AppLockLifecycle
import com.filestech.notes_tech.security.applock.AppLockManager
import com.filestech.notes_tech.security.applock.AppLockState
import com.filestech.notes_tech.ui.applock.AppLockRoute
import com.filestech.notes_tech.ui.applock.RecentsGuard
import com.filestech.notes_tech.ui.common.ExternalActivityGuard
import com.filestech.notes_tech.ui.common.LocalExternalActivityGuard
import com.filestech.notes_tech.ui.panic.RecouvrementDePanique
import com.filestech.notes_tech.ui.panic.activityPanicViewModel
import com.filestech.notes_tech.ui.secure.LocalSecureWindow
import com.filestech.notes_tech.ui.secure.SecureWindowController
import com.filestech.notes_tech.ui.splash.SplashScreen
import com.filestech.notes_tech.ui.startup.StartupFailureScreen
import com.filestech.notes_tech.ui.startup.StartupState
import com.filestech.notes_tech.ui.startup.StartupViewModel
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * The single activity.
 *
 * A [FragmentActivity] since the app lock (D-023): `androidx.biometric` attaches its prompt as a
 * fragment. The lifecycle callbacks below feed [AppLockLifecycle], synchronously — see its KDoc.
 */
/**
 * 🔴 **The launch intent, without what could steer the navigation** — security audit of 2026-09-26, E1.
 *
 * `MainActivity` is exported, and Navigation 2.9.8 honours the extras
 * `android-support-nav:controller:deepLinkIds` / `deepLinkExtras` of any caller: another app chose
 * the opening screen, and opening the editor without a note id brought the process down
 * (`NoteEditorViewModel`'s `checkNotNull`). Nothing here reads an extra or a data URI — no deep link,
 * no shortcut, no widget — so the intent keeps its action and categories and loses the rest.
 */
internal fun sansNavigationImposee(recu: Intent): Intent = Intent(recu).apply {
    replaceExtras(null as Bundle?)
    data = null
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var secureWindow: SecureWindowController

    @Inject
    lateinit var appLock: AppLockManager

    @Inject
    lateinit var appLockLifecycle: AppLockLifecycle

    /** One instance for the activity's life: a new one per recomposition would invalidate every screen. */
    private val externalActivityGuard = ExternalActivityGuard { appLockLifecycle.onExternalActivityLaunched() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Posé AVANT `super.onCreate` : c'est la condition pour que l'écran de démarrage prenne la
        // main. Après, la fenêtre est déjà créée et l'appel n'a plus d'effet.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 🔴 Before `setContent`, where Navigation reads it (security audit of 2026-09-26, E1).
        intent = sansNavigationImposee(intent)
        // Before anything is composed, and synchronously: the first frame must already be the lock
        // screen when a lock is configured. Once per process — a recreated activity keeps the state.
        appLock.resolveAtLaunch()
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(
                LocalSecureWindow provides secureWindow,
                LocalExternalActivityGuard provides externalActivityGuard,
            ) {
                NotesTechApp(settings, secureWindow, appLock)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appLockLifecycle.onStart()
    }

    override fun onResume() {
        super.onResume()
        appLockLifecycle.onResume()
    }

    /** A touch or a key: the one sign of a return no other app can fake (audit 2026-09-26, E2). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        appLockLifecycle.onUserInteraction()
    }

    /**
     * ⚠️ `isChangingConfigurations`: the language change recreates the activity on purpose
     * (`SettingsRoute`), and must not ask for the PIN in the middle of the settings.
     */
    override fun onStop() {
        super.onStop()
        appLockLifecycle.onStop(changingConfigurations = isChangingConfigurations)
    }

    /**
     * ⚠️ The new intent is not handed to Navigation, and is not made the activity's own
     * (`setIntent`): nothing a caller puts in it can steer the app — cf. [sansNavigationImposee].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        appLockLifecycle.onNewIntent()
    }

    /**
     * Applique la langue choisie **avant** que le moindre `Context` ne serve à résoudre une
     * ressource.
     *
     * ## Pourquoi ici, et pas par `AppCompatDelegate` ou `LocaleManager`
     *
     * `LocaleManager` (langue par application) n'existe qu'à partir de l'API 33 ; le plancher du
     * projet est 24. `AppCompatDelegate.setApplicationLocales` demanderait AppCompat, que ce
     * portage n'embarque pas — l'activité est une `FragmentActivity` (pour l'invite biométrique, D-023),
     * pas une `AppCompatActivity`, et l'interface est en Compose.
     *
     * ⚠️ **Hilt n'est pas encore prêt à ce stade** : `attachBaseContext` s'exécute avant l'injection
     * de l'activité. La préférence est donc relue directement, avec la même classe et les mêmes
     * clés — pas avec une copie de la logique, ce qui produirait deux réponses possibles à la même
     * question.
     */
    override fun attachBaseContext(newBase: Context) {
        val prefs = AppSettings(LegacyPreferences(newBase))
        // `SYSTEM` (no code) forces nothing: the context stays as Android built it, following the
        // device's setting, even when it changes while the app runs.
        val langue = prefs.localeNow().code?.let(Locale::forLanguageTag)
        super.attachBaseContext(if (langue == null) newBase else newBase.avecLangue(langue))
    }

    private fun Context.avecLangue(locale: Locale): Context {
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        return createConfigurationContext(configuration)
    }
}

@Composable
private fun NotesTechApp(settings: AppSettings, secureWindow: SecureWindowController, appLock: AppLockManager) {
    val theme by settings.theme.collectAsStateWithLifecycle(initialValue = settings.themeNow())
    val fenetreProtegee by secureWindow.active.collectAsStateWithLifecycle(
        // ⚠️ La valeur initiale est LUE. Un défaut à `false` laisserait la fenêtre capturable
        // pendant la fraction de seconde qui précède la première émission du flux — c'est-à-dire
        // exactement le temps que met l'aperçu des applications récentes à se prendre.
        initialValue = secureWindow.activeNow(),
    )

    FenetreProtegee(fenetreProtegee)

    // ⚠️ `collectAsState`, NOT `collectAsStateWithLifecycle`: the lock is decided in `onStop`, and
    // the composition must see it WHILE the activity is stopped. Paused collection would resume only
    // at `onStart`, and the first frame after the return could still be the unlocked content.
    val verrouConfigure by appLock.configured.collectAsState(initial = appLock.isConfigured())
    RecentsGuard(lockConfigured = verrouConfigure)

    NotesTechTheme(
        darkTheme = when (theme) {
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
        },
    ) {
        ContenuPrincipal(settings, appLock, verrouConfigure)
    }
}

@Composable
private fun ContenuPrincipal(settings: AppSettings, appLock: AppLockManager, verrouConfigure: Boolean) {
    val viewModel: StartupViewModel = hiltViewModel()
    // `collectAsStateWithLifecycle` et non `collectAsState` : sans lui, la collecte continue quand
    // l'application passe en arrière-plan. Pour une application qui verrouille ses coffres sur
    // inactivité, garder des flux actifs hors écran est exactement ce qu'on ne veut pas.
    val state by viewModel.state.collectAsStateWithLifecycle()

    // THE panic model of the activity: the overlay below is its only host (settings and lock
    // screen only trigger). `collectAsState` for the same reason as the lock state.
    val panique = activityPanicViewModel()
    val etatDePanique by panique.state.collectAsState()
    val paniqueDeclenchee = etatDePanique.running || etatDePanique.report != null

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

            // 🔴 The app lock guards the NOTES, so it wraps the content and only it (D-023). The
            // opening spinner and the start-up failure screen show nothing of the user's: the failure
            // screen in particular must stay readable, since what it says — do not uninstall, write
            // to support — is what saves the notes when the key is missing.
            //
            // `collectAsState` for the reason given in `NotesTechApp`. And "locked" requires a lock
            // that still EXISTS — a lock screen asking for a PIN that no longer exists is a dead end
            // — EXCEPT while panic mode runs or reports: it clears the preferences, PIN included,
            // two steps before its end, and lifting the lock then would show the notes' screens on a
            // sealed database, under the report.
            StartupState.Ready -> {
                val verrou by appLock.state.collectAsState()
                val verrouille = verrou as? AppLockState.Locked
                LockedAppHost(
                    locked = verrouille != null && (verrouConfigure || paniqueDeclenchee),
                    lockScreen = { AppLockRoute(epoch = verrouille?.epoch ?: 0) },
                ) { navController -> NotesTechNavHost(navController = navController) }
            }

            is StartupState.Failed -> StartupFailureScreen(
                reason = current.reason,
                onRetry = viewModel::retry,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    // Above the lock AND the content, as a sibling of the Scaffold — never inside its scrolling
    // content (the nested-scroll crash of 2026-08-14, `SettingsScreen`).
    RecouvrementDePanique(etatDePanique)
}

/**
 * `FLAG_SECURE` : interdit la capture d'écran et masque l'aperçu dans les applications récentes.
 *
 * ⚠️ **Posé et retiré par le même effet.** Un drapeau posé sans être retiré survivrait à la
 * désactivation du réglage jusqu'au prochain redémarrage — l'utilisateur verrait l'interrupteur à
 * « désactivé » et les captures continueraient d'échouer, sans explication.
 *
 * [active] ne vient plus du réglage seul : c'est la décision de
 * [SecureWindowController], qui compose le réglage utilisateur avec les demandes ponctuelles des
 * écrans sensibles et du mode panique. **Ce composable est le seul endroit du programme qui touche
 * la fenêtre** — c'est ce qui permet au compteur de rester la seule autorité.
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
