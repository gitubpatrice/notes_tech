package com.filestech.notes_tech.ui.splash

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.notes_tech.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * L'écran de présentation Files Tech, joué **au premier lancement seulement**.
 *
 * Portage de `ui/screens/splash_screen.dart`. La mécanique est identique sur les applications du
 * portefeuille, et c'est le but : les durées ci-dessous ne sont pas des réglages, ce sont une
 * signature de marque. Les changer désaligne Notes Tech des huit autres.
 *
 * | Élément | Départ | Durée | Effet |
 * |---|---|---|---|
 * | logo | 0 ms | 900 ms | échelle 0,5 → 1 et opacité 0 → 1, `easeOutCubic` |
 * | titre et accroche | 700 ms | 800 ms | opacité 0 → 1 |
 * | indication « toucher pour continuer » | 2 500 ms | 500 ms | opacité 0 → 0,6 |
 * | fermeture automatique | 5 500 ms | — | — |
 *
 * **Trois portes de sortie** — la touche, le retour, et l'échéance — toutes passant par la même
 * garde d'idempotence. Sans elle, un retour pressé pendant la fermeture automatique produit deux
 * navigations, et la seconde s'applique à l'écran d'accueil déjà affiché.
 *
 * ## 🔒 Ce que cet écran ne fait pas
 *
 * Il ne touche à aucun coffre, ne lit aucune note, et n'ouvre pas la base. Il s'affiche pendant que
 * le reste se prépare, et n'est pas un point de passage privilégié : le contourner ne donne accès à
 * rien.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val finished = rememberUpdatedState(onFinished)

    // ⚠️ `AtomicBoolean` dans un `remember`, et pas un `var` capturé : les trois portes de sortie
    // sont déclenchées depuis des contextes différents (une coroutine d'échéance, un geste, le
    // bouton retour). `compareAndSet` est le seul « une seule fois » qui tienne quel que soit
    // l'ordre.
    val dejaFerme = remember { AtomicBoolean(false) }
    val fermerUneFois = remember {
        {
            if (dejaFerme.compareAndSet(false, true)) finished.value()
        }
    }

    val animationsReduites = remember(context) {
        // WCAG 2.3.3 / RGAA 13.1. `MediaQuery.disableAnimationsOf` côté Flutter lit la même
        // valeur système : une échelle de durée à zéro veut dire « pas d'animation ».
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val logo = remember { Animatable(0f) }
    val accroche = remember { Animatable(0f) }
    val indication = remember { Animatable(0f) }

    LaunchedEffect(animationsReduites) {
        fun duree(millis: Int) = if (animationsReduites) 0 else millis

        launch { logo.animateTo(1f, tween(duree(LOGO_MILLIS), easing = EaseOutCubic)) }
        launch {
            delay(ACCROCHE_DELAI_MILLIS)
            accroche.animateTo(1f, tween(duree(ACCROCHE_MILLIS), easing = EaseOutCubic))
        }
        launch {
            delay(INDICATION_DELAI_MILLIS)
            indication.animateTo(INDICATION_OPACITE, tween(duree(INDICATION_MILLIS)))
        }
        // ⚠️ L'échéance de fermeture n'est PAS raccourcie quand les animations sont réduites, et
        // c'est délibéré : le réglage système dit « ne bouge pas », pas « va plus vite ». La
        // version Flutter fait le même choix, et une divergence ici se verrait au chronomètre.
        delay(FERMETURE_MILLIS)
        fermerUneFois()
    }

    BackHandler(onBack = fermerUneFois)

    val etiquette = stringResource(R.string.splash_semantics_label)
    val indice = stringResource(R.string.splash_skip_hint)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                // Sans `indication = null`, une onde de touche part du point de contact sur tout
                // l'écran — un effet que rien n'appelle et qui n'existe pas dans l'original.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = fermerUneFois,
            )
            .semantics(mergeDescendants = true) { contentDescription = "$etiquette. $indice" },
    ) {
        // `clamp(128, 200)` sur 40 % de la largeur — repris tel quel. Sur une tablette, un logo
        // proportionnel occuperait la moitié de l'écran.
        val tailleLogo = (maxWidth * LOGO_FRACTION).coerceIn(LOGO_MIN, LOGO_MAX)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier
                    .size(tailleLogo)
                    .scale(LOGO_ECHELLE_DEPART + (1f - LOGO_ECHELLE_DEPART) * logo.value)
                    .alpha(logo.value)
                    .clearAndSetSemantics { },
            )
            Column(
                modifier = Modifier.padding(top = 24.dp).alpha(accroche.value),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.app_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.splash_tagline),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 48.dp)
                .alpha(indication.value)
                // L'indication est déjà énoncée par la description du conteneur : la relire en
                // deuxième position ferait dire deux fois la même phrase.
                .clearAndSetSemantics { },
        ) {
            Text(
                text = indice,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val LOGO_MILLIS = 900
private const val ACCROCHE_DELAI_MILLIS = 700L
private const val ACCROCHE_MILLIS = 800
private const val INDICATION_DELAI_MILLIS = 2_500L
private const val INDICATION_MILLIS = 500
private const val INDICATION_OPACITE = 0.6f

/**
 * ⚠️ **5 500 ms depuis l'affichage, pas depuis la dernière animation.**
 *
 * L'attente vit dans la coroutine parente, qui démarre en même temps que les trois `launch`
 * enfants — elle ne les suit pas. Un « 5 500 − 2 500 » aurait l'air d'un enchaînement et fermerait
 * l'écran au bout de trois secondes.
 */
private const val FERMETURE_MILLIS = 5_500L
private const val LOGO_ECHELLE_DEPART = 0.5f
private const val LOGO_FRACTION = 0.4f
private val LOGO_MIN = 128.dp
private val LOGO_MAX = 200.dp
