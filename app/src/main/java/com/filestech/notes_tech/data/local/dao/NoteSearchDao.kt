package com.filestech.notes_tech.data.local.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.filestech.notes_tech.data.local.FtsMatchExpression
import com.filestech.notes_tech.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Recherche plein texte sur `notes_fts`.
 *
 * ## Pourquoi `@RawQuery` et pas `@Query`
 *
 * `notes_fts` est une table virtuelle **FTS5**, absente du graphe d'entités de Room — Room ne sait
 * annoter que du FTS4, et la déclarer produirait un index incompatible avec l'existant
 * (cf. [com.filestech.notes_tech.data.local.UnmanagedSchema]). Un `@Query` échouerait à la
 * compilation sur « no such table ».
 *
 * `observedEntities = [NoteEntity::class]` rend malgré tout le `Flow` réactif, et ce n'est pas un
 * contournement : l'index est **dérivé** de `notes` par trois triggers, il ne peut pas changer
 * sans que `notes` change.
 */
@Dao
abstract class NoteSearchDao {

    @RawQuery(observedEntities = [NoteEntity::class])
    protected abstract fun searchRaw(query: SupportSQLiteQuery): Flow<List<NoteEntity>>

    /**
     * Cherche [rawInput] dans les titres, contenus et étiquettes des notes non supprimées.
     *
     * Rend un flux vide si la saisie ne contient aucun terme exploitable — un utilisateur qui tape
     * seulement des espaces ou de la ponctuation ne doit pas déclencher une erreur de syntaxe
     * FTS5.
     *
     * ⚠️ Les notes verrouillées ne ressortent **jamais** : les triggers insèrent des chaînes vides
     * dans l'index pour toute note dont `encrypted_content` n'est pas nul. La garde est donc dans
     * les données, pas dans cette requête — c'est plus solide, mais ça veut dire qu'un futur
     * changement de trigger casserait la promesse sans toucher à ce fichier.
     */
    fun search(rawInput: String, limit: Int = DEFAULT_LIMIT): Flow<List<NoteEntity>> {
        val match = FtsMatchExpression.from(rawInput) ?: return flowOf(emptyList())
        return searchRaw(SimpleSQLiteQuery(SEARCH_SQL, arrayOf<Any?>(match, limit)))
    }

    private companion object {
        const val DEFAULT_LIMIT = 100

        /**
         * `bm25` rend un score **croissant vers le moins pertinent** : le tri est donc ascendant.
         * Un `DESC` ici remonterait les pires résultats en tête, ce qui ne se voit pas sur un jeu
         * de test à trois notes.
         */
        /**
         * ⚠️ `n.encrypted_content IS NULL` est une **seconde ligne de défense**, et elle n'est pas
         * redondante.
         *
         * Les triggers hérités masquent déjà les notes verrouillées en insérant des chaînes vides
         * dans l'index. Mais ces triggers ont été écrits par une **autre application**, ils vivent
         * dans la base de l'utilisateur, et rien ici ne les recrée ni ne les vérifie à l'ouverture :
         * une base dont les triggers seraient absents, anciens ou abîmés ferait ressortir le titre
         * en clair d'une note de coffre, puisque le `JOIN` rend `n.*`.
         *
         * Faire dépendre une promesse de non-divulgation de données écrites par un autre programme
         * n'est pas acceptable. Le filtre coûte zéro — le `JOIN` touche déjà `notes` — et rend la
         * garantie autonome.
         *
         * Constat remonté par la relecture externe (GPT-5.2, 2026-08-13) sur la couche DAO.
         *
         * ℹ️ `notes_fts` reste référencé par son nom alors que la table est aliasée `f`, et c'est
         * correct : FTS5 expose une **colonne cachée** portant le nom de la table, donc
         * `notes_fts MATCH ?` et `bm25(notes_fts)` résolvent comme colonnes, pas comme tables.
         * Vérifié sur appareil (`LegacyDatabaseOpeningTest`), la relecture externe le soupçonnait
         * d'être une erreur de syntaxe.
         */
        const val SEARCH_SQL = """
            SELECT n.* FROM notes_fts f
            JOIN notes n ON n.rowid = f.rowid
            WHERE notes_fts MATCH ?
              AND n.trashed_at IS NULL
              AND n.encrypted_content IS NULL
            ORDER BY bm25(notes_fts) ASC
            LIMIT ?
        """
    }
}
