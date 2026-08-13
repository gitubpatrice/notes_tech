package com.filestech.notes_tech.data.local.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.filestech.notes_tech.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Liens `[[Titre]]` entre notes — lectures.
 *
 * ## Pourquoi tout passe par `@RawQuery`
 *
 * `note_links` **n'a pas de clé primaire** dans la base héritée, or `@Entity` en exige une et Room
 * compare les clés primaires à l'ouverture : déclarer une clé absente ferait échouer l'ouverture
 * chez tous les utilisateurs. La table reste donc hors du graphe d'entités
 * (cf. [com.filestech.notes_tech.data.local.UnmanagedSchema]), ce qui interdit `@Query` — sa
 * vérification à la compilation refuserait une table inconnue.
 *
 * `observedEntities = [NoteEntity::class]` rend les flux réactifs. Ce n'est pas un contournement
 * de la réactivité mais sa description exacte : les liens sont **dérivés** du contenu des notes et
 * ne changent jamais sans qu'une note change.
 *
 * ## Les écritures ne sont pas ici
 *
 * Elles vivent dans `NoteLinkWriter`, qui écrit par `SupportSQLiteDatabase`. La séparation est
 * délibérée : un `@Dao` ne peut pas exécuter de SQL arbitraire en écriture, et mélanger les deux
 * mécanismes dans un même type laisserait croire qu'ils offrent les mêmes garanties.
 */
@Dao
abstract class NoteLinkDao {

    @RawQuery(observedEntities = [NoteEntity::class])
    protected abstract fun rawLinks(query: SupportSQLiteQuery): Flow<List<NoteLinkRow>>

    @RawQuery(observedEntities = [NoteEntity::class])
    protected abstract fun rawNotes(query: SupportSQLiteQuery): Flow<List<NoteEntity>>

    /** Les liens **partant** de [noteId], dans l'ordre où ils apparaissent dans le texte. */
    fun outgoing(noteId: String): Flow<List<NoteLinkRow>> = rawLinks(
        SimpleSQLiteQuery(
            """
            SELECT source_id, target_id, target_title, target_title_norm, position
            FROM note_links WHERE source_id = ? ORDER BY position ASC
            """,
            arrayOf<Any?>(noteId),
        ),
    )

    /**
     * Les notes qui **pointent vers** [noteId] — les rétroliens.
     *
     * ## ⚠️ Deux façons de pointer, pas une
     *
     * Un lien vise sa cible soit par identifiant (`target_id`), soit — s'il est encore fantôme — par
     * titre normalisé. Une première version de cette requête ne retenait que la première :
     * `[[Réunion]]` écrit avant l'existence de la note « Réunion » n'apparaissait pas dans ses
     * rétroliens tant qu'aucune réindexation n'était passée le résoudre.
     *
     * L'écart était invisible en test — il faut avoir écrit le lien **avant** la note pour le voir.
     * Relevé en portant la couche des repositories, par comparaison avec `links_dao.dart:64`.
     *
     * ⚠️ [titleNorm] doit venir de `TitleNormalizer`, jamais d'un titre brut : c'est la clé
     * d'appariement, pas le libellé.
     *
     * ## Les notes verrouillées sont exclues
     *
     * Un rétrolien affiche le titre de la note **source** ; laisser passer une note de coffre
     * divulguerait ce titre depuis une liste que consulte une note non protégée.
     *
     * En principe la question ne se pose pas — les liens d'une note verrouillée sont effacés au
     * verrouillage. C'est précisément pourquoi la garde est ici : elle ne dépend d'aucune donnée
     * écrite par un autre programme, et l'application publiée, elle, ne l'a pas.
     */
    fun backlinks(noteId: String, titleNorm: String): Flow<List<NoteEntity>> = rawNotes(
        SimpleSQLiteQuery(
            """
            SELECT DISTINCT n.* FROM note_links l
            JOIN notes n ON n.id = l.source_id
            WHERE (l.target_id = ? OR (l.target_id IS NULL AND l.target_title_norm = ?))
              AND n.trashed_at IS NULL
              AND n.encrypted_content IS NULL
            ORDER BY n.updated_at DESC
            """,
            arrayOf<Any?>(noteId, titleNorm),
        ),
    )

    /**
     * Les liens **fantômes** : ceux qui visent un titre pour lequel aucune note n'existe.
     *
     * `target_id IS NULL` les identifie. Ils se résolvent tout seuls si l'utilisateur crée plus
     * tard une note portant ce titre — d'où l'intérêt de les lister, pour proposer la création.
     */
    fun dangling(): Flow<List<NoteLinkRow>> = rawLinks(
        SimpleSQLiteQuery(
            """
            SELECT source_id, target_id, target_title, target_title_norm, position
            FROM note_links WHERE target_id IS NULL ORDER BY target_title_norm ASC
            """,
        ),
    )
}

/**
 * Une ligne de `note_links`.
 *
 * Pas une `@Entity` — délibérément, voir [NoteLinkDao]. Room sait peupler cette classe depuis un
 * `@RawQuery` par le nom des colonnes, sans qu'elle fasse partie du schéma qu'il valide.
 */
data class NoteLinkRow(
    @androidx.room.ColumnInfo(name = "source_id") val sourceId: String,
    /** `null` = lien fantôme, vers un titre qui n'existe pas encore. */
    @androidx.room.ColumnInfo(name = "target_id") val targetId: String?,
    @androidx.room.ColumnInfo(name = "target_title") val targetTitle: String,
    /** Titre cible normalisé — minuscules, sans diacritiques — pour un appariement insensible. */
    @androidx.room.ColumnInfo(name = "target_title_norm") val targetTitleNorm: String,
    /** Décalage du lien dans le texte de la note source. */
    @androidx.room.ColumnInfo(name = "position") val position: Int,
)
