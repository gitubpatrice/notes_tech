package com.filestech.notes_tech.data.repository

import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.NotesDatabase
import com.filestech.notes_tech.data.local.dao.NoteLinkRow
import com.filestech.notes_tech.data.local.mapper.toDomain
import com.filestech.notes_tech.domain.links.TitleNormalizer
import com.filestech.notes_tech.domain.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lecture des liens `[[Titre]]` entre notes.
 *
 * ## Lecture seule, et c'est structurel
 *
 * Les liens ne s'écrivent **jamais** depuis ici. Ils sont dérivés du contenu d'une note et se
 * réécrivent dans la transaction qui écrit cette note — voir `NotesRepository`. Exposer une
 * écriture de liens ici offrirait le moyen de les désynchroniser d'avec le texte dont ils sortent,
 * pour un gain nul.
 *
 * C'est la différence avec l'application publiée, où le repository des liens porte ses propres
 * écritures et son propre flux de notifications, consommés par un service d'indexation séparé.
 */
@Singleton
class LinksRepository @Inject constructor(private val databases: DatabaseProvider) {

    /** Les liens **partant** de [noteId], dans l'ordre où ils apparaissent dans le texte. */
    fun observeOutgoing(noteId: String): Flow<List<NoteLinkRow>> = observing { it.noteLinkDao().outgoing(noteId) }

    /**
     * Les notes qui mentionnent [note] — par identifiant **ou** par titre, pour les liens encore
     * fantômes.
     *
     * ⚠️ La normalisation du titre se fait ici, jamais chez l'appelant : c'est la clé d'appariement
     * de la base, et une interface qui passerait le titre brut ne trouverait aucun lien fantôme
     * vers un titre accentué — c'est-à-dire l'essentiel d'un corpus français.
     *
     * ## ⚠️ Une note verrouillée n'a pas de rétroliens, et la requête n'est même pas lancée
     *
     * Le court-circuit est explicite plutôt que dérivé. Une version précédente passait une clé
     * normalisée vide, en comptant sur le fait qu'aucune ligne de `note_links` n'en porte — ce qui
     * est vrai, parce que l'extraction des liens écarte les titres dont la normalisation est vide,
     * des deux côtés.
     *
     * C'était **une garantie de confidentialité tenue par une propriété distante**. Elle cesserait
     * de tenir si une ligne à `target_title_norm` vide entrait dans la base par un autre chemin —
     * une restauration, une version future, un autre programme — et alors la clé vide apparierait
     * ces lignes, faisant remonter des rétroliens pour une note de coffre.
     *
     * Signalé comme point de vigilance par une relecture externe (Gemini 3.1 Pro, 2026-08-13), qui
     * concluait que le chemin était fermé. Il l'était ; il l'est maintenant sans dépendre d'autre
     * chose que de cette ligne.
     */
    fun observeBacklinks(note: Note): Flow<List<Note>> {
        if (note.isLocked) return flowOf(emptyList())
        val titleNorm = TitleNormalizer.normalize(note.title)
        return observing { it.noteLinkDao().backlinks(note.id, titleNorm) }.map { it.toDomain() }
    }

    /**
     * Les liens **fantômes** : ceux qui visent un titre pour lequel aucune note n'existe.
     *
     * Ils se résolvent d'eux-mêmes si l'utilisateur crée plus tard une note portant ce titre — d'où
     * l'intérêt de les lister, pour lui proposer de la créer.
     */
    fun observeDangling(): Flow<List<NoteLinkRow>> = observing { it.noteLinkDao().dangling() }

    private fun <T> observing(source: (NotesDatabase) -> Flow<T>): Flow<T> = flow { emitAll(source(databases.get())) }
}
