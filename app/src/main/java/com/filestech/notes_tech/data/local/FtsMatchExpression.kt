package com.filestech.notes_tech.data.local

/**
 * Transforme une saisie utilisateur libre en expression `MATCH` valide pour FTS5.
 *
 * ## Portage **à l'identique** de `_buildFtsMatch` (`notes_tech/lib/data/db/notes_dao.dart:485`)
 *
 * Ce n'est pas une réécriture améliorée : c'est une transposition. Une recherche qui ne rend pas
 * les mêmes résultats que l'application publiée est une régression, même si l'algorithme retenu se
 * défend dans l'absolu.
 *
 * ### Ce que fait l'algorithme, et pourquoi
 *
 * 1. **Découpage sur les espaces uniquement.** Chaque terme reste entier, apostrophes et
 *    ponctuation comprises.
 * 2. **Chaque terme est mis entre guillemets**, guillemets internes doublés. FTS5 le traite alors
 *    comme une phrase littérale : `NEAR` cesse d'être un mot-clé, `-` un opérateur de négation, et
 *    une parenthèse déséquilibrée ne fait plus lever de `SQLiteException`.
 * 3. **Seul le DERNIER terme reçoit `*`**, et seulement s'il est entièrement alphanumérique. C'est
 *    celui que l'utilisateur est en train de taper ; les précédents sont des mots achevés.
 *
 * ### La faute que ce fichier a d'abord commise
 *
 * Une première version découpait sur **tout ce qui n'est ni lettre ni chiffre** et préfixait
 * **chaque** terme. `l'été` devenait alors `"l"* "été"*` : deux termes au lieu d'un, dont `"l"*`
 * qui rattrape presque toutes les notes d'un corpus français. Ce n'était pas une erreur visible —
 * c'était une recherche silencieusement fausse, et seulement en français.
 *
 * Relevé par l'audit `data-room` du 2026-08-13, par comparaison avec le vrai code Flutter. Ni la
 * relecture Gemini ni la relecture GPT ne pouvaient le voir : elles n'avaient pas la source
 * d'origine sous les yeux.
 *
 * Extrait de son DAO pour être testable sur la JVM — c'est la logique la plus piégeuse de la
 * couche, et la faire dépendre d'un appareil pour être vérifiée serait le mauvais arbitrage.
 */
internal object FtsMatchExpression {

    private val WHITESPACE = Regex("\\s+")

    /**
     * FTS5 n'accepte le suffixe `*` que derrière un terme alphanumérique.
     *
     * `\p{L}` et `\p{N}` sont les catégories Unicode : « été » et « 2025 » passent, « C++ » et
     * « l'été » non — ceux-là sont cherchés sans préfixe, ce qui reste juste.
     */
    private val PREFIXABLE = Regex("^[\\p{L}\\p{N}]+$")

    /**
     * Rend l'expression `MATCH`, ou `null` si la saisie ne contient aucun terme.
     *
     * `null` et non une chaîne vide : une chaîne vide passée à `MATCH` est elle-même une erreur de
     * syntaxe. Le type oblige donc l'appelant à court-circuiter la requête, au lieu de le lui
     * rappeler.
     */
    fun from(rawInput: String): String? {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) return null

        val tokens = cleaned
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .map { it.replace("\"", "\"\"") }
        if (tokens.isEmpty()) return null

        return tokens
            .mapIndexed { index, token ->
                val canPrefix = index == tokens.lastIndex && PREFIXABLE.matches(token)
                if (canPrefix) "\"$token\"*" else "\"$token\""
            }
            .joinToString(" ")
    }
}
