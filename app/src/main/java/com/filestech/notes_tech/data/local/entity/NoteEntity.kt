package com.filestech.notes_tech.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Table `notes` de la base héritée.
 *
 * ⚠️ Décalque exact — voir l'avertissement en tête de [FolderEntity].
 *
 * ## Le `rowid` est porteur, et invisible ici
 *
 * `notes_fts` est une table FTS5 **à contenu externe** indexée sur `notes.rowid`
 * (`content='notes'`, `content_rowid='rowid'`). Le `rowid` n'apparaît dans aucun champ de cette
 * classe, mais tout ce qui le change casse l'index — et déclenche au passage la cascade
 * `ON DELETE CASCADE` de `note_links`.
 *
 * C'est pourquoi **aucun DAO n'utilise `INSERT OR REPLACE`** : `REPLACE` est un `DELETE` suivi
 * d'un `INSERT`, donc un nouveau `rowid`, donc des backlinks supprimés et un index qui pointe dans
 * le vide. Détail complet dans `docs/04-PIEGES.md` §1.
 */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Index composite couvrant pour le listage par dossier (filtre + tri).
        // ⚠️ `orders` n'est pas décoratif : la colonne de tri est DESC dans la base. L'omettre
        // produirait un index différent et ferait échouer la validation de schéma.
        Index(
            name = "idx_notes_folder_active",
            value = ["folder_id", "archived", "trashed_at", "updated_at"],
            orders = [
                Index.Order.ASC,
                Index.Order.ASC,
                Index.Order.ASC,
                Index.Order.DESC,
            ],
        ),
        Index(name = "idx_notes_trashed", value = ["trashed_at"]),
        Index(name = "idx_notes_updated", value = ["updated_at"]),
    ],
)
class NoteEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /**
     * Titre en clair. **Vide** quand la note est verrouillée avec [encVersion] == 2 : le titre est
     * alors dans [encryptedContent].
     */
    @ColumnInfo(name = "title")
    val title: String,

    /** Contenu Markdown en clair. **Vide** quand la note est verrouillée. */
    @ColumnInfo(name = "content")
    val content: String,

    /**
     * Non-`null` ⇒ **note verrouillée dans un coffre**.
     *
     * Enveloppe : `nonce (12) ‖ ciphertext ‖ tag GCM (16)`, chiffrée AES-256-GCM avec la
     * `folder_kek` du dossier. Le format du clair dépend de [encVersion].
     */
    @ColumnInfo(name = "encrypted_content", typeAffinity = ColumnInfo.BLOB)
    val encryptedContent: ByteArray?,

    @ColumnInfo(name = "folder_id")
    val folderId: String,

    /** Étiquettes sérialisées. Chaîne vide = aucune étiquette. */
    @ColumnInfo(name = "tags", defaultValue = "''")
    val tags: String,

    @ColumnInfo(name = "pinned", defaultValue = "0")
    val pinned: Boolean,

    @ColumnInfo(name = "favorite", defaultValue = "0")
    val favorite: Boolean,

    @ColumnInfo(name = "archived", defaultValue = "0")
    val archived: Boolean,

    /** Instant de mise à la corbeille, ou `null` si la note est active. Rétention : 30 jours. */
    @ColumnInfo(name = "trashed_at")
    val trashedAt: Long?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /**
     * Version du format de [encryptedContent].
     * Voir [com.filestech.notes_tech.domain.model.EncryptedFormat].
     *
     * Colonne de schéma plutôt qu'un marqueur deviné dans le blob, et la raison est bonne : un
     * préfixe de version à l'intérieur du chiffré serait ambigu avec un nonce commençant par la
     * même valeur, et un contenu en clair peut toujours imiter n'importe quelle enveloppe.
     */
    @ColumnInfo(name = "enc_v", defaultValue = "1")
    val encVersion: Int,

    /**
     * [com.filestech.notes_tech.domain.model.NoteColor.id], or `null` for none (3.1.0, schema 10 —
     * the one column that was never in the Flutter base; see `NotesDatabase.MIGRATION_9_10`).
     *
     * ⚠️ Outside the vault's envelope, like [tags]: the whole database is encrypted by SQLCipher, but a
     * vault's own key does not cover it. The lists show it on a vault note only while the vault is
     * open; the privacy policy says it.
     *
     * No default: a row rebuilt without its colour would write NULL — an erased colour. The compiler
     * makes every constructor say it (Claude review, 2026-10-10; today only the insert builds one).
     */
    @ColumnInfo(name = "color_id")
    val colorId: Int?,
) {
    /** `true` si la note est verrouillée dans un coffre. Unique test à utiliser — jamais
     *  `content.isEmpty()`, qui est aussi vrai d'une note vide ordinaire. */
    val isLocked: Boolean get() = encryptedContent != null
}
