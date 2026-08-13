package com.filestech.notes_tech.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.filestech.notes_tech.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Accès à `notes`.
 *
 * ## ⚠️ Aucune méthode n'utilise `OnConflictStrategy.REPLACE`
 *
 * Sur cette table, `REPLACE` est une perte de données silencieuse — et doublement :
 *
 * 1. `note_links.source_id` porte `ON DELETE CASCADE` : le `DELETE` interne du `REPLACE`
 *    **supprime tous les backlinks de la note** ;
 * 2. `notes_fts` est un index à contenu externe adossé au `rowid` : `REPLACE` attribue un
 *    **nouveau** `rowid`, et l'entrée d'index pointe désormais dans le vide.
 *
 * Le trigger `notes_ad` ne rattrape rien : `PRAGMA recursive_triggers` vaut `OFF` par défaut, donc
 * il ne se déclenche même pas sur le `DELETE` interne. Cf. `docs/04-PIEGES.md` §1.
 *
 * Écriture = [insert] **ou** [update], explicitement.
 */
@Dao
interface NoteDao {

    /**
     * Notes actives d'un dossier : ni archivées, ni à la corbeille.
     *
     * ⚠️ **L'index ne couvre que le filtre, pas le tri.** `idx_notes_folder_active`
     * (`folder_id, archived, trashed_at, updated_at DESC`) sert bien les trois clauses `WHERE`,
     * mais `pinned` n'en fait pas partie : SQLite doit donc trier en mémoire le sous-ensemble
     * retenu.
     *
     * C'est assumé, et ça ne se corrige pas ici : ajouter un index modifierait le schéma, donc
     * ferait échouer la validation de Room sur toutes les bases existantes qui ne l'auraient pas
     * (cf. `docs/01-DECISIONS.md` D-005). Le coût est un tri sur les notes d'un seul dossier —
     * négligeable aux volumes réels, et le prix de la compatibilité avec la base héritée.
     *
     * *(Un commentaire antérieur affirmait ici que l'index « couvrait la requête ». C'était faux,
     * et signalé par la relecture externe du 2026-08-13.)*
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE folder_id = :folderId AND archived = 0 AND trashed_at IS NULL
        ORDER BY pinned DESC, updated_at DESC
        """,
    )
    fun observeActiveInFolder(folderId: String): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE archived = 0 AND trashed_at IS NULL
        ORDER BY pinned DESC, updated_at DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE trashed_at IS NOT NULL ORDER BY trashed_at DESC")
    fun observeTrash(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun findById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: String): Flow<NoteEntity?>

    /**
     * Toutes les notes verrouillées d'un dossier.
     *
     * Sert au verrouillage et au déverrouillage en masse d'un coffre. Le critère est
     * `encrypted_content IS NOT NULL` — **jamais** `content = ''`, qui est aussi vrai d'une note
     * vide ordinaire.
     */
    @Query("SELECT * FROM notes WHERE folder_id = :folderId AND encrypted_content IS NOT NULL")
    suspend fun findLockedInFolder(folderId: String): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    /**
     * Met à la corbeille. Ne supprime rien : la note reste jusqu'à [purgeTrashedBefore].
     *
     * `trashed_at` est passé par l'appelant plutôt que lu par un `strftime` en SQL : une horloge
     * injectable d'un seul côté rend les tests vacants — on écrit avec l'heure réelle, on vérifie
     * contre une heure figée, et les assertions passent pour la mauvaise raison.
     */
    @Query("UPDATE notes SET trashed_at = :trashedAt WHERE id = :id")
    suspend fun moveToTrash(id: String, trashedAt: Long)

    @Query("UPDATE notes SET trashed_at = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    /**
     * Suppression définitive.
     *
     * Le trigger `notes_ad` retire l'entrée de l'index plein texte, et la cascade de
     * `note_links.source_id` retire les liens partant de cette note. Les liens qui **pointaient**
     * vers elle passent à `target_id = NULL` (`ON DELETE SET NULL`) et redeviennent des liens
     * fantômes — comportement hérité, voulu : le texte `[[titre]]` reste dans les notes sources.
     */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deletePermanently(id: String)

    /**
     * Purge les notes en corbeille depuis avant [cutoff]. Rétention héritée : 30 jours.
     *
     * @return le nombre de notes réellement supprimées, pour que l'appelant puisse le rapporter
     *   au lieu de l'affirmer.
     */
    @Query("DELETE FROM notes WHERE trashed_at IS NOT NULL AND trashed_at < :cutoff")
    suspend fun purgeTrashedBefore(cutoff: Long): Int

    /**
     * Réassigne les notes d'un dossier vers un autre.
     *
     * Sert à vider un dossier avant sa suppression, quand l'utilisateur choisit de garder ses
     * notes — sans cela, la cascade `ON DELETE CASCADE` les emporterait.
     */
    @Query("UPDATE notes SET folder_id = :destinationId WHERE folder_id = :sourceId")
    suspend fun reassignFolder(sourceId: String, destinationId: String)
}
