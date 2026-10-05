package com.filestech.notes_tech.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * L'expression `MATCH` doit être **identique** à celle que produit l'application publiée
 * (`_buildFtsMatch`, `notes_tech/lib/data/db/notes_dao.dart:485`). Une recherche qui ne rend pas
 * les mêmes résultats est une régression, même si l'algorithme se défend dans l'absolu.
 *
 * FTS5 interprète par ailleurs ce qu'il reçoit comme un **langage de requête**, pas comme du texte :
 * sans neutralisation, chercher `l'été` lève une `SQLiteException`.
 */
class FtsMatchExpressionTest {
    @Test
    fun `un terme unique est prefixe car c'est celui qu'on tape`() {
        assertThat(FtsMatchExpression.from("budget")).isEqualTo("\"budget\"*")
    }

    @Test
    fun `seul le DERNIER terme est prefixe`() {
        // Les termes précédents sont des mots achevés ; les préfixer élargirait la recherche sans
        // que l'utilisateur l'ait demandé.
        assertThat(FtsMatchExpression.from("budget reunion")).isEqualTo("\"budget\" \"reunion\"*")
        assertThat(FtsMatchExpression.from("a b c")).isEqualTo("\"a\" \"b\" \"c\"*")
    }

    @Test
    fun `les accents sont conserves car c'est l'index qui les neutralise`() {
        // `tokenize='unicode61 remove_diacritics 2'` s'en charge côté SQLite. Les dépouiller ici en
        // plus ferait diverger deux endroits qui doivent s'accorder.
        assertThat(FtsMatchExpression.from("Réunion")).isEqualTo("\"Réunion\"*")
    }

    @Test
    fun `le decoupage se fait sur les espaces, PAS sur la ponctuation`() {
        // 🔴 Le défaut que ce test fige. Un découpage sur la ponctuation donnait `"l"* "été"*` :
        // deux termes au lieu d'un, dont `"l"*` qui rattrape presque tout le corpus. Silencieux,
        // et seulement en français.
        assertThat(FtsMatchExpression.from("l'été")).isEqualTo("\"l'été\"")
        assertThat(FtsMatchExpression.from("rock'n'roll")).isEqualTo("\"rock'n'roll\"")
    }

    @Test
    fun `un terme non alphanumerique n'est pas prefixe, mais reste cherche`() {
        // FTS5 n'accepte `*` que derrière un terme alphanumérique. `C++` est donc cherché tel quel.
        assertThat(FtsMatchExpression.from("C++")).isEqualTo("\"C++\"")
        assertThat(FtsMatchExpression.from("note_2025")).isEqualTo("\"note_2025\"")
    }

    @Test
    fun `les guillemets sont doubles pour rester dans la phrase`() {
        assertThat(FtsMatchExpression.from("dit \"oui\"")).isEqualTo("\"dit\" \"\"\"oui\"\"\"")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "   \t\n  "])
    fun `une saisie vide ou blanche rend null`(saisie: String) {
        // `null` et non `""` : une chaîne vide passée à MATCH est elle-même une erreur de syntaxe.
        assertThat(FtsMatchExpression.from(saisie)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["NEAR", "AND", "OR", "NOT", "-exclu", "*", "(((", ")", "^", ":"])
    fun `les operateurs FTS5 sont neutralises en phrases litterales`(saisie: String) {
        val expression = FtsMatchExpression.from(saisie)

        assertThat(expression).isNotNull()
        // Encadré de guillemets : FTS5 le traite comme du texte. `NEAR` cesse d'être un mot-clé,
        // `-` un opérateur de négation, et `(((` ne fait plus lever de SQLiteException.
        assertThat(expression!!).startsWith("\"")
    }
}
