package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.export.ExportResult
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.data.local.dao.NoteLinkRow
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.LinksRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.di.ApplicationScope
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.repository.VaultLockedException
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.VaultSessionClosedException
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
import kotlinx.coroutines.flow.transformLatest
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
 * ⚠️ [erreur] porte le message de l'exception, comme l'export des réglages et contrairement au mode
 * panique. Le choix est le même et pour la même raison : ici l'utilisateur cherche à comprendre
 * pourquoi son geste n'a rien donné, et « espace insuffisant » ou « coffre re-verrouillé » lui
 * servent — là-bas, l'écran peut être lu sous contrainte.
 */
data class ActionDEditeur(
    val enCours: Boolean = false,
    val export: ExportResult? = null,
    val deplacee: Boolean = false,
    val misAlaCorbeille: Boolean = false,
    val erreur: String? = null,
    /**
     * ⚠️ **Quelle action a échoué**, pour que l'écran choisisse la bonne phrase.
     *
     * Sans ce champ, un export raté s'annonçait « Déplacement impossible » — les deux chaînes
     * existent, et n'en utiliser qu'une revient à dire à l'utilisateur que son geste a échoué, mais
     * un autre que celui qu'il a fait.
     */
    val origine: OrigineDErreur? = null,
) {
    enum class OrigineDErreur { DEPLACEMENT, EXPORT, CREATION, CORBEILLE }
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
     */
    // ⚠️ Pas de `distinctUntilChanged` : un `StateFlow` ne réémet déjà pas une valeur égale, et
    // l'opérateur y est déprécié pour cette raison même.
    @OptIn(ExperimentalCoroutinesApi::class)
    val suggestionsDeLien: StateFlow<List<Note>> = requeteDeLien
        .transformLatest { texte ->
            // 🔴🔴 **Vider AVANT d'attendre, et c'est tout l'objet de `transformLatest`.**
            //
            // La version précédente posait `debounce` en tête : pendant les 120 ms qui suivaient une
            // frappe, le porteur gardait **la liste calculée pour la requête d'avant**. L'utilisateur
            // tapait « Alpha », voyait ses suggestions, remplaçait par « Beta » — et pouvait toucher
            // une proposition « Alpha » encore affichée sous un champ qui disait « Beta ». Le lien
            // inséré désignait alors une autre note que celle cherchée, sans un mot.
            //
            // Le même défaut vidait de travers : `reinitialiserLaRecherche()` posait bien la chaîne
            // vide, mais elle passait par le freinage elle aussi — rouvrir la feuille assez vite
            // montrait donc les résultats de la fois d'avant, sous un champ vierge.
            //
            // Ici la liste part à vide **à chaque nouvelle requête**, de façon synchrone avec la
            // frappe, et ne se remplit qu'après le calme. Un affichage vide est honnête ; un
            // affichage périmé ne l'est pas. Relevé par la relecture externe du 2026-08-15.
            emit(emptyList())
            if (texte.isBlank()) return@transformLatest
            delay(FREINAGE_SUGGESTIONS_MILLIS)
            emit(notes.suggestTitles(texte, excludeId = noteId))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = emptyList(),
        )

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
     * Crée la note qu'un lien **fantôme** désigne, sans toucher au texte.
     *
     * ⚠️ Aucune insertion ici, et c'est la différence avec [creerPuisLier] : le `[[Titre]]` est déjà
     * écrit dans la note — c'est même ce qui a produit le lien fantôme. En insérer un second
     * dupliquerait le lien à un endroit que l'utilisateur n'a pas choisi.
     *
     * Le rattachement se fait tout seul : `resolveIncoming` accroche les liens fantômes visant ce
     * titre au moment où la note naît. Le panneau le montrera résolu à la prochaine émission.
     */
    fun creerLaNoteManquante(titre: String) = creerDansLeMemeDossier(titre)

    /**
     * ⚠️ **Le dossier est celui de la note courante, pas la boîte de réception.**
     *
     * Une note créée depuis un lien hérite du contexte où le lien a été écrit — y compris un coffre.
     * L'envoyer d'office dans la boîte de réception sortirait discrètement du coffre une note que
     * l'utilisateur vient de créer depuis l'intérieur.
     */
    private fun creerDansLeMemeDossier(titre: String, ensuite: () -> Unit = {}) {
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

    /**
     * Déplace la note vers [folderId].
     *
     * ⚠️ **Impossible depuis un coffre**, et le dépôt le refuse par une exception typée
     * (`VaultRelocationException`). Sortir une note d'un coffre écrit son contenu en clair dans la
     * base : c'est irréversible au sens qui compte — la note aura transité hors chiffrement même si
     * on la remet ensuite ailleurs — et cela demande une confirmation explicite que l'application
     * publiée pose (`note_editor_exit_vault_*`). Ni cette confirmation ni l'opération de dépôt qui
     * la suit n'existent encore ici : l'entrée de menu est donc **désactivée** pour une note de
     * coffre, plutôt que de mener à un échec ou à un dialogue sans effet.
     */
    fun deplacerVers(folderId: String) = tenterUneAction(ActionDEditeur.OrigineDErreur.DEPLACEMENT) {
        notes.moveToFolder(noteId, folderId)

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
    fun exporterLaNote(vaultMention: (String) -> String) = tenterUneAction(ActionDEditeur.OrigineDErreur.EXPORT) {
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
        _action.value = ActionDEditeur(export = exporter.exportOne(fraiche, dossier?.name.orEmpty(), vaultMention))
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
                _action.value = ActionDEditeur(erreur = e.message ?: e::class.java.simpleName, origine = origine)
            }
        }
    }

    init {
        charger()
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

    fun setPinned(pinned: Boolean) = enArrierePlan { notes.setPinned(noteId, pinned) }

    fun setFavorite(favorite: Boolean) = enArrierePlan { notes.setFavorite(noteId, favorite) }

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
            // Note scellée : il faut la session du coffre pour l'afficher.
            val claire = try {
                vaults.decrypt(note)
            } catch (e: CancellationException) {
                throw e
            } catch (_: VaultSessionClosedException) {
                _state.value = EditorUiState(loading = false, note = note, folder = dossier, lockedVault = dossier)
                return@launch
            } catch (e: Exception) {
                Timber.e(e, "dechiffrement de la note $noteId")
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
        _state.value = _state.value.copy(saving = true, saveFailed = false)
        try {
            notes.saveEdits(
                id = noteId,
                title = courant.title,
                content = courant.content.text,
                tags = note.tags,
            )
            _state.value = _state.value.copy(
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
    private fun signalerLaPerte() {
        settings.addVaultLostDraft(noteId)
        _state.value = _state.value.copy(saving = false, lostToVaultLock = true, lockedVault = _state.value.folder)
    }

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
