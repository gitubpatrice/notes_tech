package com.filestech.notes_tech.domain.links

import com.filestech.notes_tech.core.text.DartTextSemantics

/**
 * Un lien `[[Titre]]` relevé dans le texte d'une note, **avant** toute résolution.
 *
 * La cible n'est pas encore connue : l'appariement d'un titre normalisé vers un identifiant de note
 * se fait en mémoire, pas en SQL — voir
 * [com.filestech.notes_tech.data.local.OutgoingLink] pour la raison.
 *
 * @param title le titre tel qu'écrit, élagué. C'est ce que l'interface affiche.
 * @param titleNorm sa clé d'appariement, calculée par [TitleNormalizer].
 * @param position décalage du `[[` dans le texte, en unités UTF-16, **après** troncature du contenu
 *   à [WikiLinkParser.CONTENT_SCAN_LIMIT].
 */
data class ExtractedLink(val title: String, val titleNorm: String, val position: Int)

/**
 * Relève les liens `[[Titre]]` d'un texte Markdown.
 *
 * Transposition de `BacklinksService.extractFromContent`
 * (`notes_tech/lib/services/backlinks_service.dart:114`). Fonction pure, sans accès à la base :
 * l'interface l'appelle aussi pour surligner les liens pendant la frappe.
 *
 * ## Trois bornes, et ce qu'elles protègent
 *
 * | Borne | Valeur | Ce qui arriverait sans elle |
 * |---|---|---|
 * | Longueur analysée | 50 000 caractères | une note collée de 5 Mo bloquerait la frappe |
 * | Liens par note | 256 | un texte forgé saturerait `note_links` |
 * | Longueur d'un titre | 200 caractères | une paire de crochets non refermée avalerait la note |
 *
 * La troisième est portée par l'expression elle-même (`{1,200}`) et non par un test après coup :
 * au-delà, il n'y a pas de lien tronqué, il n'y a **pas de lien**. C'est le comportement hérité,
 * vérifié — `[[` suivi de 201 caractères ne rend rien.
 */
object WikiLinkParser {

    /** Au-delà, le texte n'est plus analysé. `AppConstants.noteContentBacklinksLimit` côté Dart. */
    const val CONTENT_SCAN_LIMIT = 50_000

    /** Nombre maximal de liens retenus par note. Les suivants sont ignorés en silence. */
    const val MAX_LINKS_PER_NOTE = 256

    /**
     * `[[` … `]]`, sans crochet ni saut de ligne à l'intérieur, de 1 à 200 caractères.
     *
     * L'exclusion de `]` fait tout le travail : elle empêche l'expression d'enjamber une paire mal
     * fermée. `[[a]b]]` ne rend donc **rien** — ni `a]b`, ni `a`. Vérifié contre le vrai code Dart,
     * ce n'est pas une lecture optimiste de l'expression.
     *
     * Elle est **gourmande**, mais bornée par la classe négative : aucun retour arrière coûteux
     * n'est possible, la longueur est plafonnée et le caractère terminal est exclu de la classe.
     *
     * Shared with the Markdown preview (`domain/markdown/WikiLinkMask`), as notes_tech 2.0.9 shares
     * `BacklinksService.linkPatternSource` with its preview: what the preview draws as a link and
     * what the links panel lists must be the same thing, and one pattern is how that stays true.
     */
    val LINK = Regex("""\[\[([^\[\]\n]{1,200})\]\]""")

    /**
     * @return les liens dans l'ordre du texte, **dédoublonnés par clé d'appariement** : deux
     *   écritures d'un même titre — `[[Réunion]]` puis `[[réunion]]` — ne comptent que pour une, et
     *   c'est la **première** qui est retenue, avec sa graphie et sa position.
     */
    fun extract(content: String): List<ExtractedLink> {
        val scanned = if (content.length > CONTENT_SCAN_LIMIT) {
            content.substring(0, CONTENT_SCAN_LIMIT)
        } else {
            content
        }

        return LINK.findAll(scanned)
            .mapNotNull { match ->
                val title = DartTextSemantics.trim(match.groupValues[1])
                val titleNorm = if (title.isEmpty()) "" else TitleNormalizer.normalize(title)
                if (titleNorm.isEmpty()) {
                    null
                } else {
                    ExtractedLink(title = title, titleNorm = titleNorm, position = match.range.first)
                }
            }
            // `distinctBy` garde la PREMIÈRE occurrence, et `take` s'applique après : le plafond
            // compte donc les liens retenus, pas les paires de crochets rencontrées. C'est l'ordre
            // de l'application publiée, où le compteur n'avance que sur un lien accepté.
            .distinctBy(ExtractedLink::titleNorm)
            .take(MAX_LINKS_PER_NOTE)
            .toList()
    }
}
