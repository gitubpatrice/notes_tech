package com.filestech.notes_tech.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
 * Écriture = [insert], ou l'une des écritures ciblées plus bas. Cf. `docs/04-PIEGES.md` §1.
 */
@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parent_id IS :parentId ORDER BY name COLLATE NOCASE ASC")
    fun observeChildren(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun findById(id: String): FolderEntity?

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    suspend fun listAll(): List<FolderEntity>

    /**
     * Les dossiers qui sont des coffres, quel que soit leur mode.
     *
     * ⚠️ **Le critère est `vault_salt`, et cette requête disait d'abord `vault_mode IS NOT NULL`.**
     * C'était une erreur de ce portage, relevée en écrivant la couche domaine : `vault_mode`
     * n'existe que depuis la 0.9 et a été **rétro-remplie** par une migration pour les coffres déjà
     * créés. Le sel, lui, est présent depuis le premier coffre — c'est la source de vérité retenue
     * par l'application publiée (`folder.dart:100`).
     *
     * L'écart aurait été indétectable en pratique, la migration ayant bien fait son travail. Ce
     * n'est pas une raison de faire dépendre l'identification d'un coffre du bon déroulement passé
     * d'une migration.
     */
    @Query("SELECT * FROM folders WHERE vault_salt IS NOT NULL")
    suspend fun findVaults(): List<FolderEntity>

    /**
     * Dit si [id] désigne un coffre, sans charger la ligne.
     *
     * 🔴 **Consulté à chaque écriture de note, pour décider si le clair a le droit de partir en
     * base.** Le résultat n'est ni mémorisé ni mis en cache : l'appelant l'interroge **dans la
     * transaction** qui écrit, donc la réponse ne peut pas être périmée.
     *
     * Ce détail vaut d'être expliqué, parce que l'application publiée a dû s'y reprendre à trois
     * fois. Elle mémorise ce prédicat pour ne pas payer une requête par frappe, et deux relectures
     * externes successives y ont trouvé des courses où le cache répondait « pas un coffre » pour un
     * coffre qui venait d'être créé — c'est-à-dire précisément ce que la garde existe pour empêcher.
     * Le correctif final tient sur un compteur de génération et trois tours de boucle.
     *
     * Rien de tout cela n'est nécessaire ici. La transaction rend la lecture et l'écriture
     * atomiques ; il n'y a pas d'intervalle pendant lequel la réponse pourrait vieillir.
     *
     * @return `null` si le dossier n'existe pas. L'appelant doit traiter ce cas comme un coffre —
     *   voir `FoldersRepository.isVaultFolder`.
     */
    @Query("SELECT vault_salt IS NOT NULL FROM folders WHERE id = :id")
    suspend fun isVault(id: String): Boolean?

    /**
     * `ABORT` et non `REPLACE` : un identifiant déjà pris est une erreur de programmation
     * (les identifiants sont des UUID produits par le domaine), pas un cas à absorber en silence.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity)

    // ── Écritures ciblées ────────────────────────────────────────────────────
    //
    // 🔴 Il n'existe AUCUNE écriture de ligne entière, pour la même raison que dans `NoteDao` — et
    // ici la conséquence serait pire.
    //
    // L'application publiée renomme un dossier par un `UPDATE` complet construit depuis un objet
    // `Folder` en mémoire (`folders_dao.dart:66`), et cet objet porte les sept colonnes de coffre.
    // Renommer un coffre depuis une instance incomplète ou périmée y écraserait `vault_kek_wrapped`
    // par `NULL`.
    //
    // Une note dont on efface le blob est perdue. Un coffre dont on efface la clé enveloppée
    // emporte TOUTES ses notes, et aucune saisie de la bonne phrase secrète ne les rendra : le
    // matériel qui permettait de les déchiffrer n'existe plus. Rien ne le signalerait avant la
    // prochaine ouverture du coffre.
    //
    // Le geste n'est donc pas exprimable. Les colonnes de coffre ne s'écrivent que par les méthodes
    // de provisionnement, qui les nomment (phase 4).

    /** Renomme. Ne touche à rien d'autre — surtout pas aux colonnes de coffre. */
    @Query("UPDATE folders SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long): Int

    /** Couleur et icône. `null` remet la valeur par défaut du thème, ce qui est un état légitime. */
    @Query("UPDATE folders SET color = :color, icon = :icon, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateAppearance(id: String, color: Int?, icon: String?, updatedAt: Long): Int

    /**
     * Déplace un dossier sous un autre parent. `null` le remonte à la racine.
     *
     * ⚠️ **Ne vérifie pas les cycles.** La base n'a rien pour l'empêcher, et un dossier devenu son
     * propre ancêtre disparaîtrait de l'arborescence avec tout son contenu. C'est au repository de
     * le refuser — voir `FoldersRepository.move`.
     */
    @Query("UPDATE folders SET parent_id = :parentId, updated_at = :updatedAt WHERE id = :id")
    suspend fun move(id: String, parentId: String?, updatedAt: Long): Int

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
