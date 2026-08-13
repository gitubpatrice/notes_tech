package com.filestech.notes_tech.data.local

/**
 * Transforme une saisie utilisateur libre en expression `MATCH` valide pour FTS5.
 *
 * ## Le risque n'est pas l'injection SQL, c'est la syntaxe
 *
 * La valeur est **liée** en paramètre : aucune injection n'est possible. Mais FTS5 interprète ce
 * qu'il reçoit comme un **langage de requête**, pas comme du texte. Une apostrophe, un `*`, un
 * `-`, une parenthèse déséquilibrée, ou le mot-clé `NEAR` lèvent une `SQLiteException`.
 *
 * Autrement dit, sans neutralisation, l'utilisateur qui cherche `l'été` ou `C++` fait planter sa
 * recherche. C'est un défaut invisible en anglais et systématique en français.
 *
 * ## Ce que fait la transformation
 *
 * Découpage sur tout ce qui n'est ni lettre, ni chiffre, ni tiret bas ; chaque terme est mis entre
 * guillemets — ce qui le réduit à une simple phrase littérale — et suivi de `*` pour la recherche
 * par préfixe, qui est ce qu'attend quelqu'un qui tape au fil de la frappe.
 *
 * Extrait de son DAO pour être testable sur la JVM : c'est la logique la plus piégeuse de la
 * couche, et la faire dépendre d'un appareil pour être vérifiée serait exactement le mauvais
 * arbitrage.
 */
internal object FtsMatchExpression {

    /** Tout ce qui n'est ni lettre, ni chiffre, ni tiret bas sépare deux termes. */
    private val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}_]+")

    /**
     * Rend l'expression `MATCH`, ou `null` si la saisie ne contient aucun terme exploitable.
     *
     * `null` et non une chaîne vide : une chaîne vide passée à `MATCH` est elle-même une erreur de
     * syntaxe. L'appelant doit donc court-circuiter la requête, et le type l'y oblige.
     */
    fun from(rawInput: String): String? {
        val tokens = rawInput.split(TOKEN_SEPARATOR).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        // Le doublement des guillemets est superflu aujourd'hui — le découpage les a déjà retirés —
        // mais c'est la bonne façon d'échapper une phrase FTS5. Si le séparateur devenait moins
        // strict un jour, ce code ne se transformerait pas en défaut.
        return tokens.joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"*" }
    }
}
