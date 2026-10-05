package com.filestech.notes_tech.data.repository

import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.mapper.toDomain
import com.filestech.notes_tech.domain.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La recherche plein texte, vue du domaine.
 *
 * Fine à dessein : toute la difficulté est dans `NoteSearchDao` — l'échappement de la requête FTS5,
 * l'ordre `bm25`, et surtout la double garde qui empêche une note verrouillée de ressortir.
 *
 * ## 🔴 Ce que cette couche ne doit jamais faire
 *
 * Filtrer les résultats après coup. La garantie « une note de coffre ne ressort pas d'une
 * recherche » est tenue **dans la requête**, deux fois : par les triggers hérités qui n'indexent
 * que du vide pour les notes chiffrées, et par un `encrypted_content IS NULL` explicite. Ajouter un
 * troisième filtre ici donnerait l'impression que la garantie vit dans l'interface, et la
 * prochaine personne qui a besoin d'un autre appelant l'oublierait.
 */
@Singleton
class SearchRepository @Inject constructor(private val databases: DatabaseProvider) {

    /**
     * Cherche [query] et suit les changements.
     *
     * Rend un flux vide — jamais une erreur — si la saisie ne contient aucun terme exploitable :
     * taper trois espaces n'est pas une faute de l'utilisateur.
     */
    fun observe(query: String): Flow<List<Note>> =
        flow { emitAll(databases.get().noteSearchDao().search(query)) }.map { it.toDomain() }
}
