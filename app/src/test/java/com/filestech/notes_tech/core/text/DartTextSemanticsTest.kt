package com.filestech.notes_tech.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Verrouille les propriétés internes de [DartTextSemantics] que la parité seule ne verrouille pas.
 *
 * Le test de parité (`PariteAvecFlutterTest`) rejoue un corpus fini. Ces tests-ci balaient
 * l'intégralité du plan multilingue de base : ils attrapent le caractère auquel personne n'a pensé,
 * qui est par définition celui qui manquera au corpus.
 */
@DisplayName("Sémantique de texte de Dart")
class DartTextSemanticsTest {

    /**
     * L'expression régulière et la table d'élagage décrivent le **même** ensemble, à U+0085 près.
     *
     * Les deux existent séparément pour une raison de coût — tester chaque caractère par expression
     * régulière allouerait une chaîne par caractère sur le chemin de la frappe. Ce test est le prix
     * de cette duplication : il la rend impossible à désaligner en silence.
     *
     * Sans lui, ajouter un espace Unicode à l'expression et oublier la table produirait exactement
     * le genre de défaut que ce portage cherche à éviter — un titre normalisé différemment selon
     * qu'il porte l'espace au milieu ou au bord.
     */
    @Test
    @DisplayName("l'élagage et la classe d'espaces couvrent le même ensemble, à U+0085 près")
    fun elagageEtClasseAlignesSurToutLePlanDeBase() {
        val desaccords = mutableListOf<String>()

        for (code in 0x0000..0xFFFF) {
            val caractere = code.toChar().toString()
            val elague = DartTextSemantics.trim(caractere).isEmpty()
            val attendu = DartTextSemantics.WHITESPACE.matches(caractere) || code == 0x85
            if (elague != attendu) {
                desaccords += "U+%04X : élagué=%b classe=%b".format(code, elague, attendu)
            }
        }

        assertThat(desaccords).isEmpty()
    }

    /**
     * ⚠️ Le caractère qui a motivé tout ce fichier.
     *
     * U+202F, l'espace fine insécable, est ce que produisent les claviers et correcteurs français
     * devant `: ; ! ?`. Avec le `\s` de Java, deux mots qu'elle sépare resteraient **un seul terme**
     * — donc une clé d'appariement différente de celle de l'application publiée.
     */
    @Test
    @DisplayName("l'espace fine insécable sépare bien deux mots")
    fun espaceFineInsecableEstUnEspace() {
        val fine = String(Character.toChars(0x202F))

        assertThat(DartTextSemantics.WHITESPACE.split("reunion${fine}budget")).containsExactly("reunion", "budget")
        assertThat("reunion${fine}budget".split(Regex("\\s+"))).hasSize(1) // ce que Java aurait fait
    }

    /**
     * ⚠️ L'espace sans chasse **n'est pas** un espace pour ECMAScript, donc pas pour Dart.
     *
     * Contre-test délibéré : il empêche d'« améliorer » la classe en y ajoutant tous les caractères
     * qui ressemblent à un espace. Chaque ajout est une divergence avec la version publiée.
     */
    @Test
    @DisplayName("l'espace sans chasse n'est pas un espace, et ne doit pas le devenir")
    fun espaceSansChasseNEstPasUnEspace() {
        val sansChasse = String(Character.toChars(0x200B))

        assertThat(DartTextSemantics.WHITESPACE.split("a${sansChasse}b")).hasSize(1)
        assertThat(DartTextSemantics.trim(sansChasse)).isEqualTo(sansChasse)
    }

    /**
     * ⚠️ Les deux règles de casse **complète** de Java, dont ni l'une ni l'autre n'existe en Dart.
     *
     * Ce test échouerait sur un `value.lowercase()` ordinaire. C'est sa raison d'être : il nomme
     * l'implémentation fautive la plus tentante.
     */
    @Test
    @DisplayName("la casse est simple, sans sigma final ni décomposition du I point suscrit")
    fun casseSimpleEtNonComplete() {
        val odos = "ΟΔΟΣ"
        val iPointSuscrit = String(Character.toChars(0x0130))

        assertThat(DartTextSemantics.lowercase(odos)).isEqualTo("οδοσ")
        assertThat(odos.lowercase()).isEqualTo("οδος") // la règle du sigma final, que Java applique

        assertThat(DartTextSemantics.lowercase(iPointSuscrit)).isEqualTo("i")
        assertThat(iPointSuscrit.lowercase()).hasLength(2) // Java y ajoute U+0307
    }

    /**
     * Les alphabets hors du plan de base passent en minuscules — ce qui exige d'itérer sur les
     * points de code et non sur les `Char`.
     *
     * Une implémentation par `Char` laisserait le déséret intact, sans erreur ni signe.
     */
    @Test
    @DisplayName("les alphabets hors du plan de base passent aussi en minuscules")
    fun casseAppliqueeHorsDuPlanDeBase() {
        val deseretMajuscule = String(Character.toChars(0x10400))
        val deseretMinuscule = String(Character.toChars(0x10428))

        assertThat(DartTextSemantics.lowercase(deseretMajuscule)).isEqualTo(deseretMinuscule)
    }
}
