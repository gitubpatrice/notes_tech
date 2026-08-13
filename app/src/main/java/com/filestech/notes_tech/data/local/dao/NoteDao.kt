package com.filestech.notes_tech.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.filestech.notes_tech.data.local.entity.NoteEntity
import com.filestech.notes_tech.domain.model.NoteSortMode
import kotlinx.coroutines.flow.Flow

/**
 * Lectures de `notes`.
 *
 * ## Les écritures ne sont pas ici, et c'est le point du fichier
 *
 * Elles vivent toutes dans [NoteWriteDao]. La séparation n'est pas cosmétique : c'est dans les
 * écritures que se joue la protection des coffres, et une surface d'écriture qui tient en un fichier
 * court se vérifie d'un coup d'œil. Mêlee aux seize façons de lire la table, elle se relisait dans
 * trois cents lignes.
 *
 * 🔴 **Il n'existe aucune écriture de ligne entière nulle part** — la raison, et l'incident qui
 * l'a imposée, sont exposés en tête de [NoteWriteDao].
 *
 * ## Ce que les lectures ne divulguent pas
 *
 * Deux requêtes portent une garde de confidentialité plutôt qu'un filtre de confort :
 * [titlesForLinking] et [findByTitleLike] écartent les notes verrouillées. Sans elles, un lien
 * `[[…]]` ou une auto-complétion révélerait le titre d'une note de coffre depuis une note qui, elle,
 * n'est pas protégée.
 */
@Dao
interface NoteDao {
    /**
     * Notes actives d'un dossier : ni archivées, ni à la corbeille.
     *
     * ⚠️ **L'index ne couvre que le filtre, pas le tri.** `idx_notes_folder_active`
     * (`folder_id, archived, trashed_at, updated_at DESC`) sert les trois clauses `WHERE`, mais
     * `pinned` n'en fait pas partie : SQLite trie en mémoire le sous-ensemble retenu.
     *
     * Ça ne se corrige pas ici — ajouter un index modifierait le schéma et ferait échouer la
     * validation de Room sur toutes les bases existantes (`docs/01-DECISIONS.md` D-005). Le coût
     * est un tri sur les notes d'un seul dossier, négligeable aux volumes réels.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE folder_id = :folderId AND archived = 0 AND trashed_at IS NULL
        ORDER BY pinned DESC, updated_at DESC
        """,
    )
    fun observeActiveInFolder(folderId: String): Flow<List<NoteEntity>>

    /**
     * Les notes les plus récemment modifiées, tous dossiers confondus.
     *
     * ⚠️ **Pas de `pinned DESC`**, délibérément : `listRecent` côté Flutter trie sur
     * `updated_at DESC` seul (`notes_dao.dart:154-166`). Ajouter l'épinglage bloquerait en tête de
     * « récentes » une note épinglée ancienne — un écran qui ne dirait plus ce que son titre
     * annonce, et un écart de comportement avec l'application publiée.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE archived = 0 AND trashed_at IS NULL
        ORDER BY updated_at DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE trashed_at IS NOT NULL ORDER BY trashed_at DESC")
    fun observeTrash(): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE favorite = 1 AND archived = 0 AND trashed_at IS NULL
        ORDER BY updated_at DESC
        """,
    )
    fun observeFavorites(): Flow<List<NoteEntity>>

    /**
     * Liste d'un dossier, dans l'ordre demandé.
     *
     * `@RawQuery` parce que la clause `ORDER BY` varie : Room refuse un paramètre à cet endroit, et
     * il a raison — une colonne de tri n'est pas une valeur. La clause vient d'un
     * [NoteSortMode], un ensemble fermé de constantes écrites en toutes lettres ; les identifiants,
     * eux, restent des paramètres liés.
     *
     * `observedEntities` rend le flux réactif malgré le SQL construit : sans cette mention, Room
     * n'a aucun moyen de savoir quelle table observer.
     */
    @RawQuery(observedEntities = [NoteEntity::class])
    fun observeSorted(query: SupportSQLiteQuery): Flow<List<NoteEntity>>

    fun observeInFolder(folderId: String, sort: NoteSortMode, includeArchived: Boolean): Flow<List<NoteEntity>> =
        observeSorted(
            SimpleSQLiteQuery(
                "SELECT * FROM notes WHERE folder_id = ? AND trashed_at IS NULL" +
                    (if (includeArchived) "" else " AND archived = 0") +
                    " ORDER BY ${sort.orderBy}",
                arrayOf<Any?>(folderId),
            ),
        )

    /**
     * Toutes les notes hors corbeille, **archives comprises**.
     *
     * Balayage complet : réservé aux consommateurs qui en ont réellement besoin — statistiques,
     * export, écran d'accueil. Jamais dans une boucle de rendu, et jamais pour construire l'index
     * des rétroliens : [titlesForLinking] existe pour ça et ne charge pas les blobs.
     */
    @Query("SELECT * FROM notes WHERE trashed_at IS NULL ORDER BY updated_at DESC")
    suspend fun listAllAlive(): List<NoteEntity>

    /**
     * Les couples (identifiant, titre) qui peuvent être **cible** d'un lien `[[Titre]]`.
     *
     * ⚠️ **Les notes verrouillées en sont exclues**, et c'est une garantie de confidentialité, pas
     * une optimisation. Résoudre un lien vers une note de coffre inscrirait son identifiant dans
     * `note_links`, d'où une note non protégée afficherait son titre et un lien cliquable vers
     * elle. Même raisonnement que la garde de la recherche plein texte.
     *
     * ⚠️ **L'ordre `updated_at DESC` fait partie du contrat.** Deux notes peuvent porter le même
     * titre normalisé ; la table d'appariement n'en garde qu'une, et l'application publiée garde la
     * **dernière** rencontrée dans cet ordre — donc la moins récemment modifiée
     * (`backlinks_service.dart:324`, où le littéral de map écrase les clés en double). Trier
     * autrement ferait pointer les liens ambigus vers une autre note.
     *
     * Projection sur deux colonnes : cette requête tourne à chaque enregistrement d'une note qui
     * contient des liens. Charger les entités entières y ferait lire tous les blobs chiffrés de la
     * base pour n'en utiliser aucun.
     */
    @Query(
        """
        SELECT id, title FROM notes
        WHERE trashed_at IS NULL AND encrypted_content IS NULL AND title != ''
        ORDER BY updated_at DESC
        """,
    )
    suspend fun titlesForLinking(): List<NoteTitleRef>

    /**
     * Notes d'un dossier dont le contenu est **en clair**, donc lisible au repos si ce dossier est
     * un coffre.
     *
     * Le critère est l'exposition elle-même — `content` non vide — et **non** l'absence de blob :
     * une ligne portant à la fois un chiffré et du clair serait tout aussi lisible, et un test sur
     * « verrouillée » la laisserait passer.
     *
     * ⚠️ **Sans filtre sur la corbeille, volontairement.** Une note de coffre laissée en clair puis
     * jetée y séjourne trente jours — c'est l'endroit où il serait le plus grave de l'oublier.
     *
     * ## ⚠️ Un titre en clair compte, et l'application publiée ne le voyait pas
     *
     * Sa requête ne teste que `content <> ''` (`notes_dao.dart:91`). Une note **sans blob** dont
     * seul le titre est rempli — « Codes de la carte bleue », corps vide — n'était donc réparée par
     * aucune passe : ni celle-ci, ni celle du format 1, qui exige un blob. C'est la limite que son
     * propre code documente comme connue et ouverte (`notes_repository.dart:209`).
     *
     * Ce portage la ferme en reprenant **exactement** le critère de
     * `NotesRepository.carriesPlaintext` :
     *
     * | État | Retenu | Pourquoi |
     * |---|---|---|
     * | pas de blob, titre **ou** contenu non vide | oui | rien ne le protège |
     * | blob, contenu non vide | oui | le contenu est vidé au chiffrement |
     * | blob format 2, titre non vide | oui | le titre a rejoint le blob, la colonne doit être vide |
     * | blob format 1, titre non vide | **non** | état hérité légitime |
     *
     * Relevé par une relecture externe (GPT-5.5, 2026-08-13).
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE folder_id = :folderId
          AND (
            (encrypted_content IS NULL AND (title != '' OR content != ''))
            OR (encrypted_content IS NOT NULL AND content != '')
            OR (encrypted_content IS NOT NULL AND enc_v = 2 AND title != '')
          )
        """,
    )
    suspend fun findPlaintextInFolder(folderId: String): List<NoteEntity>

    /**
     * Notes chiffrées d'un dossier restées au format 1 — celles dont le titre est encore en clair.
     *
     * Sert la migration vers le format 2, qui ne peut se faire qu'à l'ouverture du coffre :
     * déplacer le titre dans le blob exige la clé, dont une migration de schéma ne dispose pas.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE folder_id = :folderId AND encrypted_content IS NOT NULL AND enc_v = 1
        """,
    )
    suspend fun findLegacyEncryptedInFolder(folderId: String): List<NoteEntity>

    /**
     * Pré-filtre d'auto-complétion sur le titre, insensible à la casse.
     *
     * ⚠️ Insensible à la casse **mais pas aux diacritiques** : `LOWER()` de SQLite ne connaît que
     * l'ASCII. L'affinage revient à l'appelant, qui compare des titres normalisés — d'où le
     * sur-échantillonnage recommandé, hérité de l'application publiée.
     *
     * Deux motifs : préfixe de titre, et préfixe de mot à l'intérieur du titre.
     *
     * ⚠️ `encrypted_content IS NULL` est une garde de confidentialité, pas un filtre de confort.
     * Sans elle, l'auto-complétion d'un `[[…]]` révélerait le titre des notes d'un coffre. La
     * placer ici plutôt que chez l'appelant a une seconde raison, apprise par l'application
     * publiée : filtrer après coup laisserait la limite être consommée par des notes verrouillées,
     * et les suggestions visibles s'amincir sans raison apparente.
     *
     * @param pattern déjà échappé pour `ESCAPE '\'` et déjà suffixé de `%` par l'appelant.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE (LOWER(title) LIKE :pattern ESCAPE '\' OR LOWER(title) LIKE :wordPattern ESCAPE '\')
          AND trashed_at IS NULL
          AND encrypted_content IS NULL
          AND (:excludeId IS NULL OR id != :excludeId)
        ORDER BY updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun findByTitleLike(pattern: String, wordPattern: String, limit: Int, excludeId: String?): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes WHERE folder_id = :folderId AND trashed_at IS NULL")
    suspend fun countInFolder(folderId: String): Int

    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<NoteEntity>

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
}

/**
 * Le strict nécessaire pour apparier un lien `[[Titre]]` à sa cible.
 *
 * Deux colonnes, jamais l'entité entière : cette projection est lue à chaque enregistrement d'une
 * note porteuse de liens, et charger les blobs chiffrés de toute la base pour n'en lire aucun
 * serait payer cher un travail inutile — et tenir en mémoire, sans raison, du matériel qu'on
 * cherche par ailleurs à ne pas exposer.
 */
data class NoteTitleRef(@ColumnInfo(name = "id") val id: String, @ColumnInfo(name = "title") val title: String)
