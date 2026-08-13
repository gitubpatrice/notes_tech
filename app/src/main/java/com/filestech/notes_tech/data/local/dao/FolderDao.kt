package com.filestech.notes_tech.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.filestech.notes_tech.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Accès à `folders`.
 *
 * ## ⚠️ Aucune méthode n'utilise `OnConflictStrategy.REPLACE`, et ce n'est pas un oubli
 *
 * `REPLACE` est un `DELETE` suivi d'un `INSERT`. Sur ce schéma, remplacer un dossier
 * **supprimerait en cascade toutes ses notes** (`notes.folder_id` porte `ON DELETE CASCADE`), et
 * chaque note supprimée emporterait ses liens.
 *
 * Le pire est que rien ne se verrait : `PRAGMA recursive_triggers` vaut `OFF` par défaut, donc les
 * triggers de l'index plein texte ne se déclencheraient même pas pour nettoyer ce qui a disparu.
 *
 * Écriture = [insert] **ou** [update], explicitement. Cf. `docs/04-PIEGES.md` §1.
 */
@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parent_id IS :parentId ORDER BY name COLLATE NOCASE ASC")
    fun observeChildren(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun findById(id: String): FolderEntity?

    /** Les dossiers qui sont des coffres, quel que soit leur mode. */
    @Query("SELECT * FROM folders WHERE vault_mode IS NOT NULL")
    suspend fun findVaults(): List<FolderEntity>

    /**
     * `ABORT` et non `REPLACE` : un identifiant déjà pris est une erreur de programmation
     * (les identifiants sont des UUID produits par le domaine), pas un cas à absorber en silence.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    /**
     * Supprime un dossier — **sauf la boîte de réception**, protégée dans la requête elle-même.
     *
     * La garde est en SQL et non dans un `if` côté appelant : une règle qu'il faut se rappeler à
     * chaque point d'appel est un défaut en attente d'un nouvel appelant. Ici, aucun appelant ne
     * peut se tromper.
     *
     * ⚠️ La suppression emporte les notes du dossier par cascade. C'est le comportement hérité, et
     * c'est à l'interface de le faire confirmer.
     *
     * @return le nombre de lignes supprimées : `0` si l'identifiant est inconnu **ou** s'il
     *   s'agissait de la boîte de réception. L'appelant qui a besoin de distinguer les deux doit
     *   le vérifier lui-même.
     */
    @Query("DELETE FROM folders WHERE id = :id AND id != '${FolderEntity.INBOX_ID}'")
    suspend fun delete(id: String): Int

    /** Remet le compteur de tentatives d'un coffre PIN. Utilisé après un déverrouillage réussi. */
    @Query("UPDATE folders SET vault_attempts = 0 WHERE id = :id")
    suspend fun resetVaultAttempts(id: String)

    /**
     * Incrémente le compteur de tentatives et rend sa nouvelle valeur.
     *
     * Incrémentation **en base** plutôt que lecture-modification-écriture côté Kotlin : deux
     * tentatives concurrentes liraient la même valeur et n'en compteraient qu'une, ce qui
     * offrirait des essais gratuits sur un coffre à cinq tentatives.
     */
    @Query("UPDATE folders SET vault_attempts = vault_attempts + 1 WHERE id = :id")
    suspend fun incrementVaultAttempts(id: String)

    @Query("SELECT vault_attempts FROM folders WHERE id = :id")
    suspend fun vaultAttempts(id: String): Int?
}
