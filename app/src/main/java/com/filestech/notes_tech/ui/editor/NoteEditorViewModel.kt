package com.filestech.notes_tech.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
            started = SharingStarted.WhileSubscribed(ARRET_ABONNEMENT_MILLIS),
            initialValue = PanneauDeLiens(),
        )

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

    fun moveToTrash() = enArrierePlan { notes.moveToTrash(noteId) }

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

        /** Aligne sur l'accueil : les curseurs se ferment 5 s apres le dernier abonne. */
        const val ARRET_ABONNEMENT_MILLIS = 5_000L
    }
}
