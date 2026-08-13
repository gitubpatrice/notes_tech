package com.filestech.notes_tech.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * FTS5 interprète ce qu'il reçoit comme un **langage de requête**, pas comme du texte.
 *
 * Sans neutralisation, chercher `l'été` lève une `SQLiteException`. C'est un défaut invisible en
 * anglais et systématique en français — exactement le genre qui traverse une campagne de tests.
 */
class FtsMatchExpressionTest {

    @Test
    fun `un terme simple devient une phrase avec prefixe`() {
        assertThat(FtsMatchExpression.from("budget")).isEqualTo("\"budget\"*")
    }

    @Test
    fun `plusieurs termes sont juxtaposes, ce que FTS5 lit comme un ET`() {
        assertThat(FtsMatchExpression.from("budget reunion")).isEqualTo("\"budget\"* \"reunion\"*")
    }

    @Test
    fun `les accents sont conserves car c'est l'index qui les neutralise`() {
        // `tokenize='unicode61 remove_diacritics 2'` s'en charge côté SQLite. Les dépouiller ici
        // en plus ne servirait à rien et ferait diverger deux endroits qui doivent s'accorder.
        assertThat(FtsMatchExpression.from("Réunion")).isEqualTo("\"Réunion\"*")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            " ",
            "   \t\n  ",
            "'",
            "\"",
            "(((",
            ")",
            "-",
            "*",
            "^",
            ":",
            "+++",
            "...",
            "??",
        ],
    )
    fun `une saisie sans terme exploitable rend null plutot qu'une expression vide`(saisie: String) {
        // `null` et non `""` : une chaîne vide passée à MATCH est elle-même une erreur de syntaxe.
        // Le type force l'appelant à court-circuiter la requête.
        assertThat(FtsMatchExpression.from(saisie)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["l'été", "C++", "NEAR", "AND", "OR", "NOT", "a-b", "\"quoted\"", "50%"])
    fun `les saisies qui feraient echouer FTS5 sont neutralisees en phrases`(saisie: String) {
        val expression = FtsMatchExpression.from(saisie)

        assertThat(expression).isNotNull()
        // Chaque terme est encadré de guillemets : FTS5 le traite comme une phrase littérale, donc
        // `NEAR` cesse d'être un mot-clé et `-` cesse d'être un opérateur de négation.
        assertThat(expression!!.split(" ")).isNotEmpty()
        expression.split(" ").forEach { terme ->
            assertThat(terme).startsWith("\"")
            assertThat(terme).endsWith("\"*")
        }
    }

    @Test
    fun `les mots-cles FTS5 perdent leur sens d'operateur`() {
        assertThat(FtsMatchExpression.from("NEAR")).isEqualTo("\"NEAR\"*")
        // `a-b` deviendrait `a NOT b` sans neutralisation : deux termes au lieu d'un, et un
        // résultat faux plutôt qu'une erreur — le pire des deux.
        assertThat(FtsMatchExpression.from("a-b")).isEqualTo("\"a\"* \"b\"*")
    }

    @Test
    fun `les chiffres et le tiret bas sont des caracteres de terme`() {
        assertThat(FtsMatchExpression.from("note_2025")).isEqualTo("\"note_2025\"*")
    }
}
