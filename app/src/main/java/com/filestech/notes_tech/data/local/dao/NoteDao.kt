package com.filestech.notes_tech.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.notes_tech.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Accès à `notes`.
 *
 * ## 🔴 Il n'existe AUCUNE écriture de ligne entière — c'est le point le plus important du fichier
 *
 * Une écriture générique `update(note)` réécrit toutes les colonnes depuis l'objet fourni,
 * `content` **et** `encrypted_content` compris. C'est un chemin par lequel une note de coffre perd
 * sa protection, définitivement, sans le moindre signal.
 *
 * Ce n'est pas une crainte théorique : **l'incident a déjà eu lieu dans l'application publiée**, et
 * le code Flutter le documente (`notes_tech/lib/data/db/notes_dao.dart:234-241`) —
 *
 * > *« l'éditeur détient l'éphémère DÉCHIFFRÉE d'une note de coffre (`content` rempli,
 * > `encryptedContent == null`) : épingler une telle note réécrivait son contenu en clair et
 * > effaçait son blob chiffré — la note perdait sa protection définitivement, sans le moindre
 * > signal, sur un tap d'icône. »*
 *
 * La version Flutter a ajouté des écritures ciblées **et gardé** l'écriture générique, sous un
 * avertissement. Ce portage va plus loin : l'écriture générique **n'existe pas**. Un avertissement
 * qu'il faut se rappeler à chaque appel est un défaut en attente d'un nouvel appelant — celui-là a
 * déjà été oublié une fois.
 *
 * Chaque écriture ci-dessous touche un groupe de colonnes **et un seul**, et
 * [updateEditableFields] porte en plus une garde SQL qui la rend inopérante sur une note
 * verrouillée. L'invariant est tenu par la base, pas par la mémoire du prochain lecteur.
 *
 * ## ⚠️ Aucune méthode n'utilise `OnConflictStrategy.REPLACE`
 *
 * `REPLACE` est un `DELETE` suivi d'un `INSERT` : nouveau `rowid`, donc index FTS5 désynchronisé
 * **et** backlinks supprimés par cascade. Le trigger `notes_ad` ne rattrape rien,
 * `PRAGMA recursive_triggers` valant `OFF`. Cf. `docs/04-PIEGES.md` §1.
 *
 * ## Les écritures rendent le nombre de lignes touchées
 *
 * `0` signifie « aucune note ne porte cet identifiant » — ou, pour [updateEditableFields], « la
 * note est verrouillée ». La version Flutter lève une `NoteNotFoundException` dans ce cas ; ici le
 * DAO reste muet et c'est au repository de trancher, un DAO qui lève sur identifiant inconnu
 * rendant malcommode le cas nominal d'une suppression concurrente.
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

    /**
     * `ABORT` et non `REPLACE` : un identifiant déjà pris est une erreur de programmation (les
     * identifiants sont des UUID produits par le domaine), pas un cas à absorber en silence.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity)

    // ── Écritures ciblées ────────────────────────────────────────────────────

    /**
     * Écrit ce que l'éditeur modifie : titre, contenu, étiquettes.
     *
     * 🔴 **`AND encrypted_content IS NULL` est la garde qui remplace l'avertissement.** Sur une
     * note verrouillée, cette requête ne touche **rien** et rend `0`. Il devient impossible
     * d'écrire en clair le contenu d'une note de coffre, quel que soit l'objet que l'appelant a en
     * main — y compris l'éphémère déchiffrée que détient l'éditeur.
     *
     * Le chemin légitime pour une note de coffre est [replaceContentPayload], seul à écrire
     * `encrypted_content`, et qui porte ce nom pour qu'on ne s'y trompe pas.
     *
     * @return `0` si l'identifiant est inconnu **ou** si la note est verrouillée.
     */
    @Query(
        """
        UPDATE notes
        SET title = :title, content = :content, tags = :tags, updated_at = :updatedAt
        WHERE id = :id AND encrypted_content IS NULL
        """,
    )
    suspend fun updateEditableFields(id: String, title: String, content: String, tags: String, updatedAt: Long): Int

    /**
     * Écrit les seuls drapeaux de métadonnées.
     *
     * `COALESCE(:x, x)` laisse inchangé tout drapeau passé à `null` : l'appelant qui n'épingle que
     * la note n'a pas à connaître l'état des deux autres, donc ne peut pas les écraser par
     * inadvertance avec une valeur périmée.
     *
     * Ne touche **ni** au contenu, **ni** au blob chiffré. C'est précisément le geste qui avait
     * détruit la protection d'une note de coffre dans l'application publiée.
     */
    @Query(
        """
        UPDATE notes SET
          updated_at = :updatedAt,
          pinned = COALESCE(:pinned, pinned),
          favorite = COALESCE(:favorite, favorite),
          archived = COALESCE(:archived, archived)
        WHERE id = :id
        """,
    )
    suspend fun updateFlags(
        id: String,
        updatedAt: Long,
        pinned: Boolean? = null,
        favorite: Boolean? = null,
        archived: Boolean? = null,
    ): Int

    /**
     * Réécrit le couple (`content`, `encrypted_content`) — **le seul chemin qui écrit le blob**.
     *
     * ⚠️ **Ne touche pas à `updated_at`**, délibérément. Cette écriture sert au verrouillage, au
     * déverrouillage et aux réparations d'arrière-plan : remettre `updated_at` à maintenant ferait
     * remonter toutes les notes reprotégées en tête de « modifiées récemment » à chaque ouverture
     * du coffre. Une réparation silencieuse qui réordonne l'écran n'est pas silencieuse.
     *
     * `title` et `encVersion` sont facultatifs et restent inchangés à `null`. Quand ils sont
     * fournis, ils sont écrits dans **le même `UPDATE`** que le blob : à partir du format 2 le
     * titre vit dans le chiffré, et deux écritures séparées laisseraient, en cas d'interruption, un
     * titre en clair face à un blob qui le contient déjà.
     */
    @Query(
        """
        UPDATE notes SET
          content = :content,
          encrypted_content = :encryptedContent,
          title = COALESCE(:title, title),
          enc_v = COALESCE(:encVersion, enc_v)
        WHERE id = :id
        """,
    )
    suspend fun replaceContentPayload(
        id: String,
        content: String,
        encryptedContent: ByteArray?,
        title: String? = null,
        encVersion: Int? = null,
    ): Int

    /**
     * Met ou retire l'horodatage de corbeille, sans toucher au contenu.
     *
     * Même raison que [updateFlags] : mettre à la corbeille une note de coffre ouverte, par une
     * écriture de ligne entière, la déchiffrait au repos.
     *
     * `trashedAt` à `null` restaure la note.
     */
    @Query("UPDATE notes SET trashed_at = :trashedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setTrashedAt(id: String, updatedAt: Long, trashedAt: Long?): Int

    /** Déplace une note vers un autre dossier, sans toucher au contenu. */
    @Query("UPDATE notes SET folder_id = :folderId, updated_at = :updatedAt WHERE id = :id")
    suspend fun moveToFolder(id: String, folderId: String, updatedAt: Long): Int

    // ── Suppressions ─────────────────────────────────────────────────────────

    /**
     * Suppression définitive.
     *
     * Le trigger `notes_ad` retire l'entrée de l'index plein texte, et la cascade de
     * `note_links.source_id` retire les liens partant de cette note. Les liens qui **pointaient**
     * vers elle passent à `target_id = NULL` (`ON DELETE SET NULL`) et redeviennent fantômes —
     * comportement hérité, voulu : le texte `[[titre]]` reste écrit dans les notes sources.
     */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deletePermanently(id: String): Int

    /**
     * Purge les notes en corbeille depuis avant [cutoff]. Rétention héritée : 30 jours.
     *
     * @return le nombre de notes réellement supprimées, pour que l'appelant puisse le rapporter au
     *   lieu de l'affirmer.
     */
    @Query("DELETE FROM notes WHERE trashed_at IS NOT NULL AND trashed_at < :cutoff")
    suspend fun purgeTrashedBefore(cutoff: Long): Int

    /**
     * Réassigne les notes d'un dossier vers un autre.
     *
     * Sert à vider un dossier avant sa suppression quand l'utilisateur choisit de garder ses
     * notes — sans quoi la cascade `ON DELETE CASCADE` les emporterait.
     */
    @Query("UPDATE notes SET folder_id = :destinationId WHERE folder_id = :sourceId")
    suspend fun reassignFolder(sourceId: String, destinationId: String): Int
}
