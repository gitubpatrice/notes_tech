package com.filestech.notes_tech.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Table `folders` de la base héritée.
 *
 * ⚠️ **Décalque exact du schéma écrit par la version Flutter.** Toute divergence — type,
 * nullabilité, valeur par défaut, nom d'index, ordre de tri, action de clé étrangère — fait
 * échouer l'ouverture chez l'utilisateur, parce que Room valide le schéma trouvé contre celui
 * qu'il attend (cf. `docs/01-DECISIONS.md` D-005).
 *
 * Cette validation est le **bénéfice** de la conception, pas une contrainte subie : c'est la preuve
 * vérifiée par la machine que cette classe décrit la base réelle. Référence du schéma :
 * `docs/02-SCHEMA-HERITE.md` §3.
 *
 * ## Pourquoi ce n'est pas une `data class`
 *
 * Les colonnes `vault_*` sont des `BLOB`, donc des `ByteArray`. L'`equals` généré par une
 * `data class` compare les tableaux **par référence** : deux entités portant les mêmes octets
 * seraient déclarées différentes. Plutôt qu'un avertissement à retenir, on retire la faute :
 * pas de `data class`, donc pas d'`equals` faux à mal utiliser. Les comparaisons et les copies se
 * font sur le modèle de domaine, pas ici.
 */
@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(name = "idx_folders_parent", value = ["parent_id"])],
)
class FolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** `null` = dossier racine. La clé étrangère est en `SET NULL` : supprimer un parent ne
     *  supprime pas ses enfants, il les remonte à la racine. */
    @ColumnInfo(name = "parent_id")
    val parentId: String?,

    /** Couleur ARGB empaquetée, ou `null` pour la couleur par défaut du thème. */
    @ColumnInfo(name = "color")
    val color: Int?,

    @ColumnInfo(name = "icon")
    val icon: String?,

    /** Millisecondes depuis l'époque Unix — convention de toute la base. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    // ── Colonnes de coffre ───────────────────────────────────────────────────
    // Toutes nulles ⇒ ce dossier n'est pas un coffre. Elles ne sont pas regroupées dans un
    // `@Embedded` nullable parce que `vault_attempts` est NOT NULL : Room ne saurait pas
    // distinguer « pas de coffre » de « coffre avec zéro tentative ».

    /** Sel Argon2id, 16 octets. Cf. `docs/02-SCHEMA-HERITE.md` §4. */
    @ColumnInfo(name = "vault_salt", typeAffinity = ColumnInfo.BLOB)
    val vaultSalt: ByteArray?,

    /** `folder_kek` chiffrée par la clé dérivée de la passphrase. */
    @ColumnInfo(name = "vault_kek_wrapped", typeAffinity = ColumnInfo.BLOB)
    val vaultKekWrapped: ByteArray?,

    @ColumnInfo(name = "vault_iv", typeAffinity = ColumnInfo.BLOB)
    val vaultIv: ByteArray?,

    /** Permet de vérifier une passphrase sans déchiffrer une note. */
    @ColumnInfo(name = "vault_verifier", typeAffinity = ColumnInfo.BLOB)
    val vaultVerifier: ByteArray?,

    /**
     * `'passphrase'`, `'pin'`, ou `null`. Lecture typée par
     * [com.filestech.notes_tech.domain.model.VaultMode].
     *
     * ⚠️ **Ne dit pas si le dossier est un coffre** — c'est `vault_salt` qui le dit.
     */
    @ColumnInfo(name = "vault_mode")
    val vaultMode: String?,

    /** Mode PIN : `folder_kek` scellée par une clé Keystore liée à l'appareil. */
    @ColumnInfo(name = "vault_pin_blob", typeAffinity = ColumnInfo.BLOB)
    val vaultPinBlob: ByteArray?,

    @ColumnInfo(name = "vault_pin_iv", typeAffinity = ColumnInfo.BLOB)
    val vaultPinIv: ByteArray?,

    /** Tentatives PIN échouées. Auto-effacement du coffre à 5. */
    @ColumnInfo(name = "vault_attempts", defaultValue = "0")
    val vaultAttempts: Int,
) {
    companion object {
        /**
         * Identifiant du dossier racine indélébile, créé au premier démarrage et recréé à chaque
         * ouverture par un `INSERT OR IGNORE`. Les notes orphelines y sont réassignées.
         *
         * ⚠️ Son libellé d'origine, `Boîte de réception`, est écrit **en dur dans la base** par la
         * version Flutter (`database.dart:869`). Ce n'est pas une chaîne localisée : l'affichage
         * doit substituer la traduction au moment du rendu, sans réécrire la ligne — un utilisateur
         * a pu renommer ce dossier.
         */
        const val INBOX_ID = "inbox"
    }
}
