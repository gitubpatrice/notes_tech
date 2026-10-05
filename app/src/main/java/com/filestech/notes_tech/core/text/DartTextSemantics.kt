package com.filestech.notes_tech.core.text

/**
 * Les règles de texte de Dart, reproduites exactement.
 *
 * ## Pourquoi ce fichier existe
 *
 * Trois opérations de chaînes — découper sur les espaces, élaguer les bords, passer en minuscules —
 * ont l'air identiques dans les deux langages et ne le sont pas. Elles servent ici à calculer des
 * **clés d'appariement** : l'expression `MATCH` de la recherche, et surtout `target_title_norm`, la
 * colonne par laquelle un lien `[[Titre]]` retrouve sa note cible.
 *
 * Les deux applications lisent et écrivent la **même base**. Une clé calculée différemment de part
 * et d'autre ne produit pas une erreur : elle produit des liens qui ne se retrouvent plus, et une
 * recherche qui ne rend pas les mêmes résultats. Rien ne le signale.
 *
 * ## Les trois écarts, mesurés et non supposés
 *
 * Les valeurs ci-dessous ont été relevées en exécutant le vrai code Flutter (`flutter test` sur
 * `notes_tech`, 2026-08-13), pas déduites d'une lecture des spécifications. Les vecteurs qui en
 * sont sortis sont rejoués tels quels par `DartTextSemanticsTest`.
 *
 * | Opération | Java / Kotlin | Dart | Conséquence si l'on ne fait rien |
 * |---|---|---|---|
 * | `\s` | ASCII seul | espaces Unicode | deux mots séparés par U+202F restent **un seul terme** |
 * | `trim()` | `Char.isWhitespace` | White_Space ∪ U+FEFF | U+001F élagué à tort, U+0085 gardé à tort |
 * | `lowercase()` | casse **complète** | casse **simple** | `İ` devient deux caractères, `Σ` final devient `ς` |
 *
 * Le troisième est le plus surprenant, donc celui qu'on aurait raté : `"ΟΔΟΣ".lowercase()` rend
 * `οδος` en Java — la règle du sigma final — là où Dart rend `οδοσ`. Et `"İ".lowercase()` rend deux
 * caractères en Java (`i` suivi de U+0307) contre un seul en Dart. Aucune de ces deux règles
 * n'existe dans la version publiée : les appliquer serait une divergence, même si l'orthographe y
 * gagne.
 *
 * ## Les points de code sont écrits en hexadécimal, jamais en littéral
 *
 * Un fichier dont l'objet est la distinction entre U+00A0 et U+202F ne peut pas se permettre des
 * caractères invisibles dans sa propre source. Ils survivraient mal à un copier-coller, à un
 * changement d'encodage ou à une relecture.
 */
/**
 * La **classe de caractères** que `\s` recouvre dans une expression rationnelle Dart.
 *
 * Extraite de [DartTextSemantics.WHITESPACE] le 2026-08-14, parce qu'un second usage est apparu :
 * l'extrait de note (`NoteExcerpt`) porte un `^#{1,6}\s+` qui doit reconnaître exactement le même
 * ensemble. Recopier la classe aurait produit deux définitions du même `\s`, dont une aurait pu
 * être corrigée sans l'autre — le motif que `docs/04-PIEGES.md` §25 décrit.
 *
 * Hors de l'objet parce qu'une constante de compilation ne peut pas être référencée depuis
 * l'initialiseur d'une propriété du même objet.
 */
internal const val WHITESPACE_CLASS =
    "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

internal object DartTextSemantics {

    /**
     * L'ensemble `WhiteSpace ∪ LineTerminator` d'ECMAScript, que le `RegExp(r'\s+')` de Dart
     * applique.
     *
     * ⚠️ **`\s` seul ne suffit pas** : en Java il ne couvre que `[ \t\n\x0B\f\r]`. La divergence
     * n'est pas théorique en français — l'espace fine insécable **U+202F** est ce que produisent
     * les claviers et les correcteurs devant `: ; ! ?`, et elle voyage par copier-coller.
     *
     * ⚠️ **U+200B (espace sans chasse) n'y est PAS**, et c'est correct : ECMAScript ne le compte pas
     * comme un espace. Mesuré — un mot coupé par U+200B reste un seul terme des deux côtés.
     */
    val WHITESPACE: Regex = Regex("$WHITESPACE_CLASS+")

    /** Fin de ligne suivante : élaguée par Dart, absente de son `\s`. L'asymétrie est dans Dart. */
    private const val NEXT_LINE = 0x85

    /**
     * Élague comme `String.trim()` de Dart, et **surtout pas** comme celui de Kotlin.
     *
     * Deux écarts, tous deux mesurés :
     *
     * - `Char.isWhitespace()` de Kotlin est vrai pour U+001C à U+001F — les séparateurs de fichier,
     *   de groupe, d'enregistrement et d'unité — que Dart laisse en place. Un titre réduit à un
     *   U+001F est un lien valide dans l'application publiée ; l'élagage de Kotlin le ferait
     *   disparaître. Le cas paraît absurde jusqu'à ce qu'on se rappelle d'où vient ce genre
     *   d'octet : un copier-coller depuis un export CSV ou un terminal.
     * - Dart élague U+0085, que son propre `\s` ne reconnaît pas. Un U+0085 au milieu d'un titre y
     *   survit donc, aux extrémités non.
     *
     * La règle ici n'est pas « quel élagage est le plus sensé » mais « lequel produit la même clé ».
     */
    fun trim(value: String): String = value.trim { it.isDartTrimmable() }

    /**
     * `trim(value).isEmpty()`, without copying [value]: it stops at the first character [trim] would
     * keep. The Markdown preview asks it on every keystroke, and [trim] copies a whole note as soon
     * as it ends with a line break (Gemini review, 2026-09-25).
     */
    fun isBlank(value: CharSequence): Boolean = value.all { it.isDartTrimmable() }

    /**
     * Écrit en toutes lettres plutôt que dérivé de [WHITESPACE] : tester chaque caractère par
     * expression régulière allouerait une chaîne par caractère, sur un chemin parcouru à chaque
     * frappe.
     *
     * Les deux listes doivent donc rester alignées, à U+0085 près. `DartTextSemanticsTest` le
     * vérifie en balayant **tout le plan de base** plutôt qu'en comptant sur la vigilance du
     * prochain lecteur.
     */
    private fun Char.isDartTrimmable(): Boolean = when (code) {
        // Tabulation, saut de ligne, tabulation verticale, page, retour chariot, espace.
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20 -> true
        NEXT_LINE -> true
        // Espace insécable, espace ogham, séparateur de ligne, séparateur de paragraphe.
        0xA0, 0x1680, 0x2028, 0x2029 -> true
        // Espace insécable étroite, espace mathématique moyen, espace idéographique, indicateur
        // d'ordre des octets.
        0x202F, 0x205F, 0x3000, 0xFEFF -> true
        // Cadratins, demi-cadratins et leurs variantes fines : U+2000 à U+200A, contigus.
        else -> code in 0x2000..0x200A
    }

    /**
     * Passe en minuscules **point de code par point de code**, comme Dart, et non chaîne entière
     * comme Java.
     *
     * `String.lowercase()` de Java applique la casse *complète* : celle qui peut changer le nombre
     * de caractères et dépendre du contexte. Deux règles s'y déclenchent que Dart n'a pas :
     *
     * - **sigma final** — `ΟΔΟΣ` rend `οδος` en Java, `οδοσ` en Dart ;
     * - **I point suscrit** — `İ` rend `i` suivi de U+0307 en Java, un seul `i` en Dart.
     *
     * `Character.toLowerCase(codePoint)` applique la casse *simple*, une correspondance de point de
     * code à point de code sans contexte : exactement ce que fait Dart.
     *
     * L'itération porte sur les **points de code** et non sur les `Char` : `Character.toLowerCase`
     * appliqué à une moitié de paire de substitution ne ferait rien, et les alphabets hors du plan
     * de base — le déséret, par exemple — ne passeraient jamais en minuscules. Mesuré : Dart les
     * traite, donc ce portage doit les traiter.
     *
     * ⚠️ **Un écart résiduel subsiste, borné et mesuré** : la table de casse de Dart ignore quelques
     * alphabets ajoutés à Unicode après elle — l'osage (U+104B0) et l'adlam (U+1E900) sont dans ce
     * cas, vérifiés. Java les traite, Dart non. La conséquence se limite à un lien `[[…]]` écrit
     * dans l'un de ces alphabets, qui ne s'apparierait pas entre les deux versions ; il se répare de
     * lui-même dès que la version Kotlin réindexe la note. Fermer cet écart demanderait de recopier
     * la table de casse de Dart, ce qui échangerait un défaut invisible contre une table figée qui
     * vieillirait mal.
     */
    fun lowercase(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            out.appendCodePoint(Character.toLowerCase(codePoint))
            index += Character.charCount(codePoint)
        }
        return out.toString()
    }
}
