package com.filestech.notes_tech.ui.editor

import androidx.annotation.StringRes
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.export.ExportResult
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.data.local.dao.NoteLinkRow
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.LinksRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.di.ApplicationScope
import com.filestech.notes_tech.domain.export.NoteMarkdown
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.repository.VaultLockedException
import com.filestech.notes_tech.security.clipboard.SensitiveClipboard
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.VaultSessionClosedException
import com.filestech.notes_tech.ui.common.userMessageFor
import com.filestech.notes_tech.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** L'état de l'éditeur. Le contenu affiché est **en clair, en mémoire seulement**. */
data class EditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    /**
     * 🔴 **La raison pour laquelle la note n'a pas pu être ouverte**, quand ce n'est ni « introuvable »
     * ni « coffre à déverrouiller ».
     *
     * Sans ce champ, **toutes** les issues de déchiffrement tombaient sur la même branche : demander
     * le secret. Y compris un coffre **auto-détruit**, où l'on invitait donc l'utilisateur à saisir
     * un code encore et encore pour des notes qui n'existent plus — et un dossier coffre disparu, où
     * l'écran s'ouvrait simplement **vide**, sans rien dire.
     *
     * Les trois chaînes existaient des deux côtés et n'étaient lues nulle part.
     */
    @StringRes val loadError: Int? = null,
    val title: String = "",
    /**
     * Le contenu **et la position du curseur**, dans un seul porteur.
     *
     * ⚠️ Une `String` ne suffisait pas : insérer un `[[Titre]]` là où l'utilisateur écrit demande de
     * savoir où il écrit, et rien dans l'état ne le disait. Garder la sélection à l'écran, dans un
     * `remember`, et ne remonter que le texte est le montage qui fait sauter le curseur — deux
     * sources de vérité pour un même champ finissent toujours par diverger, et ici le symptôme est
     * un curseur qui revient au début à chaque frappe.
     *
     * ⚠️ **Comparer ce champ pour décider d'enregistrer serait un piège** : un simple déplacement du
     * curseur produit une nouvelle valeur avec le même texte. Toute comparaison porte sur
     * [TextFieldValue.text], jamais sur la valeur entière — cf. [originalContent], qui reste une
     * `String` exprès.
     */
    val content: TextFieldValue = TextFieldValue(),
    val note: Note? = null,
    val folder: Folder? = null,
    val lockedVault: Folder? = null,
    val saving: Boolean = false,
    val lostToVaultLock: Boolean = false,
    val saveFailed: Boolean = false,
    /**
     * 🔴 **Pourquoi l'enregistrement échoue**, quand la raison est connue et corrigeable.
     *
     * La bannière disait « Échec de sauvegarde », rien de plus. Un titre de plus de 200 caractères
     * fait échouer **chaque** enregistrement différé, indéfiniment, et l'utilisateur n'avait aucun
     * moyen de savoir ce qui bloque ni comment le débloquer — il continuait d'écrire dans une note
     * qui ne s'enregistre plus. `error_note_title_too_long` dit exactement quoi faire, et n'était
     * lue nulle part.
     */
    @StringRes val saveFailureReason: Int? = null,
    /**
     * Le texte tel qu'il a été CHARGÉ, en clair.
     *
     * 🔴 Sans lui, aucune comparaison honnête n'est possible sur une note de coffre : `note.title`
     * y vaut la chaîne vide (le titre vit dans le chiffré), donc « le titre a-t-il changé ? »
     * répondait **toujours oui**. Ouvrir une note de coffre pour la LIRE, puis revenir, la
     * rechiffrait et repoussait sa date de modification — elle remontait en tête de liste sans que
     * personne n'y ait touché. Relevé par une relecture externe (Gemini, 2026-08-14).
     */
    val originalTitle: String = "",
    val originalContent: String = "",
) {
    val isVaultNote: Boolean get() = folder?.isVault == true
}

/**
 * Ce que le panneau de liens a besoin de montrer.
 *
 * ⚠️ Les deux listes sont vides tant que rien n'a été lu, et le panneau **disparaît** dans ce cas
 * plutôt que d'afficher deux sections creuses. C'est le comportement de l'application publiée, et
 * c'est aussi le bon : une note sans lien ne doit pas payer de place à l'écran pour le dire.
 */
data class PanneauDeLiens(
    /** Les liens **partant** de la note, résolus comme fantômes, dans l'ordre du texte. */
    val sortants: List<NoteLinkRow> = emptyList(),
    /** Les notes qui **mentionnent** celle-ci. */
    val mentions: List<Note> = emptyList(),
) {
    val estVide: Boolean get() = sortants.isEmpty() && mentions.isEmpty()
}

/**
 * L'issue d'une action lancée depuis le menu de l'éditeur.
 *
 * ⚠️ [erreur] is a string resource chosen by `userMessageFor`, no longer the exception's message
 * (2026-09-24, parity with notes_tech 2.0.9's `describeError`). The raw message was internal French
 * — "enregistrement prealable echoue" — shown as such to English-speaking users.
 */
data class ActionDEditeur(
    val enCours: Boolean = false,
    val export: ExportResult? = null,
    val deplacee: Boolean = false,
    val misAlaCorbeille: Boolean = false,
    /** Le contenu est dans le presse-papiers. `false` aussi quand il n'y avait rien à copier. */
    val copiee: Boolean = false,
    /** La note était vide : le presse-papiers n'a **pas** été touché. */
    val copieVide: Boolean = false,
    /** The note to open: a `[[Title]]` resolved, or just created — see `ouvrirOuCreerLaNote`. */
    val aOuvrir: String? = null,
    @StringRes val erreur: Int? = null,
    /**
     * ⚠️ **Quelle action a échoué**, pour que l'écran choisisse la bonne phrase.
     *
     * Sans ce champ, un export raté s'annonçait « Déplacement impossible » — les deux chaînes
     * existent, et n'en utiliser qu'une revient à dire à l'utilisateur que son geste a échoué, mais
     * un autre que celui qu'il a fait.
     */
    val origine: OrigineDErreur? = null,
) {
    enum class OrigineDErreur { DEPLACEMENT, EXPORT, CREATION, CORBEILLE, COPIE }
}

/**
 * L'éditeur d'une note.
 *
 * ## 🔴 Le clair ne vit qu'ici, et il ne redescend jamais tel quel
 *
 * Une note de coffre est déchiffrée **pour l'affichage** dans un objet éphémère. L'enregistrement
 * repasse par `NotesRepository.saveEdits`, qui rescelle. Persister l'objet déchiffré remettrait le
 * clair en base — la garde d'écriture le refuse, mais mieux vaut ne pas compter dessus.
 *
 * ## 🔴 Le coffre peut se refermer PENDANT qu'on écrit
 *
 * C'est le cas qui coûte des données : l'utilisateur tape, le délai d'inactivité tombe, la
 * sauvegarde différée part et se fait refuser. Sans rien de plus, le texte n'existe nulle part et
 * rien ne le dit. La note est donc inscrite dans `vault_lost_drafts`, que l'accueil relit pour
 * afficher sa bannière — le seul signalement d'une perte silencieuse.
 */
@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    private val notes: NotesRepository,
    private val links: LinksRepository,
    private val exporter: NoteExporter,
    private val folders: FoldersRepository,
    private val vaults: FolderVaultService,
    private val settings: AppSettings,
    private val clipboard: SensitiveClipboard,
    @ApplicationScope private val applicationScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val noteId: String = checkNotNull(savedState[Destination.ARG_NOTE_ID]) {
        "l'editeur a ete ouvert sans identifiant de note"
    }

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var sauvegardeDifferee: Job? = null

    /**
     * Serialise les ecritures de cette note.
     *
     * 🔴 `sauvegardeDifferee?.cancel()` n'arrete PAS une ecriture Room deja engagee. Sans ce
     * verrou, la sauvegarde differee peut se terminer APRES la finale et reecrire une version plus
     * ancienne — une perte silencieuse, exactement ce que la sauvegarde finale existe pour empecher.
     *
     * Releve en relisant les correctifs de relecture (GPT-5.2, 2026-08-14) : le correctif precedent
     * garantissait que la finale s'execute, pas qu'elle s'execute EN DERNIER.
     */
    private val ecriture = Mutex()

    /**
     * Les liens de la note, relus par la base à chaque changement.
     *
     * ## ⚠️⚠️ La clé de réabonnement n'est PAS l'état entier
     *
     * `_state` change à **chaque frappe**. Un `flatMapLatest` posé dessus rouvrirait deux curseurs
     * SQLCipher par caractère tapé. La clé est donc réduite à ce dont les deux requêtes dépendent
     * réellement : l'identifiant de la note, son titre — qui sert d'appariement aux liens fantômes —
     * et son état de verrouillage.
     *
     * ⚠️ Ce titre est celui de l'**entité persistée**, pas celui du champ de saisie : les liens
     * fantômes ne s'accrochent qu'après un enregistrement, jamais pendant la frappe. C'est aussi ce
     * que fait l'application publiée, qui compare `widget.note.title`.
     *
     * ## Confidentialité — vérifié, pas supposé
     *
     * Une note de coffre verrouillé ne peut apparaître ni comme cible ni comme source : les liens
     * sortants d'une note scellée sont **supprimés** à l'indexation (`NotesRepository.reindexLinks`),
     * `titlesForLinking()` filtre sur `encrypted_content IS NULL`, et `observeBacklinks` court-
     * circuite sur `isLocked`. Les trois chemins ont été relus le 2026-08-15 avant d'afficher quoi
     * que ce soit ici.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liens: StateFlow<PanneauDeLiens> = _state
        .map { it.note }
        .distinctUntilChanged { ancienne, nouvelle ->
            ancienne?.id == nouvelle?.id &&
                ancienne?.title == nouvelle?.title &&
                ancienne?.isLocked == nouvelle?.isLocked
        }
        .flatMapLatest { note ->
            if (note == null) {
                flowOf(PanneauDeLiens())
            } else {
                combine(links.observeOutgoing(note.id), links.observeBacklinks(note)) { sortants, mentions ->
                    PanneauDeLiens(sortants = sortants, mentions = mentions)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            // `WhileSubscribed(5 s)` et non `Eagerly`, pour la même raison qu'à l'accueil : sans
            // abonné, ces flux garderaient des curseurs ouverts sur une base chiffrée.
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = PanneauDeLiens(),
        )

    /**
     * Ce que l'utilisateur a tapé dans la feuille d'autocomplétion.
     *
     * Vidé à la fermeture par [reinitialiserLaRecherche] : sans cela, rouvrir la feuille afficherait
     * les résultats de la fois précédente pendant le temps du freinage.
     */
    private val requeteDeLien = MutableStateFlow("")

    /**
     * Les titres proposés pour un `[[…]]`.
     *
     * ## Confidentialité — tenue par la requête, pas par un filtre d'écran
     *
     * `findByTitleLike` porte `AND encrypted_content IS NULL` : une note de coffre verrouillé n'est
     * jamais candidate. C'est **dans la requête** et non après coup, pour deux raisons — la garantie
     * ne dépend d'aucun appelant, et la limite n'est pas consommée par des notes qu'on écarterait
     * ensuite, ce qui ferait maigrir les suggestions sans raison visible.
     *
     * ⚠️ L'application publiée filtre, elle, **après** la requête (`suggestTitles`, `if (n.isLocked)
     * continue`). Ne pas transposer ce filtre ici : il serait redondant, et un second endroit qui
     * décide de la même chose finit par en décider autrement.
     *
     * ⚠️ One exception since 2026-09-25, from a vault note only: the note of that vault whose title
     * is typed in full — never a list of the vault's titles (`NotesRepository.suggestTitlesFrom`).
     */
    // ⚠️ Pas de `distinctUntilChanged` : un `StateFlow` ne réémet déjà pas une valeur égale, et
    // l'opérateur y est déprécié pour cette raison même.
    //
    // 🔴🔴 **L'ordre des émissions et le contrat `pour` vivent dans [fluxDeSuggestions]**, à part et
    // testés sur la JVM en temps virtuel (`FluxDeSuggestionsTest`). Ils étaient ici, et deux
    // fragilités relevées par une relecture externe (GPT-5.2, 2026-08-17) n'étaient **écrites que
    // dans un commentaire** faute d'être mesurables : ce ViewModel demande une base, un coffre et
    // Hilt, donc rien de tout cela n'était exerçable hors appareil, et les tests d'écran injectent la
    // réponse — ils court-circuitent précisément ce qu'il fallait vérifier.
    //
    // ⚠️ Ce qui reste ici est le seul câblage : la source des saisies, la recherche, et la portée.
    val suggestionsDeLien: StateFlow<SuggestionsDeLien> = requeteDeLien
        .fluxDeSuggestions(FREINAGE_SUGGESTIONS_MILLIS, ::chercherDesTitres)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = SuggestionsDeLien(),
        )

    /**
     * The titles for [texte], searched from the note's folder: from a vault note, the vault's note
     * typed in full comes too (`NotesRepository.suggestTitlesFrom`). A failure — the vault locked
     * meanwhile — is answered by [fluxDeSuggestions], marked as such.
     */
    private suspend fun chercherDesTitres(texte: String): List<Note> {
        val dossier = _state.value.note?.folderId ?: return notes.suggestTitles(texte, excludeId = noteId)
        return notes.suggestTitlesFrom(dossier, texte, excludeId = noteId)
    }

    fun chercherUnTitre(texte: String) {
        requeteDeLien.value = texte
    }

    fun reinitialiserLaRecherche() {
        requeteDeLien.value = ""
    }

    /** Insère `[[titre]]` là où l'utilisateur écrit. */
    fun insererUnLien(titre: String) = insererAuCurseur("[[$titre]]")

    /**
     * Crée une note portant [titre] dans le **dossier de la note courante**, puis insère le lien.
     *
     * ⚠️⚠️ **Le lien porte le titre TAPÉ, jamais celui de la note créée.**
     *
     * Dans un coffre, `NotesRepository.create` scelle **avant** d'insérer : la note revient avec son
     * blob et un titre **vide**, puisque le titre vit dans le chiffré. Reprendre `creee.title` pour
     * fabriquer le lien écrirait donc `[[]]` — un lien vers rien, dans le seul cas où l'utilisateur
     * ne peut pas s'en apercevoir en relisant, parce que la note cible est justement invisible.
     *
     * Le titre tapé est aussi le bon sur le fond : c'est lui que la note portera une fois ouverte,
     * et c'est sur sa forme normalisée que l'appariement se fait.
     *
     * ⚠️ Le lien restera **fantôme** tant que la note vit dans un coffre — `titlesForLinking` écarte
     * les notes chiffrées. C'est voulu : un lien résolu vers une note de coffre en révélerait
     * l'existence depuis une note qui, elle, n'est pas protégée.
     *
     * ⚠️ L'application publiée doit poser ici une garde explicite, parce que sa création laissait la
     * note en clair dans un coffre le temps d'un rechiffrement séparé — son propre commentaire dit
     * que **les deux chemins de création de l'éditeur passaient à côté**. Ici la garde est dans le
     * dépôt, avant l'insertion en base : il n'y a pas d'instant où le clair existe sur le disque, et
     * donc rien à répéter à l'appel.
     */
    fun creerPuisLier(titre: String) = creerDansLeMemeDossier(titre) { insererUnLien(titre) }

    /**
     * Opens the note a `[[title]]` names, creating it first — in the current note's folder — when
     * no note has that title. Tapped in the Markdown preview, or on a dangling link of the panel.
     *
     * ## One path for both gestures, as in the published app
     *
     * notes_tech 2.0.9 resolves a tapped preview link and, when nothing answers, falls back on
     * `_createFromDangling` — the very function its links panel calls — which **creates, then
     * opens**. ⚠️ The port's panel created **without opening**: a divergence from the published app
     * since 2.0.4 at least, unnoticed until the preview needed the same gesture (2026-09-25). A tap
     * that creates a note and leaves the user where they were looks like a tap that did nothing.
     *
     * ⚠️ No text is inserted: the `[[Title]]` is already in the note — it is what was tapped. The
     * link attaches itself when the note is born (`resolveIncoming`), and the title used is the one
     * tapped, never read back from the created note (see [creerPuisLier], for a vault).
     *
     * ⚠️ Resolution starts from the note's folder (`NotesRepository.resolveTitleFrom`): from a vault
     * note, the notes of that open vault first, then the notes outside every vault; from anywhere
     * else, never a vault note. 2.0.9 resolves no vault note at all, and a link tapped in a vault
     * created a new note each time — solution B, chosen by Patrice on 2026-09-25.
     */
    fun ouvrirOuCreerLaNote(titre: String) = tenterUneAction(ActionDEditeur.OrigineDErreur.CREATION) {
        val dossier = _state.value.note?.folderId ?: return@tenterUneAction
        val cible = notes.resolveTitleFrom(dossier, titre) ?: notes.create(folderId = dossier, title = titre).id
        _action.value = ActionDEditeur(aOuvrir = cible)
    }

    /**
     * ⚠️ **Le dossier est celui de la note courante, pas la boîte de réception.**
     *
     * Une note créée depuis un lien hérite du contexte où le lien a été écrit — y compris un coffre.
     * L'envoyer d'office dans la boîte de réception sortirait discrètement du coffre une note que
     * l'utilisateur vient de créer depuis l'intérieur.
     */
    private fun creerDansLeMemeDossier(titre: String, ensuite: () -> Unit) {
        val dossier = _state.value.note?.folderId ?: return
        // 🔴 **`tenterUneAction` et NON `enArrierePlan`.**
        //
        // La première version passait par `enArrierePlan`, qui journalise et se tait — trois
        // fonctions sous le commentaire de `tenterUneAction` qui dit textuellement de ne pas le
        // faire ici. L'écran ferme la feuille **immédiatement** après l'appel : une création qui
        // échoue — base indisponible, stockage plein, session de coffre refermée — laissait donc
        // l'utilisateur devant une feuille qui se referme normalement, sans note créée, sans lien
        // inséré, et sans le moindre signal qu'il faut recommencer. Un geste d'écriture sans effet,
        // parfaitement silencieux.
        //
        // Relevé **par les deux relectures externes du 2026-08-15**, chacune de son côté. C'est le
        // motif que ce dépôt connaît le mieux : la règle écrite à un endroit, et non appliquée à
        // l'endroit voisin.
        tenterUneAction(ActionDEditeur.OrigineDErreur.CREATION) {
            notes.create(folderId = dossier, title = titre)
            ensuite()
            // ⚠️ Remettre l'état à zéro **sans** poser d'issue : la création réussie n'a rien à
            // annoncer, le lien inséré se voit tout seul dans le texte.
            _action.value = ActionDEditeur()
        }
    }

    /**
     * L'issue d'une action de menu, à montrer **une fois**.
     *
     * ⚠️ Consommée par l'écran, jamais laissée en place : sans cela, une rotation rejouerait le
     * partage et l'utilisateur verrait une seconde fenêtre s'ouvrir sans l'avoir demandée. C'est la
     * même règle que l'export des réglages, et pour la même raison.
     */
    private val _action = MutableStateFlow(ActionDEditeur())
    val action: StateFlow<ActionDEditeur> = _action.asStateFlow()

    /** Les dossiers où la note peut aller. */
    val dossiers: StateFlow<List<Folder>> = folders.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = emptyList(),
        )

    fun consommerLAction() {
        _action.value = ActionDEditeur()
    }

    /** Le coffre [folderId] a-t-il une session ouverte ? Sert à demander le secret **avant** d'agir. */
    fun estDeverrouille(folderId: String): Boolean = vaults.isUnlocked(folderId)

    /**
     * Déplace la note vers [folderId].
     *
     * ## Deux chemins de dépôt, et c'est l'état de la note qui tranche — pas l'appelant
     *
     * `moveToFolder` **refuse** une note verrouillée : déchiffrer avec la clé d'origine est un autre
     * geste, qui porte un autre nom (`relocateLockedNote`) parce qu'il peut retirer une protection.
     * Router ici sur `note.isLocked` garde ce choix hors de l'interface : un écran ne décide pas
     * qu'on déchiffre, il constate qu'il le faut.
     *
     * ⚠️ **`note.isLocked` et non `isVaultNote`.** Le second regarde le dossier ; une note en clair
     * survivant dans un dossier coffre — ce que `reprotectPlaintextNotes` existe pour réparer —
     * passerait alors par le chemin qui déchiffre, sur une note qui n'a rien à déchiffrer. Et
     * `state.note` porte bien la note **scellée** telle qu'elle est en base : l'éphémère déchiffrée
     * ne vit que dans `title` et `content`. C'est le `_wasLocked` de l'application publiée, dont le
     * commentaire raconte le défaut jumeau — elle avait d'abord testé `encryptedContent != null`
     * sur l'éphémère, condition **toujours fausse**, et sa confirmation ne s'affichait jamais.
     *
     * La confirmation de sortie de coffre, elle, est posée par l'écran avant l'appel : c'est un
     * geste d'interface, et le dépôt ne doit pas dépendre d'un dialogue pour être sûr.
     */
    fun deplacerVers(folderId: String, sortieDeCoffreConfirmee: Boolean = false) =
        tenterUneAction(ActionDEditeur.OrigineDErreur.DEPLACEMENT) {
            // 🔴🔴 **Vider la sauvegarde en attente AVANT de décider quoi que ce soit.**
            //
            // L'enregistrement est freiné à 500 ms. Pendant cette fenêtre, la note en base peut être
            // en retard d'un état entier sur ce que l'utilisateur a sous les yeux — et le cas qui
            // compte est celui-ci : une note **créée dans un coffre** naît vide, donc non scellée.
            // Taper « code : 4242 » puis toucher « déplacer » dans la demi-seconde présentait une
            // note que la base croit encore vide et en clair. Aucune confirmation de sortie n'était
            // due, et la note quittait le coffre sans que la question soit posée.
            //
            // C'est ce que fait l'application publiée au même endroit (« Flush avant la mutation »),
            // et c'est déjà ce que fait l'export deux fonctions plus bas. Le déplacement était le
            // jumeau qui ne le faisait pas.
            enregistrer()
            val apresEnregistrement = _state.value
            if (apresEnregistrement.saveFailed || apresEnregistrement.lostToVaultLock) {
                error("enregistrement prealable echoue")
            }

            // ⚠️ **Relire la note, ne pas croire l'état.** L'écran décide d'afficher la confirmation sur
            // `state.note`, qui peut avoir été scellée depuis — une note créée vide dans un coffre l'est
            // au premier caractère. L'état est tenu à jour par `enregistrer`, mais une garde qui dépend
            // du bon fonctionnement d'un autre chemin n'est pas une garde.
            val actuelle = notes.find(noteId) ?: return@tenterUneAction
            if (actuelle.isLocked) {
                // 🔴 Sortir d'un coffre **exige** que la confirmation ait été posée. Si l'écran ne l'a
                // pas fait — parce qu'il croyait la note en clair — on refuse bruyamment plutôt que de
                // déprotéger en silence. L'utilisateur réessaie, et l'état étant alors à jour, il obtient
                // sa question. Un échec visible se répare ; une note sortie du chiffrement, non.
                val estUneSortie = folders.find(folderId)?.isVault != true
                check(!estUneSortie || sortieDeCoffreConfirmee) {
                    "sortie de coffre demandee sans confirmation prealable"
                }
                notes.relocateLockedNote(noteId, folderId)
            } else {
                notes.moveToFolder(noteId, folderId)
            }

            // 🔴🔴 **Relire la note et son dossier, sinon l'écran reste celui d'une note non protégée.**
            //
            // Déplacer vers un coffre **scelle** la note dans la même transaction. Sans cette relecture,
            // `state.note` et `state.folder` gardent le dossier d'avant, donc `isVaultNote` reste faux —
            // et c'est lui qui commande `SecureWindowGuard`. Le contenu, désormais chiffré au repos,
            // resterait affiché dans une fenêtre **non marquée protégée** : capturable, et visible dans
            // l'aperçu des applications récentes. L'entrée « déplacer » resterait active par-dessus le
            // marché, alors qu'elle doit se fermer dès que la note est au coffre.
            //
            // ⚠️ On recopie **uniquement** `note` et `folder` : passer par `charger()` remplacerait tout
            // l'état, donc le texte en cours de frappe et la position du curseur. Le déplacement ne doit
            // rien coûter à ce que l'utilisateur est en train d'écrire.
            //
            // Relevé PROBABLE par la relecture externe du 2026-08-15 ; le chemin est confirmé —
            // `moveToFolder` appelle `sealIfVault` avant d'écrire.
            //
            // ⚠️ La relecture vaut **dans les deux sens** depuis que sortir d'un coffre est possible :
            // sans elle, une note qui vient d'en sortir garderait un dossier coffre dans l'état, donc
            // une fenêtre marquée protégée pour un contenu qui ne l'est plus, et une entrée de menu qui
            // continuerait de proposer une confirmation de sortie déjà honorée.
            val fraiche = notes.find(noteId)
            _state.value = _state.value.copy(
                note = fraiche ?: _state.value.note,
                folder = fraiche?.let { folders.find(it.folderId) } ?: _state.value.folder,
            )
            _action.value = ActionDEditeur(deplacee = true)
        }

    /**
     * Exporte la note en Markdown et rend de quoi la partager.
     *
     * ⚠️⚠️ **Enregistrer d'abord, puis RELIRE la note en base.**
     *
     * L'enregistrement est freiné à 500 ms : exporter juste après avoir tapé produirait un fichier
     * amputé des derniers caractères, silencieusement, puisque l'export « réussirait ». Et relire
     * plutôt que d'exporter l'état de l'écran est ce qui permet à l'exporteur de voir une note de
     * coffre **comme telle** — donc de la déchiffrer lui-même et de poser le suffixe ` [unlocked]`.
     * Lui passer le clair de l'écran ferait perdre cette marque, qui est précisément ce qui dit au
     * destinataire que ce fichier était protégé et ne l'est plus.
     *
     * C'est aussi ce que fait l'application publiée, dont le commentaire raconte le défaut inverse :
     * exporter la ligne brute sans redéchiffrer produisait un `.md` au frontmatter correct et au
     * **corps vide**.
     */
    fun exporterLaNote(inboxLabel: String, vaultMention: (String) -> String) {
        tenterUneAction(ActionDEditeur.OrigineDErreur.EXPORT) {
            enregistrer()

            // 🔴 **Si l'enregistrement a échoué, on n'exporte PAS.**
            //
            // `enregistrer` ne lève pas : il pose `saveFailed` — ou `lostToVaultLock` si le coffre s'est
            // refermé — puis rend la main normalement. L'export continuait donc après un échec et
            // produisait un fichier amputé des dernières modifications, en annonçant sa réussite. C'est
            // exactement la perte silencieuse que le paragraphe ci-dessus prétend éviter : le commentaire
            // était juste sur l'intention et faux sur le fait.
            //
            // Relevé PROBABLE par la relecture externe du 2026-08-15.
            val apresEnregistrement = _state.value
            if (apresEnregistrement.saveFailed || apresEnregistrement.lostToVaultLock) {
                error("enregistrement prealable echoue")
            }

            val fraiche = notes.find(noteId) ?: return@tenterUneAction
            val dossier = folders.find(fraiche.folderId)
            // The same `folder:` rule as the whole-archive export — a default inbox name in the app's
            // language, a missing folder by its identifier (it read "" here, and the stored French
            // default for the inbox).
            val libelle = NoteMarkdown.folderLabel(dossier, fraiche.folderId, inboxLabel)
            _action.value = ActionDEditeur(export = exporter.exportOne(fraiche, libelle, vaultMention))
        }
    }

    /**
     * Copie le Markdown de la note dans le presse-papiers.
     *
     * ## ⚠️ N'ENREGISTRE PAS, et c'est délibéré
     *
     * Contrairement à [exporterLaNote], rien n'est écrit en base. Le texte affiché **est** l'état :
     * [onContentChange] le met à jour à chaque frappe, donc `content.text` est exact au caractère
     * près, sans attendre l'enregistrement différé. La version publiée devait au contraire lire ses
     * contrôleurs à la main (`note_editor_screen.dart:557`), parce que son modèle ne portait que la
     * dernière version **enregistrée** : copier juste après une frappe rendait un texte amputé des
     * derniers caractères, silencieusement, puisque la copie « réussissait ». Ce portage n'a pas ce
     * défaut à contourner — mais il ne faut pas non plus introduire un `enregistrer()` ici, qui
     * rescellerait une note de coffre pour un geste de lecture.
     *
     * ## ⚠️ La copie s'arrête au dernier caractère
     *
     * [String.trimEnd] retire les fins de ligne et espaces traînants. Un éditeur en produit sans
     * qu'on les voie — une touche Entrée de trop avant de fermer — et ils se collent tels quels dans
     * la destination. Écart assumé avec l'application publiée, qui copie `note.content` brut. Le
     * **contenu enregistré n'est pas touché** : seule la valeur déposée dans le presse-papiers l'est.
     *
     * ## ⚠️ Une note vide ne touche PAS au presse-papiers
     *
     * Y déposer une chaîne vide effacerait ce que l'utilisateur y avait mis — une perte silencieuse
     * de **sa** donnée, provoquée par un geste qui n'a rien à copier. Même décision que l'archive
     * vide qui ne se partage plus.
     */
    fun copierEnMarkdown() = tenterUneAction(ActionDEditeur.OrigineDErreur.COPIE) {
        // 🔴 No copy of a vault note without a live session (audit 2026-09-26, V1): the collector
        // drops the plaintext on the lock, and this says so again at the one gesture that exports it.
        val dossier = _state.value.folder
        if (dossier != null && dossier.isVault && !vaults.isUnlocked(dossier.id)) {
            oublierLeClair()
            return@tenterUneAction
        }
        val texte = _state.value.content.text.trimEnd()
        if (texte.isEmpty()) {
            _action.value = ActionDEditeur(copieVide = true)
            return@tenterUneAction
        }
        clipboard.copier(texte)
        _action.value = ActionDEditeur(copiee = true)
    }

    /**
     * ⚠️ Ne PAS reprendre [enArrierePlan] ici : il journalise et se tait. Une action de menu qui
     * échoue doit se voir — c'est l'invariant « une perte, ou un geste sans effet, se signale ».
     */
    private fun tenterUneAction(origine: ActionDEditeur.OrigineDErreur, bloc: suspend () -> Unit) {
        if (_action.value.enCours) return
        _action.value = ActionDEditeur(enCours = true)
        viewModelScope.launch {
            try {
                bloc()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "action de menu sur $noteId")
                _action.value = ActionDEditeur(erreur = userMessageFor(e), origine = origine)
            } finally {
                // 🔴 **Sans ce retour à zéro, un menu entier devient inerte, définitivement et en
                // silence.**
                //
                // La garde du dessus refuse toute action tant que `enCours` est vrai. Un bloc qui se
                // termine **sans poser d'issue** — un `return@tenterUneAction` anticipé, typiquement
                // parce que la note a disparu pendant l'édition, ce que le dépôt traite en cas
                // nominal — laissait donc l'indicateur levé pour la durée de vie de l'écran. Plus
                // aucune entrée du menu ne répondait, et rien ne le disait.
                //
                // La condition est nécessaire : un bloc qui a réussi a déjà posé son issue, dont
                // `enCours` vaut faux. On ne remet à zéro que ce que personne n'a rempli.
                if (_action.value.enCours) _action.value = ActionDEditeur()
            }
        }
    }

    init {
        charger()
        oublierLeClairALaFermetureDuCoffre()
    }

    /**
     * 🔴 **A vault that closes takes its plaintext off the editor** — security audit of 2026-09-26, V1.
     *
     * `lockAll()` runs when the app goes to the background, and the inactivity sweep closes a vault
     * with the app in the foreground; both wipe the KEYS only. The editor kept the decrypted title and
     * body in its state, on screen and copyable at the return, with no session — the promise of
     * `NotesTechApplication.observerLeCycleDeVieDuProcessus` ("someone who sees the unlocked phone
     * must not find an open vault") held for every screen but this one.
     *
     * ⚠️ Collected in `viewModelScope`, not while the screen is started: the lock happens precisely
     * while the activity is stopped, and the plaintext must be gone before the next frame is drawn.
     */
    private fun oublierLeClairALaFermetureDuCoffre() {
        viewModelScope.launch {
            vaults.unlockedFolderIds.collect { ouverts ->
                val dossier = _state.value.folder
                if (dossier != null && dossier.isVault && dossier.id !in ouverts) oublierLeClair()
            }
        }
    }

    /**
     * Drops the plaintext and shows the vault as locked — the sheet asks for the secret again.
     *
     * ⚠️ **Under [ecriture]**: a save in flight finishes first, and what it wrote is not reported lost.
     * Text typed since the last save cannot be written any more — the key is gone — so it is reported
     * as the relock during editing has always been (`vault_lost_drafts`, the home banner).
     */
    private suspend fun oublierLeClair() = ecriture.withLock {
        sauvegardeDifferee?.cancel()
        val etat = _state.value
        if (etat.loading || etat.loadError != null || etat.folder?.isVault != true) return@withLock
        val modifiee = etat.lockedVault == null &&
            (etat.title != etat.originalTitle || etat.content.text != etat.originalContent)
        passerALEtatVerrouille(perte = modifiee)
    }

    /** The locked state, with no plaintext left in it. */
    private fun passerALEtatVerrouille(perte: Boolean) {
        if (perte) settings.addVaultLostDraft(noteId)
        _state.update { etat ->
            EditorUiState(
                loading = false,
                note = etat.note,
                folder = etat.folder,
                lockedVault = etat.folder,
                lostToVaultLock = perte || etat.lostToVaultLock,
            )
        }
    }

    fun onTitleChange(value: String) {
        _state.value = _state.value.copy(title = value)
        programmerLaSauvegarde()
    }

    /**
     * ⚠️⚠️ **L'enregistrement n'est programmé que si le TEXTE a changé.**
     *
     * Depuis que le champ porte aussi la sélection, ce rappel se déclenche sur un simple déplacement
     * du curseur — un appui dans le texte, un glissement de sélection, un aller-retour de clavier. En
     * programmant à chaque fois, on **repousse** l'enregistrement en attente : quelqu'un qui tape une
     * phrase puis déplace lentement son curseur peut retarder indéfiniment l'écriture du texte qu'il
     * vient de taper, et le perdre si le coffre se referme entre-temps.
     *
     * Le garde de [enregistrer] ne suffit pas à couvrir ça : il empêche l'écriture inutile, pas le
     * report de l'écriture utile.
     */
    fun onContentChange(value: TextFieldValue) {
        val texteAChange = value.text != _state.value.content.text
        _state.value = _state.value.copy(content = value)
        if (texteAChange) programmerLaSauvegarde()
    }

    /**
     * Insère [fragment] à l'endroit où l'utilisateur écrit, et programme l'enregistrement.
     *
     * La mécanique du remplacement vit dans [InsertionDeTexte], à part et testée : c'est la seule
     * partie qui puisse se tromper d'un caractère sans que rien ne le montre.
     */
    fun insererAuCurseur(fragment: String) {
        _state.value = _state.value.copy(
            content = InsertionDeTexte.dansLaSelection(_state.value.content, fragment),
        )
        programmerLaSauvegarde()
    }

    /**
     * 🔴 **L'état recopie le drapeau écrit — sans quoi l'icône se fige au premier appui.**
     *
     * Rien ici n'observe la note en continu : `state.note` n'est réécrit que par le chargement, par
     * l'enregistrement et par le déplacement. Écrire en base sans le recopier laissait donc
     * `note.pinned` à sa valeur de chargement, et l'écran calcule l'appui suivant à partir d'elle :
     * `setPinned(!note.pinned)` renvoyait **la même valeur**. L'icône ne changeait pas, et il
     * devenait impossible de désépingler depuis l'éditeur.
     *
     * C'est le motif corrigé le même jour sur `note.isLocked`, sur les deux champs voisins qui
     * l'avaient échappé — un champ posé une fois et relu plus tard comme s'il était frais. Relevé
     * par l'audit par motifs du 2026-08-15.
     *
     * ⚠️ La recopie est faite **après** l'écriture, dans le même bloc : si le dépôt échoue,
     * [enArrierePlan] saute la ligne et l'état reste celui de la base.
     *
     * ⚠️⚠️ **`update` et non `value = value.copy(...)`**, seul endroit du fichier où l'écart compte.
     * Lire l'état puis le réécrire laisse une fenêtre pendant laquelle un enregistrement peut avoir
     * posé une note **plus fraîche** — scellée, par exemple. L'écraser avec la précédente et un
     * drapeau à jour ressusciterait exactement la péremption de `note.isLocked` corrigée ce jour-là,
     * par le geste le plus anodin de l'écran. `update` boucle jusqu'à écrire sur ce qu'il a lu.
     */
    fun setPinned(pinned: Boolean) = enArrierePlan {
        notes.setPinned(noteId, pinned)
        _state.update { etat -> etat.copy(note = etat.note?.copy(pinned = pinned)) }
    }

    /** Le jumeau de [setPinned], et il porte la même recopie pour la même raison. */
    fun setFavorite(favorite: Boolean) = enArrierePlan {
        notes.setFavorite(noteId, favorite)
        _state.update { etat -> etat.copy(note = etat.note?.copy(favorite = favorite)) }
    }

    /**
     * 🔴 **Passe par [tenterUneAction], comme ses deux voisines du même menu.**
     *
     * Elle utilisait `enArrierePlan` — qui journalise et se tait — alors qu'elle est la **troisième
     * entrée du menu** dont les deux autres venaient d'être dotées d'un signalement visible, et que
     * le KDoc de [tenterUneAction], quinze lignes plus bas, dit textuellement de ne pas faire ça
     * ici. L'écran appelait de surcroît `onBack()` **immédiatement**, sans attendre l'issue : une
     * suppression qui échoue renvoyait l'utilisateur à l'accueil en lui laissant croire que sa note
     * était à la corbeille, alors qu'elle était toujours là.
     *
     * ⚠️ La navigation se fait donc **après** le succès, par l'écran, qui observe [action].
     *
     * Relevé par l'audit de cohérence du 2026-08-15 — c'est très exactement ce qu'un tel audit
     * trouve : la règle écrite à un endroit, et non appliquée à l'entrée voisine du même menu.
     */
    fun moveToTrash() = tenterUneAction(ActionDEditeur.OrigineDErreur.CORBEILLE) {
        notes.moveToTrash(noteId)
        _action.value = ActionDEditeur(misAlaCorbeille = true)
    }

    /** Relance le chargement après un déverrouillage réussi. */
    fun retryAfterUnlock() {
        _state.value = _state.value.copy(lockedVault = null, loading = true)
        charger()
    }

    /**
     * Enregistre **maintenant**, sans attendre le délai.
     *
     * ⚠️ Appelé quand l'écran se ferme. `NonCancellable` : la portée du ViewModel est annulée à
     * l'instant où l'écran disparaît, et une sauvegarde annulable partirait à la poubelle avec
     * elle. C'est exactement la leçon de la phase 4 — rattraper une annulation avec du code
     * annulable ne rattrape rien.
     */
    fun saveNow() {
        sauvegardeDifferee?.cancel()
        // 🔴 **La portee du PROCESSUS, pas celle du ViewModel.**
        //
        // `viewModelScope` est annulé à l'instant où l'écran disparaît, c'est-à-dire exactement quand
        // cette sauvegarde est demandée. `withContext(NonCancellable)` ne protège qu'une coroutine
        // **déjà démarrée** : sur une portee annulée, `launch` crée une coroutine qui n'exécute
        // jamais son corps, et le texte tapé disparaît sans un mot.
        //
        // C'est la même leçon que la phase 4, un cran plus loin : ce n'est pas seulement l'annulation
        // qu'il faut rattraper, c'est la PORTEE qui doit survivre au geste qu'elle exécute. Relevé
        // par une relecture externe (Gemini, 2026-08-14).
        val instantane = _state.value
        applicationScope.launch {
            withContext(NonCancellable) { enregistrer(instantane) }
        }
    }

    private fun charger() {
        viewModelScope.launch {
            val note = notes.find(noteId)
            if (note == null) {
                _state.value = EditorUiState(loading = false, notFound = true)
                return@launch
            }
            val dossier = folders.find(note.folderId)
            if (!note.isLocked) {
                _state.value = EditorUiState(
                    loading = false,
                    title = note.title,
                    content = TextFieldValue(note.content),
                    note = note,
                    folder = dossier,
                    originalTitle = note.title,
                    originalContent = note.content,
                )
                return@launch
            }
            // 🔴 **Un dossier coffre introuvable ne se déverrouille pas.** Sans cette garde, l'écran
            // posait `lockedVault = null` et affichait un éditeur **vide**, sans erreur ni feuille de
            // saisie : la note existe, son dossier a disparu, et rien ne le disait.
            //
            // ⚠️ **Défense contre une base héritée, pas contre un chemin de cette application.**
            // `notes.folder_id` porte un `ON DELETE CASCADE` : supprimer un dossier emporte ses
            // notes, donc ce cas ne peut pas naître ici. Mais la base a été écrite par une **autre
            // application**, et rien ne garantit que ses suppressions se soient faites l'intégrité
            // référentielle active. Le garde coûte quatre lignes et remplace un écran muet.
            if (dossier == null) {
                _state.value = EditorUiState(
                    loading = false,
                    note = note,
                    loadError = R.string.note_editor_error_vault_folder_missing,
                )
                return@launch
            }

            // Note scellée : il faut la session du coffre pour l'afficher.
            val claire = try {
                vaults.decrypt(note)
            } catch (e: CancellationException) {
                throw e
            } catch (_: VaultSessionClosedException) {
                // Le seul cas où demander le secret a un sens : la session est fermée, elle peut
                // se rouvrir. C'est aussi ce que l'application publiée appelle « coffre
                // re-verrouillé », mais elle se contente de l'écrire — ici la feuille de saisie
                // s'ouvre sur place, donc `note_editor_error_vault_relocked` n'a rien à ajouter.
                _state.value = EditorUiState(loading = false, note = note, folder = dossier, lockedVault = dossier)
                return@launch
            } catch (e: Exception) {
                // Contenu chiffré abîmé, tag GCM tronqué, base en erreur : le secret n'y changerait
                // rien non plus.
                Timber.e(e, "dechiffrement de la note $noteId")
                _state.value = EditorUiState(
                    loading = false,
                    note = note,
                    folder = dossier,
                    loadError = R.string.note_editor_error_load_generic,
                )
                return@launch
            }
            // 🔴 The vault may have closed while it was decrypting: the collector of
            // [oublierLeClairALaFermetureDuCoffre] saw `loading` and let it be. Checked here, on the
            // same thread as that collector, so one of the two always sees the other.
            if (!vaults.isUnlocked(dossier.id)) {
                _state.value = EditorUiState(loading = false, note = note, folder = dossier, lockedVault = dossier)
                return@launch
            }
            _state.value = EditorUiState(
                loading = false,
                title = claire.title,
                content = TextFieldValue(claire.content),
                note = note,
                folder = dossier,
                originalTitle = claire.title,
                originalContent = claire.content,
            )
        }
    }

    /**
     * Programme un enregistrement après un temps de calme.
     *
     * ⚠️ Le travail précédent est **annulé** avant d'en lancer un nouveau. Sans ça, une frappe
     * rapide empile une sauvegarde par caractère, et sur une note de coffre, chacune paie un
     * chiffrement complet.
     */
    private fun programmerLaSauvegarde() {
        sauvegardeDifferee?.cancel()
        sauvegardeDifferee = viewModelScope.launch {
            delay(DELAI_AUTO_SAVE_MILLIS)
            enregistrer()
        }
    }

    private suspend fun enregistrer(instantane: EditorUiState? = null) = ecriture.withLock {
        val courant = instantane ?: _state.value
        val note = courant.note ?: return
        if (courant.lockedVault != null) return
        // ⚠️ Comparer au texte CHARGÉ, pas à l'entité en base : pour une note scellée, l'entité ne
        // porte pas le clair. Et **pas** de `!note.isLocked` ici : cette condition faisait sauter la
        // garde pour les seules notes où elle comptait.
        // ⚠️ `.text` : comparer la valeur entière ferait passer un déplacement de curseur pour une
        // modification, et rechiffrerait une note de coffre ouverte pour la seule lecture — c'est le
        // défaut que `originalTitle`/`originalContent` existent pour fermer, sous une autre forme.
        if (courant.title == courant.originalTitle && courant.content.text == courant.originalContent) return

        // ⚠️ `_state.value` et non `courant` : l'instantane sert a savoir QUOI persister, jamais a
        // reecrire l'etat de l'ecran. Le recopier reinjecterait un titre et un contenu peut-etre
        // plus anciens que ce que l'utilisateur a sous les yeux.
        _state.value = _state.value.copy(saving = true, saveFailed = false, saveFailureReason = null)
        try {
            val persistee = notes.saveEdits(
                id = noteId,
                title = courant.title,
                content = courant.content.text,
                tags = note.tags,
            )
            _state.value = _state.value.copy(
                // 🔴🔴 **La note de l'état doit suivre ce qui vient d'être écrit, et le rater a
                // produit un vrai défaut.**
                //
                // Une note créée dans un coffre naît **vide**, donc non scellée : `charger()` pose
                // alors `note.isLocked = false`. Le premier caractère tapé la scelle en base, mais
                // `state.note` gardait cette valeur du chargement — **périmée pour toujours**.
                //
                // Conséquence mesurée sur le S9 le 2026-08-15 : « Déplacer » vers un dossier
                // ordinaire ne posait **aucune** confirmation de sortie de coffre, puisque l'écran
                // croyait la note en clair. C'est le défaut exact que l'application publiée a corrigé
                // en v1.1.0 puis re-cassé en testant `encryptedContent != null` sur l'éphémère —
                // condition toujours fausse, dialogue jamais affiché. Reproduit ici sous une autre
                // forme : non plus le mauvais champ, mais le bon champ **jamais rafraîchi**.
                //
                // Le déplacement, lui, échouait bruyamment (`VaultRelocationException`) : c'est le
                // dépôt qui a rattrapé l'interface, et c'est bien pour ça qu'il refuse.
                note = persistee ?: _state.value.note,
                saving = false,
                // Ce qui vient d'etre persiste devient la nouvelle reference de comparaison.
                originalTitle = courant.title,
                originalContent = courant.content.text,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: VaultLockedException) {
            signalerLaPerte()
        } catch (_: VaultSessionClosedException) {
            signalerLaPerte()
        } catch (e: IllegalArgumentException) {
            // 🔴 **Dire POURQUOI, quand la cause est connue et corrigeable.**
            //
            // Un titre de plus de [NotesRepository.TITLE_MAX_LENGTH] caractères fait échouer
            // **chaque** enregistrement différé, indéfiniment. La bannière disait « Échec de
            // sauvegarde » et rien d'autre : l'utilisateur continuait d'écrire dans une note qui ne
            // s'enregistrait plus, sans savoir ce qui bloquait ni comment le débloquer.
            // `error_note_title_too_long` le dit exactement, et n'était lue nulle part.
            //
            // ⚠️ Le classement porte sur le TYPE et sur la longueur **mesurée** du titre, jamais
            // sur le message de l'exception : `require` produit du texte interne, non traduit, qui
            // n'a rien à faire à l'écran.
            //
            // ⚠️ **Ce n'est pas une preuve, c'est la meilleure heuristique disponible.** Si une
            // autre validation lève pendant que le titre dépasse aussi la limite, on nomme le titre.
            // Ce n'est pas faux — il faudra le raccourcir de toute façon — mais c'est incomplet, et
            // le second échec retombera sur le message générique. Relevé par une relecture externe
            // (Gemini, 2026-08-15) ; le classement par type d'exception dédiée viendrait de la couche
            // dépôt, qui ne distingue pas encore ses refus.
            Timber.e(e, "enregistrement refuse pour la note $noteId")
            // ⚠️ `courant.title` et NON `_state.value.title` : c'est `courant` qui a été soumis à
            // l'écriture. L'état, lui, a pu changer pendant l'appel — l'utilisateur tape toujours.
            // Mesurer sur l'état revenait à juger la tentative sur un texte qu'elle n'a jamais vu :
            // titre raccourci entre-temps, on tait la vraie cause ; titre allongé, on l'invente.
            // Relevé par une relecture externe (GPT-5.2, 2026-08-15).
            val titreTropLong = courant.title.length > NotesRepository.TITLE_MAX_LENGTH
            _state.value = _state.value.copy(
                saving = false,
                saveFailed = true,
                saveFailureReason = if (titreTropLong) R.string.error_note_title_too_long else null,
            )
        } catch (e: Exception) {
            // 🔴 **Un échec d'enregistrement DOIT se voir.** Journaliser et rendre la main laissait
            // l'utilisateur taper dans le vide : l'écran se comportait normalement, et le texte
            // n'existait nulle part. Stockage plein, base verrouillée, erreur SQLCipher — toutes ces
            // causes produisaient une perte parfaitement silencieuse.
            //
            // C'est l'invariant « une perte de données se signale », et il était tenu pour le
            // verrouillage de coffre (bannière) mais pas pour le reste. Jumeau asymétrique. Relevé
            // par une relecture externe (GPT-5.2, 2026-08-14).
            Timber.e(e, "enregistrement de la note $noteId")
            _state.value = _state.value.copy(saving = false, saveFailed = true)
        }
    }

    /**
     * 🔴 Inscrit la note dans la liste des modifications perdues.
     *
     * Le coffre s'est refermé entre la frappe et l'écriture. Le texte tapé n'existe plus qu'à
     * l'écran, et il disparaîtra avec lui. C'est la seule trace qui permettra à l'accueil de le
     * dire — sans elle, la perte est parfaitement silencieuse.
     */
    private fun signalerLaPerte() = passerALEtatVerrouille(perte = true)

    private fun enArrierePlan(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "action d'editeur sur $noteId")
            }
        }
    }

    private companion object {
        /** `AppConstants.autoSaveDebounce` — le même demi-seconde que la version publiée. */
        const val DELAI_AUTO_SAVE_MILLIS = 500L

        /**
         * Aligne sur l'accueil : les curseurs se ferment 5 s apres le dernier abonne.
         *
         * ⚠️ Ce nom est celui des quatre autres ViewModels du depot (`HomeViewModel`,
         * `SearchViewModel`, `TrashViewModel`, `FoldersDrawerViewModel`). La premiere version
         * l'appelait `ARRET_ABONNEMENT_MILLIS` : meme valeur, meme role, un nom de plus — donc un
         * `grep` d'audit sur les delais de desabonnement qui rate ce cinquieme site.
         */
        const val ARRET_DIFFERE_MILLIS = 5_000L

        /**
         * 120 ms, comme `link_autocomplete_sheet.dart`. Court exprès : l'appariement est une
         * requete `LIKE` sur une colonne indexee, sans cout d'inference — freiner davantage se
         * verrait comme une latence sans rien economiser.
         */
        const val FREINAGE_SUGGESTIONS_MILLIS = 120L
    }
}
