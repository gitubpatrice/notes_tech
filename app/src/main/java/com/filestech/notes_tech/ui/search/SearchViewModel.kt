package com.filestech.notes_tech.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.SearchRepository
import com.filestech.notes_tech.domain.model.Note
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Note> = emptyList(),
    val folderNamesById: Map<String, String> = emptyMap(),
    /**
     * 🔴 **La recherche a échoué**, et il fallait bien que quelque chose puisse le dire.
     *
     * Le flux n'avait aucun filet : une erreur de la base remontait jusqu'à `stateIn`, qui la
     * relance depuis sa propre coroutine — donc **jusqu'au gestionnaire d'exceptions non
     * capturées**. L'écran ne se figeait pas, il emportait l'application.
     *
     * ⚠️ Ce n'est pas la syntaxe FTS5 qui menace : `FtsMatchExpression.from` la rend impossible,
     * et c'est pour ça que cette chaîne n'était lue nulle part alors que l'application publiée s'en
     * sert. Ce qui reste, c'est l'index abîmé, le disque plein, la base fermée sous les pieds.
     */
    val failed: Boolean = false,
    /**
     * 🔴 **La réponse en main ne répond pas à la saisie courante**, et l'écran affirmait le
     * contraire.
     *
     * Le `combine` ci-dessous mêle deux flux de rythmes différents : la saisie, qui émet à chaque
     * frappe, et les résultats, qui passent par un freinage de 250 ms **puis** par une requête. Entre
     * les deux, l'état portait la **nouvelle** requête et l'**ancienne** issue — donc, pour une
     * première recherche, une liste vide. L'écran en concluait « Aucun résultat. Essayez un autre
     * mot-clé » : un message qui **accuse la saisie de l'utilisateur** pour une réponse qui n'est pas
     * encore arrivée.
     *
     * `search_screen.dart:109` ne fait pas cette faute — il rend un indicateur d'activité tant que
     * `snap.connectionState == ConnectionState.waiting`. C'était donc une **régression du portage**.
     *
     * ⚠️ Ce drapeau et [failed] sont **exclusifs par construction** : tant qu'on ne sait pas, on ne
     * peut pas non plus savoir que ça a échoué. L'échec d'une requête abandonnée ne doit pas
     * s'afficher sous la requête suivante.
     */
    val searching: Boolean = false,
)

/**
 * Ce qu'une requête a donné : des résultats, ou un échec. Jamais les deux.
 *
 * ⚠️ [pour] porte **la saisie à laquelle cette issue répond**, et c'est tout le mécanisme de
 * [SearchUiState.searching] : le seul moyen fiable de savoir si la réponse en main est celle de la
 * question posée est de lui faire porter la question.
 *
 * ⚠️ `null` veut dire **aucune réponse, pour aucune requête** — l'état d'ouverture de l'écran. Il ne
 * peut être égal à aucune saisie, pas même vide, ce qui est exactement la sémantique voulue.
 */
internal data class Issue(
    val pour: String? = null,
    val resultats: List<Note> = emptyList(),
    val echec: Boolean = false,
)

/**
 * La transformation qui fabrique l'état de l'écran, **extraite exprès du `combine`**.
 *
 * `SearchRepository` et `FoldersRepository` sont des classes concrètes construites sur un
 * `DatabaseProvider` : le ViewModel entier n'est pas exerçable hors appareil. Cette fonction-ci est
 * pure, et c'est elle qui porte l'invariant du défaut §76 — donc elle se teste sur la JVM
 * (`RechercheEtatTest`), sans quoi les tests d'écran resteraient les seuls, à poser `searching` à la
 * main sans jamais prouver que quelque chose le calcule.
 *
 * ⚠️ **La valeur initiale de `stateIn` passe par ici aussi**, avec `Issue()` : c'est ce qui garantit
 * qu'une requête restaurée depuis `SavedStateHandle` s'ouvre en « je cherche » et non en « aucun
 * résultat ». Deux chemins vers le même état demanderaient deux fois la même vérification.
 */
internal fun etatDeRecherche(texte: String, issue: Issue, noms: Map<String, String>): SearchUiState {
    // 🔴 Ce que cette comparaison distingue : « aucun résultat » et « pas encore de réponse ».
    val repondALaSaisie = issue.pour == texte
    return SearchUiState(
        query = texte,
        results = issue.resultats,
        folderNamesById = noms,
        // ⚠️ L'échec d'une requête **abandonnée** ne s'affiche pas sous la suivante : il ne dit rien
        // de celle-ci. C'est ce qui rend `failed` et `searching` exclusifs.
        failed = issue.echec && repondALaSaisie,
        searching = texte.isNotBlank() && !repondALaSaisie,
    )
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val search: SearchRepository,
    folders: FoldersRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val query = MutableStateFlow(savedState.get<String>(CLE_RECHERCHE).orEmpty())

    val state: StateFlow<SearchUiState> = combine(
        query,
        query.debounce(FREINAGE_MILLIS).distinctUntilChanged().flatMapLatest { texte ->
            // Une saisie vide ne déclenche aucune requête : l'écran affiche son état d'accueil.
            if (texte.isBlank()) {
                flowOf(Issue(pour = texte))
            } else {
                // 🔴 **Le `catch` est À L'INTÉRIEUR du `flatMapLatest`, et c'est tout l'enjeu.**
                //
                // `catch` est **terminal** : posé sur le flux extérieur, il émettait l'état d'échec
                // puis **terminait le flux d'état**. Le `combine` mourait avec lui, et plus aucune
                // frappe n'était jamais servie — la recherche restait figée sur son erreur jusqu'à
                // la destruction du ViewModel. Le filet censé protéger d'une panne passagère la
                // rendait définitive. Relevé CONFIRMÉ par une relecture externe (Gemini, 2026-08-15).
                //
                // Ici il ne couvre que le flux d'UNE requête : la suivante repart d'un flux neuf.
                search.observe(texte)
                    .map { Issue(pour = texte, resultats = it) }
                    .catch { erreur ->
                        Timber.e(erreur, "recherche plein texte")
                        emit(Issue(pour = texte, echec = true))
                    }
            }
        },
        folders.observeAll().map { liste -> liste.associate { it.id to it.name } },
    ) { texte, issue, noms ->
        // ⚠️ La requête reste affichée en cas d'échec : l'utilisateur doit voir ce qu'il a tapé pour
        // comprendre à quoi se rapporte le message. Les résultats, eux, sont vides — en garder
        // d'anciens à côté d'une erreur laisserait croire qu'ils correspondent à la saisie courante.
        etatDeRecherche(texte, issue, noms)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
        // ⚠️ **La valeur initiale n'est pas une donnée, c'est une absence de donnée** — leçon §75. Une
        // requête restaurée depuis `SavedStateHandle` n'a par définition aucune réponse en main :
        // l'annoncer « sans résultat » serait mentir dès la recomposition qui suit une rotation. Le
        // `Issue()` sans `pour` dit précisément ça, et le même code en tire la conclusion.
        initialValue = etatDeRecherche(query.value, Issue(), emptyMap()),
    )

    fun onQueryChange(value: String) {
        query.value = value
        savedState[CLE_RECHERCHE] = value
    }

    private companion object {
        const val FREINAGE_MILLIS = 250L
        const val ARRET_DIFFERE_MILLIS = 5_000L
        const val CLE_RECHERCHE = "search.query"
    }
}
