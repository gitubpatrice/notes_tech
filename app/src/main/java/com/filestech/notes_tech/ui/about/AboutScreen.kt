package com.filestech.notes_tech.ui.about

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.notes_tech.BuildConfig
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.ouvrirUnLien
import kotlinx.coroutines.launch

/**
 * L'écran « à propos », porté de `ui/screens/about_screen.dart`.
 *
 * ## 🔴 Cet écran était une ébauche, et 26 chaînes le disaient
 *
 * Le portage n'affichait que le nom, la version et une phrase de confidentialité sur cinq. Les
 * vingt-six autres chaînes `about_*`, traduites dans les deux langues, n'étaient **référencées
 * nulle part** — le signal qui, sur ce dépôt, a déjà révélé deux fonctionnalités annoncées et non
 * câblées. Relevé le 2026-08-15, sur demande de parité de Patrice.
 *
 * ## L'ordre des sections vient de la référence, pas d'un choix
 *
 * En-tête · Confidentialité · Dictée vocale · Sources et licences · Auteur et contact · Mentions
 * légales. Le réordonner ferait diverger deux écrans que la phase 8 doit comparer côte à côte.
 *
 * ## ⚠️ La section « Dictée vocale » est affichée alors que la dictée n'existe pas encore
 *
 * Ses trois arguments et sa notice décrivent une capacité de la version publiée, que la phase 7
 * portera. Ils sont **descriptifs**, ne dépendent d'aucun service, et l'application publiée les
 * affiche à l'identique. Les retirer créerait un écart de plus à réconcilier ; les laisser affiche
 * un texte exact sur une fonction que la 3.0.0 devra livrer — c'est le point à trancher noté dans
 * `docs/00-PLAN.md` §0.
 */
@Composable
fun AboutRoute(onBack: () -> Unit, onOpenLegal: () -> Unit) {
    val messages = remember { SnackbarHostState() }
    val portee = rememberCoroutineScope()
    val contexte = LocalContext.current
    val ressources = LocalResources.current

    val signalerLaCopie = {
        portee.launch { messages.showSnackbar(ressources.getString(R.string.about_link_copied)) }
        Unit
    }
    val ouvrir: (String) -> Unit = { url -> ouvrirUnLien(contexte, url, signalerLaCopie) }

    Scaffold(
        snackbarHost = { SnackbarHost(messages) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.about_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                // 16 / 24 / 16 / 40 — les marges de `about_screen.dart:41`. Le bas généreux existe
                // pour que la dernière carte ne colle pas au bord de l'écran.
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 40.dp),
        ) {
            EnTete(onVerifierLesMisesAJour = { ouvrir(URL_DERNIERE_VERSION) })

            TitreDeSection(stringResource(R.string.about_section_privacy))
            CarteDeConfidentialite()

            TitreDeSection(stringResource(R.string.about_section_voice))
            SectionDictee()

            TitreDeSection(stringResource(R.string.about_section_licenses))
            SectionLicences(ouvrir)

            TitreDeSection(stringResource(R.string.about_section_contact))
            SectionContact(ouvrir)

            TitreDeSection(stringResource(R.string.about_section_legal))
            CarteFilesTech {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_legal_link)) },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.about_legal_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Gavel, contentDescription = null) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = onOpenLegal),
                )
            }
        }
    }
}

/**
 * Le bloc d'identité : logo, nom, version, accroche, et la vérification des mises à jour.
 *
 * ⚠️ **La pastille de version porte un « v » littéral**, comme la référence
 * (`about_screen.dart:288`). Ce n'est pas une chaîne traduite : `v2.0.3` s'écrit pareil dans les
 * deux langues, et en faire une ressource ajouterait une clé que l'ARB n'a pas.
 */
@Composable
private fun EnTete(onVerifierLesMisesAJour: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            // La même image que l'écran de présentation, et que l'application publiée.
            painter = painterResource(R.drawable.ic_splash_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)),
        )
        Text(
            text = stringResource(R.string.app_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
        )
        FilledTonalButton(onClick = onVerifierLesMisesAJour, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.about_check_updates),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            // ⚠️ Ce texte dit que l'application ne se connecte jamais d'elle-même. C'est **vrai** et
            // vérifiable : le manifeste ne déclare pas `INTERNET`, et la CI le contrôle sur le
            // manifeste fusionné. Le bouton ci-dessus ouvre un navigateur, il ne charge rien ici.
            text = stringResource(R.string.about_check_updates_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/**
 * La promesse de confidentialité, affichée telle qu'elle est annoncée publiquement.
 *
 * ⚠️ Ce texte est **une promesse vérifiable**, pas un argument commercial : l'absence de permission
 * réseau est contrôlée par la CI sur le manifeste fusionné. Le modifier sans que le manifeste
 * change ferait mentir l'écran.
 */
@Composable
private fun CarteDeConfidentialite() {
    CarteFilesTech {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = CouleursDeBadge.Vert,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.about_privacy_card_title),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                )
            }
            RangeeDeBadges(
                modifier = Modifier.padding(top = 10.dp),
                badges = listOf(
                    Badge(Icons.Outlined.CloudOff, R.string.about_privacy_1, CouleursDeBadge.Vert),
                    Badge(Icons.Outlined.AccountCircle, R.string.about_privacy_2, CouleursDeBadge.Bleu),
                    Badge(Icons.Outlined.BarChart, R.string.about_privacy_3, CouleursDeBadge.Orange),
                    Badge(Icons.Outlined.Lock, R.string.about_privacy_4, CouleursDeBadge.Violet),
                    Badge(Icons.Outlined.VisibilityOff, R.string.about_privacy_5, CouleursDeBadge.Turquoise),
                ),
            )
        }
    }
}

@Composable
private fun SectionDictee() {
    LigneDeCaracteristique(Icons.Outlined.MicNone, R.string.about_voice_1)
    LigneDeCaracteristique(Icons.Outlined.Shield, R.string.about_voice_2)
    LigneDeCaracteristique(Icons.Outlined.DeleteSweep, R.string.about_voice_3)

    // ⚠️ Un `Surface` teinté et non une carte bordée : la référence emploie ici un `Container` sur
    // `surfaceContainerHighest`, pas un `Card` (`about_screen.dart:529-580`). L'écart de contenant
    // est ce qui distingue la notice des caractéristiques au-dessus.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.about_notice_title),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.semantics { heading() },
            )
            listOf(
                R.string.about_notice_step_1,
                R.string.about_notice_step_2,
                R.string.about_notice_step_3,
                R.string.about_notice_step_4,
                R.string.about_notice_step_5,
            ).forEachIndexed { index, etape ->
                Row(Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(etape),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLicences(ouvrir: (String) -> Unit) {
    CarteFilesTech {
        Column {
            LigneDeLien(R.string.about_link_repo, URL_DEPOT, ouvrir)
            LigneDeLien(R.string.about_link_voice, URL_DEPOT_VOIX, ouvrir)
            LigneDeLien(R.string.about_link_whisper, URL_WHISPER, ouvrir)
        }
    }
    RangeeDeBadges(
        modifier = Modifier.padding(top = 8.dp),
        badges = listOf(
            Badge(Icons.Outlined.Gavel, R.string.about_license, CouleursDeBadge.Ardoise),
            Badge(Icons.Outlined.AttachMoney, R.string.about_free, CouleursDeBadge.Vert),
        ),
    )
}

@Composable
private fun SectionContact(ouvrir: (String) -> Unit) {
    CarteFilesTech {
        Column {
            LigneDeLien(null, URL_SITE, ouvrir, titreLitteral = NOM_EDITEUR, icone = Icons.Outlined.Public)
            LigneDeLien(R.string.about_contact_email, MAILTO_CONTACT, ouvrir, icone = Icons.Outlined.MailOutline)
        }
    }
    CarteFilesTech(modifier = Modifier.padding(top = 8.dp)) {
        ListItem(
            headlineContent = { Text(AUTEUR) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.about_contact_questions),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            leadingContent = {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(percent = 50),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/**
 * Une carte du portefeuille : fond `surface`, **bordure**, rayon 12, aucune ombre.
 *
 * ⚠️ La bordure n'est pas décorative. `theme.dart:114-122` la pose sur toutes les cartes parce que
 * `surface` et le fond de page sont **proches** dans cette palette — sans elle, une carte ne se
 * distingue pas de la page, particulièrement en thème sombre où `#161B22` frôle `#0D1117`.
 */
@Composable
private fun CarteFilesTech(modifier: Modifier = Modifier, contenu: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
        content = contenu,
    )
}

/**
 * Un titre de section : `primary`, gras, 15 sp.
 *
 * Repris de `_SectionTitle` (`about_screen.dart:357-382`), y compris l'espacement de 24 au-dessus
 * et de 8 en dessous — ce sont eux qui donnent son rythme à la page.
 */
@Composable
private fun TitreDeSection(titre: String) {
    Text(
        text = titre,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 2.dp).semantics { heading() },
    )
}

@Composable
private fun LigneDeCaracteristique(icone: ImageVector, texte: Int) {
    CarteFilesTech(modifier = Modifier.padding(bottom = 6.dp)) {
        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(texte),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            },
            leadingContent = {
                Icon(
                    imageVector = icone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/**
 * Une ligne qui ouvre un lien externe.
 *
 * ⚠️ L'adresse est **affichée** en sous-titre, comme dans la référence. Un lien dont on ne voit pas
 * la destination est un lien qu'on n'a pas de raison de suivre.
 */
@Composable
private fun LigneDeLien(
    titre: Int?,
    url: String,
    ouvrir: (String) -> Unit,
    titreLitteral: String? = null,
    icone: ImageVector = Icons.Outlined.Code,
) {
    ListItem(
        headlineContent = {
            Text(
                text = titreLitteral ?: stringResource(titre!!),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent = {
            Text(
                text = url.removePrefix("https://").removePrefix("mailto:"),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        leadingContent = { Icon(icone, contentDescription = null) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { ouvrir(url) },
    )
}

private data class Badge(val icone: ImageVector, val texte: Int, val couleur: Color)

/**
 * Les pastilles colorées.
 *
 * ## ⚠️ Le TEXTE est en `onSurface`, jamais dans la couleur de la pastille
 *
 * C'est un correctif de contraste de l'application publiée (`about_screen.dart:466-487`) : ces six
 * teintes sont choisies pour être lisibles **en icône de 14 px**, pas en texte de 11 sp. Les
 * reprendre pour le libellé ferait passer plusieurs badges sous le seuil WCAG. La couleur ne porte
 * donc que l'icône, le fond à 12 % et la bordure à 30 %.
 */
@Composable
private fun RangeeDeBadges(badges: List<Badge>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        badges.forEach { badge ->
            Surface(
                color = badge.couleur.copy(alpha = 0.12f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, badge.couleur.copy(alpha = 0.3f)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = badge.icone,
                        contentDescription = null,
                        tint = badge.couleur,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(badge.texte),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Les six teintes des pastilles, reprises de `about_screen.dart`.
 *
 * ⚠️ Elles ne sont **pas** dans `ColorScheme` et n'ont rien à y faire : aucun rôle Material ne veut
 * dire « hors ligne » ou « gratuit ». Elles sont fixes dans les deux thèmes, comme côté Flutter, et
 * ne servent qu'à teinter une icône, un fond à 12 % et une bordure à 30 % — jamais du texte.
 */
private object CouleursDeBadge {
    val Vert = Color(0xFF43A047)
    val Bleu = Color(0xFF1976D2)
    val Orange = Color(0xFFFF7043)
    val Violet = Color(0xFF7B1FA2)
    val Turquoise = Color(0xFF00897B)
    val Ardoise = Color(0xFF546E7A)
}

/** Les adresses de la référence, littérales des deux côtés (`about_screen.dart:126-183`, `:342`). */
private const val URL_DEPOT = "https://github.com/gitubpatrice/notes_tech"
private const val URL_DERNIERE_VERSION = "$URL_DEPOT/releases/latest"
private const val URL_DEPOT_VOIX = "https://github.com/gitubpatrice/files_tech_voice"
private const val URL_WHISPER = "https://huggingface.co/ggerganov/whisper.cpp"
private const val URL_SITE = "https://www.files-tech.com"
private const val MAILTO_CONTACT = "mailto:contact@files-tech.com"

/** `AppConstants.appAuthor` et l'éditeur — littéraux côté Flutter, littéraux ici. */
private const val AUTEUR = "Patrice Haltaya"
private const val NOM_EDITEUR = "Files Tech"
