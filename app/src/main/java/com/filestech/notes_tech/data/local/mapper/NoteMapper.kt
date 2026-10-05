package com.filestech.notes_tech.data.local.mapper

import com.filestech.notes_tech.data.local.entity.NoteEntity
import com.filestech.notes_tech.domain.model.EncryptedBody
import com.filestech.notes_tech.domain.model.Note
import java.time.Instant

/**
 * Conversions entre la ligne `notes` et le modèle de domaine.
 *
 * ## ⚠️ Il n'existe volontairement pas de `Note.toEntity()`
 *
 * Une telle fonction n'aurait qu'un seul usage possible : réécrire la ligne entière. Or c'est
 * exactement le geste qui a détruit la protection d'une note de coffre dans l'application publiée,
 * et que `NoteDao` interdit en ne l'exposant pas (`docs/04-PIEGES.md` §1bis).
 *
 * Fournir la conversion « juste pour l'insertion » remettrait l'outil à portée de main du prochain
 * appelant, qui n'aurait aucune raison de se méfier d'un nom aussi anodin. L'insertion construit
 * donc son entité explicitement, dans le repository, à un endroit et un seul.
 */
internal fun NoteEntity.toDomain(): Note = Note(
    id = id,
    title = title,
    content = content,
    folderId = folderId,
    tags = TagCodec.decode(tags),
    pinned = pinned,
    favorite = favorite,
    archived = archived,
    trashedAt = trashedAt?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    encrypted = encryptedContent?.let(::EncryptedBody),
    encVersion = encVersion,
)

internal fun List<NoteEntity>.toDomain(): List<Note> = map(NoteEntity::toDomain)

/**
 * Le codage des étiquettes dans la colonne `tags` : une seule chaîne, virgules en séparateur.
 *
 * ## ⚠️ Ce codage est cassé, et il faut le reproduire tel quel
 *
 * Une étiquette contenant une virgule est coupée en deux à la relecture. Ni échappement, ni
 * guillemets : `notes_tech/lib/data/models/note.dart:136` écrit `tags.join(',')` et
 * `note.dart:151` relit par `split(',')`.
 *
 * Le réparer ici produirait des lignes que l'application publiée relirait de travers — et les deux
 * versions lisent la même base pendant toute la durée du chantier. Le défaut est donc **conservé,
 * documenté, et confié à l'interface** : c'est à la saisie d'interdire la virgule, pas au stockage
 * de la rattraper après coup.
 *
 * La réparation deviendra possible le jour où la version Flutter ne sera plus en service, et
 * demandera une migration de schéma — pas un changement de ce fichier.
 */
internal object TagCodec {

    private const val SEPARATOR = ","

    /** Les étiquettes vides sont écartées, comme côté Dart : `''` rend une liste vide. */
    fun decode(stored: String): List<String> =
        if (stored.isEmpty()) emptyList() else stored.split(SEPARATOR).filter(String::isNotEmpty)

    fun encode(tags: List<String>): String = tags.joinToString(SEPARATOR)
}
