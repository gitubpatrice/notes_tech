package com.filestech.notes_tech.i18n

import com.filestech.notes_tech.data.prefs.LocalePreference
import com.filestech.notes_tech.ui.common.MOTIFS_DE_DATE
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * **A language lives in five places that do not know each other** — Pass Tech 2.7.0 learnt it when
 * its APK filter dropped the three languages it had just translated, without an error (2026-09-20).
 *
 * One list, `LocalePreference.LANGUES`, and every other place checked against it: the APK's
 * `localeFilters`, the `values-*` folders, the legal pages in `raw-*`, the date patterns — and each
 * language's strings against the English ones, name for name, argument for argument, with the plural
 * forms its grammar needs. A string added in English without its translations fails here.
 */
class LanguesDeLApplicationTest {

    @Test
    @DisplayName("l'APK garde toutes les langues que l'application propose")
    fun l_apk_garde_toutes_les_langues() {
        val gradle = File("build.gradle.kts").readText()
        val filtre = requireNotNull(Regex("""localeFilters \+= listOf\(([^)]*)\)""").find(gradle)) {
            "localeFilters introuvable dans app/build.gradle.kts"
        }.groupValues[1]
        val codes = Regex("\"(\\w+)\"").findAll(filtre).map { it.groupValues[1] }.toSet()

        assertThat(codes).isEqualTo(LocalePreference.LANGUES.toSet())
    }

    @Test
    @DisplayName("chaque langue a ses chaines, ses pages legales et son format de date")
    fun chaque_langue_a_ses_fichiers() {
        for (langue in LocalePreference.LANGUES) {
            assertWithMessage(langue).that(fichierDeChaines(langue).isFile).isTrue()
            for (page in PAGES_LEGALES) {
                assertWithMessage("$langue/$page").that(File(dossierBrut(langue), page).isFile).isTrue()
            }
        }
        assertThat(MOTIFS_DE_DATE.keys).isEqualTo(LocalePreference.LANGUES.toSet())
    }

    @Test
    @DisplayName("chaque langue traduit toutes les chaines anglaises, avec les memes arguments")
    fun chaque_langue_traduit_tout() {
        val anglais = ressources("en")
        assertThat(anglais).isNotEmpty()

        for (langue in LocalePreference.LANGUES - "en") {
            val traduites = ressources(langue)
            assertWithMessage("les noms de $langue").that(traduites.keys).isEqualTo(anglais.keys)

            for ((nom, reference) in anglais) {
                val traduction = traduites.getValue(nom)
                when (reference) {
                    is Chaine -> {
                        assertWithMessage("$langue:$nom est une chaine")
                            .that(traduction)
                            .isInstanceOf(Chaine::class.java)
                        assertWithMessage("$langue:$nom, arguments")
                            .that(arguments((traduction as Chaine).texte))
                            .isEqualTo(arguments(reference.texte))
                    }
                    is Pluriel -> {
                        assertWithMessage("$langue:$nom est un pluriel")
                            .that(traduction)
                            .isInstanceOf(Pluriel::class.java)
                        verifierLePluriel(langue, nom, reference, traduction as Pluriel)
                    }
                }
            }
        }
    }

    private fun verifierLePluriel(langue: String, nom: String, reference: Pluriel, traduction: Pluriel) {
        val attendues = if (langue in AVEC_MANY) setOf("one", "many", "other") else setOf("one", "other")
        assertWithMessage("$langue:$nom, formes").that(traduction.formes.keys).containsAtLeastElementsIn(attendues)
        val argumentsDeOther = arguments(reference.formes.getValue("other"))
        assertWithMessage("$langue:$nom, arguments de other")
            .that(arguments(traduction.formes.getValue("other")))
            .isEqualTo(argumentsDeOther)
        for ((forme, texte) in traduction.formes) {
            // "one" may say "One note" instead of "%1$d note": fewer arguments, never others.
            assertWithMessage("$langue:$nom, arguments de $forme")
                .that(argumentsDeOther)
                .containsAtLeastElementsIn(arguments(texte))
        }
    }

    private sealed interface Ressource
    private data class Chaine(val texte: String) : Ressource
    private data class Pluriel(val formes: Map<String, String>) : Ressource

    private fun ressources(langue: String): Map<String, Ressource> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(fichierDeChaines(langue))
        val racine = document.documentElement
        val resultat = linkedMapOf<String, Ressource>()
        val enfants = racine.childNodes
        val elements = (0 until enfants.length).mapNotNull { enfants.item(it) as? Element }
        for (element in elements) {
            val nom = element.getAttribute("name")
            val ressource = when (element.tagName) {
                "string" -> Chaine(element.textContent)
                "plurals" -> {
                    val items = element.getElementsByTagName("item")
                    Pluriel(
                        (0 until items.length).associate { j ->
                            val item = items.item(j) as Element
                            item.getAttribute("quantity") to item.textContent
                        },
                    )
                }
                else -> null
            } ?: continue
            assertWithMessage("$langue:$nom en double").that(resultat.put(nom, ressource)).isNull()
        }
        return resultat
    }

    /** The format arguments of [texte], sorted: `%1$d`, `%2$s`… — a literal `%%` is not one. */
    private fun arguments(texte: String): List<String> =
        ARGUMENT.findAll(texte.replace("%%", "")).map { it.value }.sorted().toList()

    private companion object {
        /** Gradle runs the JVM tests from the module's directory. */
        const val RES = "src/main/res"
        val PAGES_LEGALES = listOf("privacy.md", "terms.md")

        /** CLDR's "many" (1 000 000 notes, "de" in French, "de" in Spanish, "di" in Italian). */
        val AVEC_MANY = setOf("fr", "es", "it")

        val ARGUMENT = Regex("""%(\d+\$)?[a-zA-Z]""")

        fun fichierDeChaines(langue: String) =
            File(if (langue == "en") "$RES/values/strings.xml" else "$RES/values-$langue/strings.xml")

        fun dossierBrut(langue: String) = File(if (langue == "en") "$RES/raw" else "$RES/raw-$langue")
    }
}
