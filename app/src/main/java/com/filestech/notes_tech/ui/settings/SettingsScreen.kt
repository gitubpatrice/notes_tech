package com.filestech.notes_tech.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.LocalePreference
import com.filestech.notes_tech.data.prefs.ThemePreference
import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import com.filestech.notes_tech.ui.common.CarteFilesTech
import com.filestech.notes_tech.ui.common.MIME_ZIP
import com.filestech.notes_tech.ui.common.TitreDeSection
import com.filestech.notes_tech.ui.common.libelleDeTri
import com.filestech.notes_tech.ui.common.partagerUnFichier
import com.filestech.notes_tech.ui.panic.PanicConfirmDialog
import com.filestech.notes_tech.ui.panic.PanicOverlay
import com.filestech.notes_tech.ui.panic.PanicUiState
import com.filestech.notes_tech.ui.panic.PanicViewModel
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * Les réglages.
 *
 * ⚠️ **Le délai d'auto-verrouillage n'est pas un confort.** Il décide combien de temps la clé d'un
 * coffre reste en mémoire après la dernière interaction. `0` veut dire « jamais », et c'est un
 * choix légitime que l'écran doit proposer sans le décourager — le verrouillage au passage en
 * arrière-plan reste actif dans tous les cas.
 */
@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenAbout: () -> Unit, onOpenVoiceSetup: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Recreation de l'activite au changement de langue, cf. le commentaire du selecteur ci-dessous.
    val activite = LocalActivity.current

    // La vue et les ressources de l'écran servent à annoncer le changement de langue à un lecteur
    // d'écran. `LocalResources` et non le contexte applicatif : la langue choisie est posée sur le
    // contexte de l'activité, et l'annonce doit être faite dans la langue qu'on vient de choisir.
    val vue = LocalView.current
    val ressourcesDeLEcran = LocalResources.current

    var choixDeTheme by remember { mutableStateOf(false) }
    var choixDeLangue by remember { mutableStateOf(false) }
    var choixDeDelai by remember { mutableStateOf(false) }
    var choixDeTri by remember { mutableStateOf(false) }

    val snackbars = remember { SnackbarHostState() }

    // ⚠️ Le modèle de panique est obtenu ICI, et non dans la ligne qui le déclenche : son
    // recouvrement doit être posé en frère du `Scaffold`, hors de la colonne défilante.
    val panique: PanicViewModel = hiltViewModel()
    val etatDePanique by panique.state.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
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
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                // 16 / 16 / 16 / 40 — les marges de `settings_screen.dart:53`.
                .padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
        ) {
            // ⚠️ **Langue puis Thème**, dans cet ordre. L'application publiée place la langue en
            // premier (`settings_screen.dart:61`) ; le portage les avait inversés. Sur deux écrans
            // qu'on compare côte à côte en phase 8, un ordre inversé se voit avant tout le reste.
            TitreDeSection(stringResource(R.string.settings_section_appearance))
            CarteFilesTech {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_language)) },
                        supportingContent = { Text(stringResource(libelleDeLangue(state.locale))) },
                        leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
                        trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { choixDeLangue = true },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_theme)) },
                        supportingContent = { Text(stringResource(libelleDeTheme(state.theme))) },
                        leadingContent = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
                        trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { choixDeTheme = true },
                    )
                }
            }

            // 🔴 **Le tri revient dans les réglages**, où l'application publiée le place
            // (`settings_screen.dart:68-81`). Le portage l'avait déplacé dans la barre d'accueil et
            // ne l'exposait plus ici. Il est maintenant aux **deux** endroits : `AppSettings.sort`
            // est l'unique source, donc les deux écrans se suivent sans qu'aucun soit maître, et
            // retirer un contrôle qui fonctionne aurait été une perte pour l'utilisateur.
            TitreDeSection(stringResource(R.string.home_sort_mode))
            CarteFilesTech {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_sort_mode)) },
                    supportingContent = { Text(stringResource(libelleDeTri(state.sort))) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { choixDeTri = true },
                )
            }

            TitreDeSection(stringResource(R.string.settings_section_security))
            CarteFilesTech {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_secure_window)) },
                        supportingContent = { Text(stringResource(R.string.settings_secure_window_subtitle)) },
                        leadingContent = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                        trailingContent = {
                            Switch(checked = state.secureWindow, onCheckedChange = viewModel::setSecureWindow)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_vault_auto_lock)) },
                        supportingContent = {
                            Text(
                                if (state.vaultAutoLockMinutes == 0) {
                                    stringResource(R.string.settings_vault_auto_lock_never)
                                } else {
                                    pluralStringResource(
                                        R.plurals.settings_vault_auto_lock_minutes,
                                        state.vaultAutoLockMinutes,
                                        state.vaultAutoLockMinutes,
                                    )
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Outlined.LockClock, contentDescription = null) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { choixDeDelai = true },
                    )
                }
            }

            // ⚠️ La section porte le libellé de son unique ligne, comme dans l'application
            // publiée (`settings_screen.dart:121`). Inventer un titre demanderait une clé i18n
            // nouvelle, donc une modification de l'ARB gelé de `notes_tech` — cf. docs/05-PARITE.md.
            TitreDeSection(stringResource(R.string.settings_export_all))
            CarteFilesTech { LigneDExport(snackbars) }

            TitreDeSection(stringResource(R.string.settings_panic))
            // 🔴 **La seule carte cerclée de rouge de l'écran**, comme dans la référence
            // (`settings_screen.dart:130-140`). Le portage posait cette ligne à plat entre deux
            // séparateurs : rien ne distinguait visuellement la destruction irréversible de
            // l'export ou du choix de thème.
            CarteFilesTech(bordure = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)) {
                LigneDePanique(enCours = etatDePanique.running, onDeclencher = panique::trigger)
            }

            // ⚠️ **Pas de ligne « mentions légales » ici.** Elle n'existe que dans « à propos » côté
            // publié, et l'y dupliquer donnait deux chemins vers le même écran — dont un que la
            // référence n'a pas.
            // ⚠️ La dictée est une SECTION à elle, avant « À propos ». Elle n'a pas d'équivalent
            // dans les réglages publiés — l'écran y est atteint depuis le bouton micro de l'éditeur
            // — mais un modèle qu'on installe une fois et qu'on retire rarement se cherche dans les
            // réglages, pas dans un éditeur de note.
            TitreDeSection(stringResource(R.string.voice_setup_title))
            CarteFilesTech {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.voice_setup_enable)) },
                    supportingContent = { Text(stringResource(R.string.voice_setup_subtitle)) },
                    leadingContent = { Icon(Icons.Outlined.Mic, contentDescription = null) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = onOpenVoiceSetup),
                )
            }

            TitreDeSection(stringResource(R.string.settings_section_about))
            CarteFilesTech {
                ListItem(
                    // `settings_about` et son sous-titre existaient et n'etaient jamais utilises : la
                    // ligne affichait `about_title`, qui est le TITRE DE L'ECRAN, pas son libelle dans
                    // une liste de reglages. Releve par l'audit i18n du 2026-08-14.
                    headlineContent = { Text(stringResource(R.string.settings_about)) },
                    supportingContent = { Text(stringResource(R.string.settings_about_subtitle)) },
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = onOpenAbout),
                )
            }
        }
    }

    RecouvrementDePanique(etatDePanique)

    if (choixDeTheme) {
        DialogueDeChoix(
            titre = stringResource(R.string.settings_theme),
            options = ThemePreference.entries,
            actif = state.theme,
            libelle = { stringResource(libelleDeTheme(it)) },
            onDismiss = { choixDeTheme = false },
            onSelect = {
                choixDeTheme = false
                viewModel.setTheme(it)
            },
        )
    }
    if (choixDeLangue) {
        DialogueDeChoix(
            titre = stringResource(R.string.settings_language),
            options = LocalePreference.entries,
            actif = state.locale,
            libelle = { stringResource(libelleDeLangue(it)) },
            onDismiss = { choixDeLangue = false },
            onSelect = { choisie ->
                choixDeLangue = false
                if (choisie != state.locale) {
                    // ⚠️ **Annoncer AVANT de recréer l'activité.** `settings_language_changed_*`
                    // existaient et n'étaient lues nulle part. Les poser après `recreate()` serait
                    // inutile : la vue qui les prononcerait est déjà détruite. La référence annonce
                    // au même moment (`settings_screen.dart:246-268`).
                    val annonce = when (choisie) {
                        LocalePreference.FRENCH -> R.string.settings_language_changed_fr
                        LocalePreference.ENGLISH -> R.string.settings_language_changed_en
                        LocalePreference.SYSTEM -> null
                    }
                    // `announceForAccessibility` est déprécié et reste le seul moyen d'annoncer un
                    // changement qui n'a **aucun texte à l'écran** pour le porter : la langue vient
                    // de changer, et l'activité va être recréée. Un `liveRegion` de Compose demande
                    // un composable qui change de valeur et survit à l'annonce — il n'y en a pas ici.
                    @Suppress("DEPRECATION")
                    annonce?.let { vue.announceForAccessibility(ressourcesDeLEcran.getString(it)) }
                    viewModel.setLocale(choisie)
                    // 🔴 **La langue s'applique dans `attachBaseContext`, qui ne s'exécute qu'à la
                    // création de l'activité.** Sans cette recréation, l'utilisateur choisit
                    // « English », revient, et tout reste en français jusqu'au prochain démarrage.
                    // Le réglage était bien écrit : c'est son EFFET qui manquait.
                    //
                    // Même motif que le délai d'auto-verrouillage plus tôt dans la phase — un réglage
                    // écrit et jamais relu donne l'affichage du choix, pas le choix. Relevé par une
                    // relecture externe (GPT-5.2, 2026-08-14).
                    activite?.recreate()
                }
            },
        )
    }
    if (choixDeDelai) {
        DialogueDeChoix(
            titre = stringResource(R.string.settings_vault_auto_lock),
            options = DELAIS_PROPOSES,
            actif = state.vaultAutoLockMinutes,
            libelle = { minutes ->
                if (minutes == 0) {
                    stringResource(R.string.settings_vault_auto_lock_never)
                } else {
                    pluralStringResource(R.plurals.settings_vault_auto_lock_minutes, minutes, minutes)
                }
            },
            onDismiss = { choixDeDelai = false },
            onSelect = {
                choixDeDelai = false
                viewModel.setVaultAutoLockMinutes(it)
            },
        )
    }
    if (choixDeTri) {
        DialogueDeChoix(
            titre = stringResource(R.string.home_sort_mode),
            options = NoteSortMode.entries,
            actif = state.sort,
            libelle = { stringResource(libelleDeTri(it)) },
            onDismiss = { choixDeTri = false },
            onSelect = {
                choixDeTri = false
                viewModel.setSort(it)
            },
        )
    }
}

/**
 * La ligne « exporter toutes mes notes », et tout ce qui suit l'appui.
 *
 * ## 🔴 L'ordre des trois gestes, et pourquoi aucun autre ne marche
 *
 * Partager, **consommer**, puis afficher le message **depuis la portée de la composition**.
 *
 * - Consommer est obligatoire : sans cela, une rotation d'écran rejoue l'effet et le sélecteur de
 *   partage se rouvre tout seul.
 * - Mais consommer change la clé de l'effet, donc **annule l'effet lui-même**. Un `showSnackbar`
 *   écrit après ne s'exécute jamais. C'est le piège du porteur d'événement de la phase 5, et il
 *   s'était déjà glissé ici sous un commentaire qui affirmait le contraire de ce que le code
 *   faisait.
 * - Afficher **avant** de consommer ne marche pas non plus : `showSnackbar` suspend jusqu'à la
 *   fermeture du message, et une rotation pendant ces secondes-là relancerait l'effet avec la même
 *   issue non consommée — donc un second partage.
 *
 * D'où `rememberCoroutineScope`, qui est liée à la composition et non à l'effet : elle survit à
 * l'annulation de celui-ci, et le message s'affiche après que l'issue a été consommée.
 */
@Composable
private fun LigneDExport(snackbars: SnackbarHostState) {
    val viewModel: ExportViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val portee = rememberCoroutineScope()

    // ⚠️ Ces ressources-ci, pas celles de l'application : la langue choisie est posée sur le
    // contexte de l'activité par `attachBaseContext`. Un `applicationContext.getString` rendrait la
    // langue du système, et l'archive serait étiquetée dans une langue que l'utilisateur n'a pas
    // choisie.
    //
    // ⚠️ `LocalResources` et non `LocalContext.current.getString` : le premier réagit aux
    // changements de configuration, le second les ignore. Le contexte reste nécessaire pour lancer
    // le partage — c'est son seul rôle ici.
    val ressources = LocalResources.current
    val contexte = LocalContext.current
    val libelleBoiteDeReception = stringResource(R.string.home_folder_inbox)
    val titreDuSelecteur = stringResource(R.string.common_share)

    LaunchedEffect(state.result, state.error) {
        val resultat = state.result
        val erreur = state.error
        when {
            // 🔴 **Une archive VIDE ne se partage pas, elle se signale.**
            //
            // Sans ce test, exporter sans aucune note ouvrait le sélecteur de partage sur un fichier
            // sans contenu : l'utilisateur envoyait une sauvegarde vide en croyant sauvegarder ses
            // notes. La référence pose la question en amont et affiche `homeNoNotes`
            // (`settings_screen.dart:515-519`) ; ici le contrôle est fait sur l'issue, seul endroit
            // où le nombre réellement exporté est connu — le message et l'absence de partage sont
            // les mêmes.
            resultat != null && resultat.exported == 0 && resultat.skippedLocked == 0 -> {
                viewModel.consume()
                portee.launch { snackbars.showSnackbar(ressources.getString(R.string.home_no_notes)) }
            }

            resultat != null -> {
                partagerUnFichier(
                    context = contexte,
                    uri = resultat.uri,
                    mimeType = MIME_ZIP,
                    sujet = ressources.getString(R.string.export_share_subject, resultat.exported),
                    titreDuSelecteur = titreDuSelecteur,
                )
                val message = if (resultat.isComplete) {
                    ressources.getString(R.string.settings_export_done, resultat.exported)
                } else {
                    ressources.getString(
                        R.string.settings_export_done_partial,
                        resultat.exported,
                        resultat.skippedLocked,
                    )
                }
                viewModel.consume()
                portee.launch { snackbars.showSnackbar(message) }
            }

            erreur != null -> {
                val message = ressources.getString(R.string.settings_export_error, erreur)
                viewModel.consume()
                portee.launch { snackbars.showSnackbar(message) }
            }
        }
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_export_all)) },
        supportingContent = { Text(stringResource(R.string.settings_export_subtitle)) },
        leadingContent = { Icon(Icons.Outlined.Archive, contentDescription = null) },
        trailingContent = {
            if (state.busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                // L'infobulle de la référence (`settings_screen.dart:634-637`) : l'icône seule ne
                // dit pas que l'export se termine par un partage.
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(titreDuSelecteur) } },
                    state = rememberTooltipState(),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !state.busy) {
            // 🔴 **Aucune note ⇒ on le dit, on n'exporte pas.**
            //
            // Sans ce test, l'export produisait une archive **vide** et ouvrait le sélecteur de
            // partage : l'utilisateur envoyait un fichier sans contenu en croyant sauvegarder ses
            // notes. La référence affiche `homeNoNotes` et s'arrête (`settings_screen.dart:515-519`).
            viewModel.exportAll(
                inboxLabel = libelleBoiteDeReception,
                // ⚠️ Une fonction, pas un gabarit pré-formaté : la chaîne traduite est résolue
                // avec son argument au moment de l'appel, donc un paramètre ajouté plus tard
                // échoue à la compilation et non en silence.
                vaultMention = { dossier -> ressources.getString(R.string.export_note_from_vault, dossier) },
            )
        },
    )
}

/**
 * La ligne qui ouvre la confirmation du mode panique.
 *
 * ⚠️ **Elle ne porte PAS le recouvrement de destruction**, et c'est le correctif d'un plantage
 * mesuré sur le S9 le 2026-08-14 : cette ligne vit dans une colonne à défilement vertical, et un
 * second défilement vertical imbriqué se mesure avec une hauteur infinie — `IllegalStateException`,
 * application tuée. La destruction, elle, s'était bien exécutée : l'utilisateur se retrouvait sur
 * son écran d'accueil sans savoir si ses notes avaient été effacées.
 *
 * Le recouvrement est donc posé par [SettingsRoute], en frère du `Scaffold`. Cf. `docs/04-PIEGES.md`.
 */
@Composable
private fun LigneDePanique(enCours: Boolean, onDeclencher: () -> Unit) {
    var confirmation by remember { mutableStateOf(false) }
    val retour = LocalHapticFeedback.current

    ListItem(
        headlineContent = {
            Text(
                text = stringResource(R.string.settings_panic),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        },
        supportingContent = { Text(stringResource(R.string.settings_panic_subtitle)) },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.LocalFireDepartment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = {
            // ⚠️ Le rouage d'attente remplace le chevron pendant la destruction, comme dans la
            // référence. Sans lui, la ligne paraissait inerte alors que l'effacement courait —
            // et `etatDePanique.running` existait déjà sans que rien ne l'affiche ici.
            if (enCours) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !enCours) {
            // Un retour haptique appuyé avant la confirmation : le geste qui suit détruit des
            // données, et la référence le marque de la même façon (`settings_screen.dart:675`).
            retour.performHapticFeedback(HapticFeedbackType.LongPress)
            confirmation = true
        },
    )

    if (confirmation) {
        PanicConfirmDialog(
            onDismiss = { confirmation = false },
            onConfirmed = {
                confirmation = false
                onDeclencher()
            },
        )
    }
}

/**
 * Le recouvrement plein écran de la destruction.
 *
 * ## ⚠️ Posé en frère du `Scaffold`, jamais dans son contenu
 *
 * Il défile verticalement, et le contenu des réglages aussi. Imbriquer les deux fait mesurer le
 * second avec une hauteur infinie et tue l'application — mesuré sur appareil, après que la
 * destruction avait déjà eu lieu.
 *
 * ## ⚠️ Un recouvrement, pas une destination de navigation
 *
 * Une destination serait quittable par le bouton retour, par le geste système, par une restauration
 * d'état. Or il n'y a rien à quitter : la clé est détruite, les notes ne reviendront pas, et une
 * sortie ne ferait que laisser croire à une annulation.
 */
@Composable
private fun RecouvrementDePanique(state: PanicUiState) {
    val activite = LocalActivity.current

    if (state.running || state.report != null) {
        PanicOverlay(
            running = state.running,
            report = state.report,
            // 🔴 Fermer l'activité NE SUFFIT PAS, et l'oublier casserait le lancement suivant.
            //
            // `finishAndRemoveTask` d'abord : `finish` seul laisserait la tâche dans l'aperçu des
            // applications récentes. `FLAG_SECURE` en noircit la vignette, mais l'entrée resterait
            // — une trace visible de l'application, juste après avoir passé dix secondes à en
            // effacer les traces.
            //
            // Puis `exitProcess`, et c'est le point non évident : la base est **scellée** dans un
            // objet unique du graphe d'injection, qui vit aussi longtemps que le processus. Un
            // relancement sans mort du processus retrouverait ce sceau et refuserait d'ouvrir la
            // base, sans rien expliquer. Terminer le processus rend au lancement suivant sa
            // qualité de premier lancement — ce que l'écran promet juste au-dessus.
            //
            // Aucune écriture n'est en attente : la panique a tout confirmé par `commit()`.
            onClose = {
                activite?.finishAndRemoveTask()
                exitProcess(0)
            },
        )
    }
}

@Composable
private fun <T> DialogueDeChoix(
    titre: String,
    options: List<T>,
    actif: T,
    libelle: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titre) },
        text = {
            Column {
                for (option in options) {
                    ListItem(
                        headlineContent = { Text(libelle(option)) },
                        // `onClick = null` sur le bouton radio : c'est la ligne entière qui est
                        // cliquable, et un second point de contact ferait deux cibles pour un seul
                        // choix — l'une d'elles plus petite que le minimum accessible.
                        leadingContent = { RadioButton(selected = option == actif, onClick = null) },
                        modifier = Modifier.clickable { onSelect(option) },
                    )
                }
            }
        },
        confirmButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_close), onClick = onDismiss)
        },
    )
}

private fun libelleDeTheme(value: ThemePreference): Int = when (value) {
    ThemePreference.SYSTEM -> R.string.settings_theme_system
    ThemePreference.LIGHT -> R.string.settings_theme_light
    ThemePreference.DARK -> R.string.settings_theme_dark
}

private fun libelleDeLangue(value: LocalePreference): Int = when (value) {
    LocalePreference.SYSTEM -> R.string.settings_language_system
    LocalePreference.FRENCH -> R.string.settings_language_fr
    LocalePreference.ENGLISH -> R.string.settings_language_en
}

/**
 * Les délais proposés, en minutes. `0` = jamais.
 *
 * ⚠️ **`1` a été retiré le 2026-08-15.** La liste de l'application publiée est `[0, 5, 15, 30, 60]`
 * (`settings_screen.dart:780`), et le commentaire d'origine affirmait « repris de l'application
 * publiée » — ce qui était faux. Un commentaire qui certifie une parité inexistante est pire qu'une
 * divergence signalée : il empêche de la voir.
 */
private val DELAIS_PROPOSES = listOf(0, 5, 15, 30, 60)
