package com.filestech.notes_tech.domain.model

import java.time.Instant

/**
 * Une note, telle que le domaine la manipule.
 *
 * Distincte de `NoteEntity`, qui décalque la table : celle-ci porte des types utiles
 * ([Instant] plutôt que des millisecondes, une liste d'étiquettes plutôt qu'une chaîne à virgules)
 * et surtout une **enveloppe** autour du chiffré, pour les deux raisons ci-dessous.
 *
 * ## Elle ne dit pas son contenu quand on l'imprime
 *
 * Une `data class` produit un `toString` qui recopie tous ses champs. Sur ce type-là, cela signifie
 * le texte intégral de la note de l'utilisateur dans la moindre trace, le moindre message
 * d'exception, le moindre `require(...)` malheureux.
 *
 * L'objet qu'on voudrait le moins voir passer par là est justement le plus exposé : **l'éphémère
 * déchiffrée d'une note de coffre**, que l'éditeur détient contenu en clair et blob à `null`. C'est
 * déjà cette instance-là qui avait détruit une protection en production sur un tap d'épinglage
 * (`docs/04-PIEGES.md` §1bis) ; qu'elle sache au moins se taire.
 *
 * [toString] est donc redéfini. Il rend de quoi diagnostiquer — identifiant, dossier, tailles — et
 * rien de ce que l'utilisateur a écrit.
 */
data class Note(
    val id: String,
    /** Vide quand la note est verrouillée en format [EncryptedFormat.TITLE_AND_CONTENT]. */
    val title: String,
    /** Markdown en clair. Vide quand la note est verrouillée. */
    val content: String,
    val folderId: String,
    val tags: List<String>,
    val pinned: Boolean,
    val favorite: Boolean,
    val archived: Boolean,
    val trashedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Non-`null` ⇒ note verrouillée dans un coffre. */
    val encrypted: EncryptedBody?,
    val encVersion: Int,
    /**
     * The background colour in the lists (3.1.0), `null` for none. Stored in clear, like [tags], even
     * for a vault note: the lists show it on a vault note only while its vault is open — see
     * `ui/home/CouleurVisible.kt`.
     */
    val color: NoteColor? = null,
) {
    /**
     * `true` si la note est verrouillée dans un coffre.
     *
     * ⚠️ **Seul test valable.** `content.isEmpty()` est aussi vrai d'une note vide ordinaire, et
     * s'en servir ferait traiter une note banale comme un secret — ou l'inverse.
     */
    val isLocked: Boolean get() = encrypted != null

    val isTrashed: Boolean get() = trashedAt != null

    override fun toString(): String = buildString {
        append("Note(id=").append(id)
        append(", dossier=").append(folderId)
        append(", verrouillee=").append(isLocked)
        append(", titre=").append(title.length).append(" car.")
        append(", contenu=").append(content.length).append(" car.")
        append(", etiquettes=").append(tags.size)
        append(')')
    }
}

/**
 * Le blob chiffré d'une note : `nonce (12) ‖ ciphertext ‖ tag GCM (16)`.
 *
 * ## Pourquoi un type plutôt qu'un `ByteArray` nu
 *
 * Trois défauts que ce type rend impossibles, dans l'ordre de gravité :
 *
 * 1. **`equals` par référence.** Une `data class` portant un `ByteArray` compare les tableaux par
 *    identité : deux notes aux octets identiques se déclarent différentes. Un test qui compare deux
 *    `Note` passerait pour de mauvaises raisons, ou échouerait sans raison.
 * 2. **Tableau partagé.** Un `ByteArray` est muable : le rendre tel quel donne à l'appelant de quoi
 *    modifier l'état interne de la note. Les copies défensives coûtent quelques microsecondes sur
 *    des blobs de quelques kilo-octets.
 * 3. **Chiffré dans les traces.** [toString] n'en donne que la taille.
 *
 * Le contenu reste du **chiffré** : ce type ne protège pas un secret en clair, il évite trois
 * erreurs de manipulation ordinaires.
 */
class EncryptedBody(bytes: ByteArray) {

    private val bytes: ByteArray = bytes.copyOf()

    val size: Int get() = bytes.size

    /** Rend une copie. Le tableau interne n'est jamais exposé. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is EncryptedBody && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "EncryptedBody(${bytes.size} octets)"
}

/**
 * Formats successifs du blob chiffré.
 *
 * Ces valeurs sont écrites dans la colonne `enc_v` de la base héritée : elles ne peuvent pas
 * changer. Elles vivent dans le domaine et non à côté de l'entité parce que c'est une règle métier
 * — quelle partie de la note part dans le chiffré — et non un détail de stockage. La couche données
 * s'y réfère ; l'inverse ferait dépendre le domaine de la table.
 */
object EncryptedFormat {
    /** Contenu seul ; le titre reste en clair dans la colonne `title`. */
    const val CONTENT_ONLY = 1

    /** Titre **et** contenu dans le blob ; la colonne `title` est vidée. Introduit en 2.0.0. */
    const val TITLE_AND_CONTENT = 2
}
