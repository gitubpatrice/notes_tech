package com.filestech.notes_tech.domain.model

import com.filestech.notes_tech.core.text.DartTextSemantics
import com.filestech.notes_tech.core.text.WHITESPACE_CLASS

/**
 * L'extrait affiché sous le titre d'une note dans une liste.
 *
 * Portage de `data/models/note.dart:198-216`. Les cinq expressions rationnelles sont **reprises au
 * caractère près**, dans le même ordre : les appliquer dans un autre ordre change le résultat (par
 * exemple, retirer l'emphase avant les liens laisserait des crochets orphelins).
 *
 * ## ⚠️ Deux divergences Dart/Java qui ne se voient pas à la relecture
 *
 * 1. **`\s`** ne couvre pas les mêmes caractères dans les deux langages. La version Dart utilise son
 *    `\s` à elle ; ici, [DartTextSemantics.WHITESPACE] le reproduit. Écrire `Regex("\\s+")` aurait
 *    produit un extrait différent sur les notes contenant une espace insécable — c'est-à-dire sur
 *    du texte français ordinaire, devant un point-virgule ou un point d'exclamation.
 * 2. **`trim()`** ne retire pas le même jeu de caractères. Même remède, même raison.
 *
 * ## 🔴 Une note verrouillée n'a pas d'extrait
 *
 * Le contenu d'une note de coffre est chiffré : `content` est vide au repos, et la garde ci-dessous
 * est donc redondante **aujourd'hui**. Elle reste parce que la redondance n'est pas la même chose
 * que l'inutilité : le jour où une session ouverte déchiffre en mémoire, le premier endroit qui
 * afficherait le clair sans y penser serait une liste.
 */
object NoteExcerpt {

    private const val MAX_LENGTH = 200

    // ⚠️ `$WHITESPACE_CLASS` et non `\s` — cf. la note 1 du KDoc ci-dessus. Un titre de section
    // écrit « #<espace insécable>Titre » n'est pas une curiosité de laboratoire : c'est ce que
    // produit un correcteur automatique français.
    private val HEADER = Regex("""^#{1,6}$WHITESPACE_CLASS+""", RegexOption.MULTILINE)
    private val CODE = Regex("""`{1,3}[^`]*`{1,3}""")
    private val LINK = Regex("""\[([^\]]+)\]\([^)]+\)""")
    private val EMPHASIS = Regex("""[*_~>]""")

    fun of(note: Note): String = if (note.isLocked) "" else compute(note.content)

    fun compute(markdown: String): String {
        if (markdown.isEmpty()) return ""
        val stripped = DartTextSemantics.trim(
            DartTextSemantics.WHITESPACE.replace(
                markdown
                    .replace(HEADER, "")
                    .replace(CODE, "")
                    .replace(LINK) { it.groupValues[1] }
                    .replace(EMPHASIS, ""),
                " ",
            ),
        )
        return if (stripped.length > MAX_LENGTH) stripped.substring(0, MAX_LENGTH) else stripped
    }
}
