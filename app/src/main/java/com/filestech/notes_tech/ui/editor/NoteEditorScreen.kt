package com.filestech.notes_tech.ui.editor

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.EmptyState
import com.filestech.notes_tech.ui.common.MIME_MARKDOWN
import com.filestech.notes_tech.ui.common.partagerUnFichier
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.vault.UnlockVaultSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * L'éditeur d'une note.
 *
 * Le titre et le contenu sont deux champs sans décoration, pour que la note ressemble à du texte et
 * non à un formulaire — c'est la mise en page de la version publiée.
 */
@Composable
fun NoteEditorRoute(onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val viewModel: NoteEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liens by viewModel.liens.collectAsStateWithLifecycle()

    // ⚠️ **Pas de vidage explicite avant de naviguer**, contrairement à l'application publiée qui
    // appelle `_flushSave()` dans `_openLinkedNote`. Ici, ouvrir une note empile une entrée et
    // **dispose** ce composable-ci : le `DisposableEffect` ci-dessous déclenche l'enregistrement, sur
    // la portée applicative et sous `NonCancellable`, donc il aboutit quoi qu'il arrive ensuite.
    // Ajouter un second appel ne ferait que dupliquer un geste que la comparaison de texte rendrait
    // sans effet — et ferait croire, à la relecture, que le premier chemin ne suffit pas.
    val ouvrirUneAutreNote: (String) -> Unit = { cible ->
        // Une note qui se cite elle-même ne s'ouvre pas par-dessus elle-même. Le cas ne devrait pas
        // remonter — l'indexation annule le lien d'une note vers elle-même — mais la garde coûte une
        // ligne et l'empilement qu'elle évite serait déroutant.
        if (cible != state.note?.id) onOpenNote(cible)
    }

    var autocompletionOuverte by rememberSaveable { mutableStateOf(false) }
    val suggestions by viewModel.suggestionsDeLien.collectAsStateWithLifecycle()

    // ⚠️ Fermer remet la recherche à zéro. Sans cela, rouvrir la feuille afficherait les résultats
    // de la fois précédente le temps du freinage — et l'utilisateur pourrait taper sur l'un d'eux.
    val fermerLAutocompletion = {
        autocompletionOuverte = false
        viewModel.reinitialiserLaRecherche()
    }

    var deplacementOuvert by rememberSaveable { mutableStateOf(false) }

    // Les deux détours du déplacement, retenus par **identifiant** pour survivre à une rotation et
    // à une mort de processus — un `Folder` ne se met pas dans un `Bundle`.
    var sortieDeCoffreCible by rememberSaveable { mutableStateOf<String?>(null) }
    var deverrouillageCible by rememberSaveable { mutableStateOf<String?>(null) }
    val dossiers by viewModel.dossiers.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }
    val retourHaptique = LocalHapticFeedback.current
    val contexte = LocalContext.current
    val ressources = LocalResources.current
    val portee = rememberCoroutineScope()

    AnnonceDEnregistrement(enregistrement = state.saving, echec = state.saveFailed || state.lostToVaultLock)

    // ⚠️ **Une fonction, pas un gabarit pré-formaté.** Passer « %s » puis formater casserait en
    // silence le jour où la chaîne traduite gagne un paramètre — relevé par l'audit i18n du
    // 2026-08-14 sur l'export des réglages, et la même forme est reprise ici.
    val mentionDeCoffre: (String) -> String = { nom -> ressources.getString(R.string.export_note_from_vault, nom) }
    val titreDuSelecteur = stringResource(R.string.common_share)

    IssueDUneAction(
        action = action,
        contexte = contexte,
        ressources = ressources,
        titreDuSelecteur = titreDuSelecteur,
        messages = messages,
        portee = portee,
        onConsommer = viewModel::consommerLAction,
        onBack = onBack,
    )

    if (deplacementOuvert) {
        FeuilleDeDeplacement(
            dossiers = dossiers,
            dossierActuel = state.note?.folderId,
            onChoisir = { cible ->
                deplacementOuvert = false
                // 🔴 **Deux détours possibles avant d'écrire, et ils s'excluent l'un l'autre** :
                // le premier ne concerne qu'une destination coffre, le second qu'une destination
                // ordinaire. Il n'y a donc rien à enchaîner — un seul des deux peut s'appliquer.
                val destination = dossiers.firstOrNull { it.id == cible }
                when {
                    // La destination est un coffre fermé : demander le secret AVANT de toucher au
                    // contenu. Sans ce détour, le scellement échoue faute de clé et l'utilisateur
                    // reçoit un message d'erreur là où il fallait lui poser une question.
                    destination != null && destination.isVault && !viewModel.estDeverrouille(cible) ->
                        deverrouillageCible = cible

                    // Sortie de coffre : la seule des quatre combinaisons qui retire une protection.
                    state.note?.isLocked == true && destination?.isVault != true -> sortieDeCoffreCible = cible

                    else -> viewModel.deplacerVers(cible)
                }
            },
            onDismiss = { deplacementOuvert = false },
        )
    }

    sortieDeCoffreCible?.let { cible ->
        DialogueDeSortieDeCoffre(
            onConfirmer = {
                sortieDeCoffreCible = null
                viewModel.deplacerVers(cible, sortieDeCoffreConfirmee = true)
            },
            onAnnuler = { sortieDeCoffreCible = null },
        )
    }

    // ⚠️ La feuille est cherchée dans la liste à l'affichage, et non mémorisée telle quelle : un
    // `Folder` n'est pas `Saveable`, et c'est son identifiant qui doit survivre à une rotation.
    // Si le dossier a disparu entre-temps, il n'y a plus de destination et l'état se referme.
    deverrouillageCible?.let { cible ->
        val destination = dossiers.firstOrNull { it.id == cible }
        if (destination == null) {
            deverrouillageCible = null
        } else {
            UnlockVaultSheet(
                folder = destination,
                onDismiss = { deverrouillageCible = null },
                onUnlocked = {
                    deverrouillageCible = null
                    viewModel.deplacerVers(cible)
                },
            )
        }
    }

    if (autocompletionOuverte) {
        FeuilleDAutocompletion(
            suggestions = suggestions,
            onRequeteChange = viewModel::chercherUnTitre,
            onChoisirUnTitre = { titre ->
                viewModel.insererUnLien(titre)
                fermerLAutocompletion()
            },
            onCreer = { titre ->
                viewModel.creerPuisLier(titre)
                fermerLAutocompletion()
            },
            onDismiss = fermerLAutocompletion,
        )
    }

    // ⚠️ Le contenu déchiffré d'une note de coffre est à l'écran, en clair, pendant tout le temps
    // où on la lit. Le drapeau est donc forcé pour cet écran-là, même si le réglage est désactivé —
    // c'est le seul moment de l'application où le secret d'un coffre est lisible.
    //
    // La condition suit l'état : une note qui cesse d'être coffrée rend la demande d'elle-même.
    SecureWindowGuard(active = state.isVaultNote)

    // ⚠️ L'enregistrement au départ passe par `DisposableEffect`, pas par le bouton retour seul :
    // on quitte aussi par le geste système, par une navigation, ou parce que le processus se
    // termine. Un seul de ces chemins couvert, c'est du texte perdu par les trois autres.
    DisposableEffect(Unit) {
        onDispose { viewModel.saveNow() }
    }
    BackHandler {
        viewModel.saveNow()
        onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(messages) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.saveNow()
                            onBack()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_close),
                        )
                    }
                },
                title = {
                    TitreDeLEditeur(
                        dossier = state.folder?.name.orEmpty(),
                        enregistrement = state.saving,
                        // 🔴 **`loadError` compte comme un échec pour cette ligne.** Sans lui,
                        // l'écran affichait « Enregistré », coche comprise, **par-dessus un état
                        // vide qui dit « impossible d'ouvrir »** — deux récits concurrents, et le
                        // rassurant était le faux. Relevé CONFIRMÉ par une relecture externe
                        // (GPT-5.2, 2026-08-15).
                        echec = state.saveFailed || state.lostToVaultLock || state.loadError != null,
                    )
                },
                actions = {
                    val note = state.note
                    // ⚠️ **Rien à faire sur une note qu'on n'a pas pu ouvrir.** `loadError` laisse
                    // `note` renseignée — c'est utile au diagnostic — mais épingler, mettre en
                    // favori ou insérer un lien dans un contenu qu'on n'a jamais déchiffré n'a aucun
                    // sens, et « Terminé » n'aurait rien à enregistrer. Même relecture.
                    if (note != null && state.lockedVault == null && state.loadError == null) {
                        // 🔴 **Un bouton « Terminé » visible, alors que l'enregistrement est
                        // automatique.**
                        //
                        // Ce n'est pas redondant avec la flèche de retour : l'application publiée
                        // l'a ajouté sur un retour utilisateur daté (`note_editor_screen.dart:988`),
                        // parce que *sans bouton visible, on ne sait pas qu'on peut quitter sans
                        // risque*. Une note qui s'enregistre toute seule demande un signal explicite
                        // de fin, sinon l'utilisateur reste sur l'écran à chercher « Enregistrer ».
                        //
                        // Le geste est le même que celui de la flèche — vidage puis retour — et c'est
                        // voulu : deux chemins pour un seul geste, pas deux comportements.
                        // ⚠️⚠️ **Une icône, pas un bouton libellé** — mesuré sur le S9 : un
                        // `FilledTonalButton` portant le mot « Terminé » fait cinq éléments d'action,
                        // et le titre est alors écrasé **à zéro pixel**. Le nom du dossier et l'état
                        // de l'enregistrement disparaissaient purement et simplement. L'application
                        // publiée peut se le permettre parce que son titre EST l'indicateur, sur une
                        // seule ligne étroite ; ici il en porte deux.
                        //
                        // La coche reste visible et découvrable, et sa description la nomme : c'est
                        // le « bouton visible » que le retour utilisateur réclamait, à la largeur
                        // des autres.
                        IconButton(
                            onClick = {
                                viewModel.saveNow()
                                onBack()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.note_editor_tooltip_done),
                            )
                        }
                        // 🔴 **La description suit l'état, comme l'icône.**
                        //
                        // Elle était figée : l'icône passait de l'épingle vide à l'épingle pleine, et
                        // un lecteur d'écran continuait d'annoncer « Épingler la note » sur une note
                        // **déjà épinglée** — donc l'inverse de ce que le bouton allait faire.
                        // `home_unpin` et `home_unfav` existaient exactement pour cet état, et
                        // n'étaient lues nulle part.
                        //
                        // C'est le jumeau asymétrique dans sa forme la plus littérale : ce que le
                        // code fait, et ce que l'utilisateur **entend**. Relevé par l'audit i18n du
                        // 2026-08-15.
                        IconButton(onClick = { viewModel.setPinned(!note.pinned) }) {
                            Icon(
                                imageVector = if (note.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = stringResource(
                                    if (note.pinned) R.string.home_unpin else R.string.note_editor_tooltip_pin,
                                ),
                            )
                        }
                        IconButton(onClick = { viewModel.setFavorite(!note.favorite) }) {
                            Icon(
                                imageVector = if (note.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(
                                    if (note.favorite) R.string.home_unfav else R.string.note_editor_tooltip_fav,
                                ),
                            )
                        }
                        // ⚠️ « Insérer un lien » est un bouton d'icône, **pas** une entrée de menu :
                        // c'est le geste d'écriture le plus fréquent de cet écran, et l'application
                        // publiée le place au même endroit, à côté de l'épingle et du favori.
                        IconButton(onClick = { autocompletionOuverte = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = stringResource(R.string.note_editor_tooltip_insert_link),
                            )
                        }
                        MenuDeDebordement(
                            onDeplacer = { deplacementOuvert = true },
                            onExporter = { viewModel.exporterLaNote(mentionDeCoffre) },
                            onCopier = {
                                // Retour haptique sur un geste réussi, comme l'application publiée
                                // (`note_editor_screen.dart:544`). Il part à l'appui, pas à l'issue :
                                // c'est l'accusé de réception du geste, pas celui de son résultat,
                                // que le message se charge d'annoncer.
                                retourHaptique.performHapticFeedback(HapticFeedbackType.ContextClick)
                                viewModel.copierEnMarkdown()
                            },
                            // ⚠️ Pas de `onBack()` ici : la navigation part quand la suppression a
                            // REUSSI, depuis l'observation de `action` ci-dessus. Quitter tout de
                            // suite laissait croire à une note supprimée qui ne l'était pas.
                            onCorbeille = viewModel::moveToTrash,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val chargementEnCours = stringResource(R.string.common_loading)

            // Recopie locale : `state` est un délégué, le lissage de type ne s'y applique pas.
            val erreurDeChargement = state.loadError

            when {
                // ⚠️ L'étiquette n'est pas décorative : sans elle, un lecteur d'écran ne dit
                // **rien** pendant le chargement — et une note de coffre peut mettre une seconde à
                // se déchiffrer sur un appareil ancien. `common_loading` existait pour ça.
                state.loading -> CircularProgressIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = chargementEnCours },
                )

                state.notFound -> EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.note_editor_error_not_found),
                )

                // ⚠️⚠️ **Correction d'un commentaire qui mentait.** Il affirmait qu'un coffre
                // auto-détruit « porte les deux états » et que l'ordre les départageait. C'est faux :
                // le ViewModel pose `loadError` **sans** `lockedVault`, les deux ne sont jamais vrais
                // ensemble. L'ordre reste, mais comme garde-fou d'écriture — le jour où un chemin
                // poserait les deux, c'est l'erreur qui doit gagner, jamais l'invitation à saisir un
                // secret. Relevé CONFIRMÉ par une relecture externe (Gemini, 2026-08-15), sur un
                // commentaire que je venais d'écrire.
                //
                // 🔴 **Icône d'avertissement, PAS un cadenas ouvert.** Un cadenas ouvert devant
                // « coffre auto-détruit » promet visuellement une récupération qui n'existe pas —
                // le message dit la vérité, l'image disait le contraire.
                erreurDeChargement != null -> EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = stringResource(erreurDeChargement),
                )

                state.lockedVault != null -> EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.note_card_locked),
                    subtitle = if (state.lostToVaultLock) {
                        stringResource(R.string.note_editor_error_vault_relocked_during_edit)
                    } else {
                        null
                    },
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                ) {
                    if (state.saveFailed) BanniereEchecEnregistrement(state.saveFailureReason)
                    TextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChange,
                        placeholder = { Text(stringResource(R.string.note_editor_title)) },
                        textStyle = MaterialTheme.typography.headlineSmall,
                        singleLine = true,
                        colors = champSansDecor(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = state.content,
                        onValueChange = viewModel::onContentChange,
                        placeholder = { Text(stringResource(R.string.note_editor_content_hint)) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = champSansDecor(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // ⚠️ Le panneau est DANS la colonne défilante : il ne doit donc porter aucun
                    // défilement propre. Cf. son KDoc — c'est la configuration qui a fait planter
                    // l'écran de fin du mode panique.
                    LiensDeLaNote(
                        liens = liens,
                        onOuvrirNote = ouvrirUneAutreNote,
                        // ⚠️ Un lien fantôme désigne une note annoncée et pas encore écrite :
                        // l'appuyer la crée, avec le titre du lien. Le texte de la note, lui, ne
                        // bouge pas — le `[[Titre]]` y est déjà, et c'est l'indexation qui
                        // rattachera le lien à sa cible une fois la note née.
                        onLienFantome = viewModel::creerLaNoteManquante,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }

    state.lockedVault?.let { dossier ->
        UnlockVaultSheet(
            folder = dossier,
            onDismiss = onBack,
            onUnlocked = viewModel::retryAfterUnlock,
        )
    }
}

/**
 * L'avertissement qu'un enregistrement a échoué.
 *
 * 🔴 En tête du contenu, pas en bas : l'utilisateur doit le voir avant de continuer à taper du
 * texte qui ne sera pas conservé non plus.
 */
@Composable
private fun BanniereEchecEnregistrement(@StringRes raison: Int?) {
    val couleurs = MaterialTheme.colorScheme
    Surface(color = couleurs.errorContainer, modifier = Modifier.fillMaxWidth()) {
        // ⚠️ La raison REMPLACE le message générique quand elle est connue : les afficher tous les
        // deux ferait lire « Échec de sauvegarde. Titre trop long » — la première moitié n'apprend
        // rien que la seconde ne dise mieux.
        Text(
            text = stringResource(raison ?: R.string.note_editor_error_save_failed),
            style = MaterialTheme.typography.bodySmall,
            color = couleurs.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * La confirmation avant de sortir une note d'un coffre.
 *
 * ## 🔴 Ce que l'utilisateur doit comprendre avant de toucher au bouton
 *
 * Le contenu part en clair dans la base. Et le mot « irréversible » du corps de texte ne dit pas
 * qu'on ne peut pas remettre la note au coffre — on peut — mais qu'**elle aura transité hors
 * chiffrement** : ce qui a été écrit en clair au repos l'a été, et aucun geste ultérieur ne le
 * défait. C'est la formulation de l'application publiée, et elle est juste.
 *
 * ⚠️ **L'action de sortie est le bouton discret, en rouge**, et « Annuler » celui qu'on touche par
 * réflexe — même disposition que [com.filestech.notes_tech.ui.folders.ConfirmDeleteFolderDialog],
 * et même icône, pour la même raison : les deux retirent une protection.
 *
 * Ce dialogue est la seule chose qui sépare un tap dans un menu d'une note déprotégée. L'entrée de
 * menu, elle, n'est plus désactivée — c'était la protection tant que le dépôt ne savait pas
 * déchiffrer, et une entrée grisée n'explique rien.
 */
@Composable
private fun DialogueDeSortieDeCoffre(onConfirmer: () -> Unit, onAnnuler: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        icon = {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.note_editor_exit_vault_title)) },
        text = { Text(stringResource(R.string.note_editor_exit_vault_body)) },
        confirmButton = {
            TextButton(onClick = onConfirmer) {
                Text(
                    text = stringResource(R.string.note_editor_exit_vault_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Deux champs de texte qui ne ressemblent pas à un formulaire. */
@Composable
private fun champSansDecor() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

/**
 * Ce qu'il faut faire de l'issue d'une action de menu : partager, quitter, ou annoncer.
 *
 * ⚠️ **Extrait de [NoteEditorRoute] parce que detekt a refusé la fonction**, arrivée au seuil de
 * complexité en gagnant une condition de plus. Le seuil n'est pas à relever : c'est le signal qui
 * dit qu'une fonction porte trop de décisions, et il a raison — cet effet-ci se lit seul.
 *
 * ⚠️ **Afficher PUIS consommer**, jamais l'inverse : un `LaunchedEffect` dont la clé change par son
 * propre effet s'annule, et le message ne s'afficherait jamais. Le partage part d'abord, le porteur
 * est vidé ensuite, et le message est posté sur une portée qui ne dépend pas de la clé.
 *
 * ⚠️ **Quatre branches faisaient l'inverse** — `onConsommer()` avant le `portee.launch` — et elles
 * fonctionnaient : la portée est celle du `remember`, donc l'annulation de cet effet-ci ne l'atteint
 * pas. C'est précisément ce qui rend la forme dangereuse : elle contredit la règle du dépôt **sans
 * rien casser**, et le jour où l'un de ces corps gagne un `await` avant la ligne d'affichage, le
 * message disparaît sans que rien n'ait l'air d'avoir changé. Relevé par une relecture externe
 * (Gemini, 2026-08-15) comme fragilité, pas comme défaut — et corrigé à ce titre.
 *
 * Les deux exceptions restantes n'affichent rien : la corbeille **navigue** (`onBack`), l'export
 * lance un sélecteur système. Consommer d'abord y est sans effet visible, et consommer après une
 * navigation toucherait un `ViewModel` dont l'écran est déjà parti.
 */
@Composable
private fun IssueDUneAction(
    action: ActionDEditeur,
    contexte: Context,
    ressources: Resources,
    titreDuSelecteur: String,
    messages: SnackbarHostState,
    portee: CoroutineScope,
    onConsommer: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(action) {
        val export = action.export
        val erreur = action.erreur
        when {
            export != null -> {
                partagerUnFichier(
                    context = contexte,
                    uri = export.uri,
                    mimeType = MIME_MARKDOWN,
                    sujet = export.fileName,
                    titreDuSelecteur = titreDuSelecteur,
                )
                onConsommer()
            }

            action.misAlaCorbeille -> {
                onConsommer()
                onBack()
            }

            action.deplacee -> {
                portee.launch { messages.showSnackbar(ressources.getString(R.string.note_editor_moved)) }
                onConsommer()
            }

            action.copiee -> {
                portee.launch { messages.showSnackbar(ressources.getString(R.string.note_editor_copied_to_clipboard)) }
                onConsommer()
            }

            // ⚠️ Le presse-papiers n'a PAS été touché : le dire, plutôt que laisser croire à une
            // copie vide réussie. Un geste sans effet se signale.
            action.copieVide -> {
                portee.launch { messages.showSnackbar(ressources.getString(R.string.note_editor_copy_empty)) }
                onConsommer()
            }

            erreur != null -> {
                val gabarit = when (action.origine) {
                    ActionDEditeur.OrigineDErreur.EXPORT -> R.string.note_editor_export_failed
                    ActionDEditeur.OrigineDErreur.DEPLACEMENT -> R.string.note_editor_move_failed
                    // ⚠️ Création, corbeille et copie n'ont pas de phrase dédiée : « Erreur : … » dit
                    // ce qu'il faut sans inventer une chaîne qui n'existe dans aucune des deux
                    // langues. `COPIE` a rejoint cette liste avec le copier en Markdown ; ce
                    // commentaire ne nommait toujours que les deux premières.
                    else -> R.string.common_error_with
                }
                portee.launch { messages.showSnackbar(ressources.getString(gabarit, erreur)) }
                onConsommer()
            }
        }
    }
}

/**
 * Annonce « Note enregistrée » au lecteur d'écran, **au plus une fois toutes les cinq secondes**.
 *
 * ⚠️ Le frein n'est pas du confort : l'enregistrement est différé de 500 ms, donc une frappe continue
 * en déclenche un toutes les secondes et demie environ. Sans limite, un lecteur d'écran ne dirait
 * plus que ça, et couvrirait le texte que l'utilisateur est en train d'écrire. Valeur reprise de
 * l'application publiée (`note_editor_screen.dart:58`).
 *
 * ⚠️ **Rien n'est annoncé quand l'enregistrement a échoué** : une bannière le dit à l'écran, et
 * annoncer une réussite par-dessus serait le pire des deux mondes.
 *
 * ⚠️ `elapsedRealtime` et non `currentTimeMillis` : un changement d'heure système ne doit pas
 * rouvrir la fenêtre — ni la fermer pour des heures.
 */
@Composable
private fun AnnonceDEnregistrement(enregistrement: Boolean, echec: Boolean) {
    val vue = LocalView.current
    val texte = stringResource(R.string.note_editor_announce_saved_success)
    var derniereAnnonce by rememberSaveable { mutableLongStateOf(0L) }
    var enregistrementPrecedent by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(enregistrement) {
        val vientDeFinir = enregistrementPrecedent && !enregistrement
        enregistrementPrecedent = enregistrement
        if (!vientDeFinir || echec) return@LaunchedEffect

        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - derniereAnnonce < DELAI_ENTRE_ANNONCES_MS) return@LaunchedEffect
        derniereAnnonce = maintenant
        @Suppress("DEPRECATION")
        vue.announceForAccessibility(texte)
    }
}

/** Cinq secondes, comme l'application publiée. */
private const val DELAI_ENTRE_ANNONCES_MS = 5_000L

/**
 * Le nom du dossier, et **l'état de l'enregistrement**.
 *
 * ## 🔴 Le portage n'affichait RIEN sur l'enregistrement
 *
 * Ni pendant, ni après : seule une bannière apparaissait en cas d'échec. `note_editor_saving` et
 * `note_editor_saved` étaient traduites des deux côtés et lues nulle part. Or l'enregistrement est
 * **différé** ici — quitter l'écran juste après avoir tapé demande de savoir si le texte est parti.
 * L'absence de retour laissait ce doute entier.
 *
 * ⚠️ **Le dossier est CONSERVÉ.** L'application publiée met l'indicateur à la place du titre et
 * n'affiche donc le dossier nulle part (`note_editor_screen.dart:945`). Le portage l'affichait :
 * le remplacer aurait retiré une information pour en ajouter une autre. Les deux tiennent.
 *
 * ⚠️⚠️ **PAS de `liveRegion` ici**, contrairement au premier jet. Une région active annonce chaque
 * changement : l'enregistrement étant différé de 500 ms, une frappe continue fait basculer
 * « Enregistrement… ⇄ Enregistré » toutes les secondes et demie environ, et **sature le lecteur
 * d'écran**. L'annonce est donc explicite et **limitée à une toutes les cinq secondes**, dans
 * [NoteEditorRoute] — c'est le mécanisme de l'application publiée, et le commentaire qui l'accompagne
 * là-bas dit exactement pourquoi.
 *
 * L'indicateur visuel, lui, reste exact à tout instant : c'est l'annonce qui est freinée, pas
 * l'affichage.
 */
@Composable
private fun TitreDeLEditeur(dossier: String, enregistrement: Boolean, echec: Boolean) {
    val etat = stringResource(if (enregistrement) R.string.note_editor_saving else R.string.note_editor_saved)

    Column {
        Text(
            text = dossier,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
        )
        // 🔴 **Rien du tout quand l'enregistrement a ÉCHOUÉ.**
        //
        // `saving` retombe à `false` sur un échec comme sur une réussite : la ligne affichait donc
        // « Enregistré », avec sa coche, **au-dessus de la bannière rouge qui dit le contraire**.
        // Deux affirmations opposées sur le même écran, et c'est la rassurante qui est fausse.
        // Relevé CONFIRMÉ par une relecture externe (Gemini, 2026-08-15).
        //
        // La bannière porte déjà le message ; cette ligne se tait plutôt que d'en ajouter un second.
        if (echec) return@Column

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (enregistrement) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            } else {
                Icon(
                    imageVector = Icons.Outlined.CloudDone,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = etat,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * Les actions moins fréquentes de l'éditeur.
 *
 * ⚠️ **La corbeille est ici, pas en icône.** C'est la disposition de l'application publiée, et elle
 * est meilleure : une icône de suppression à côté de l'épingle et du favori s'atteint par erreur, et
 * ce geste-là part sans confirmation.
 *
 * ⚠️ **« Déplacer » est active même pour une note de coffre**, et ne l'était pas tant que le dépôt
 * ne savait pas déchiffrer. Ce qui protège l'utilisateur n'est pas une entrée grisée mais la
 * confirmation que l'écran pose avant de sortir une note d'un coffre — cf. [DialogueDeSortieDeCoffre].
 */
@Composable
private fun MenuDeDebordement(
    onDeplacer: () -> Unit,
    onExporter: () -> Unit,
    onCopier: () -> Unit,
    onCorbeille: () -> Unit,
) {
    var ouvert by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = { ouvert = true }) {
        Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = stringResource(R.string.note_editor_tooltip_more),
        )
    }
    DropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_move)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
            onClick = {
                ouvert = false
                onDeplacer()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_export)) },
            leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
            onClick = {
                ouvert = false
                onExporter()
            },
        )
        // Entre l'export et la corbeille, comme dans l'application publiée
        // (`note_editor_screen.dart:1035`) : les deux gestes qui sortent le texte de l'application
        // se suivent, et le geste destructif reste seul en bas.
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_copy_markdown)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            onClick = {
                ouvert = false
                onCopier()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.note_editor_menu_trash)) },
            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            onClick = {
                ouvert = false
                onCorbeille()
            },
        )
    }
}
