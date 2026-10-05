package com.filestech.notes_tech.domain.voice

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le catalogue, **entrée par entrée**.
 *
 * ## Pourquoi tester des constantes
 *
 * Parce que ce ne sont pas des constantes d'affichage : ce sont les **seules** valeurs qui décident
 * si un binaire de plusieurs dizaines de mégaoctets sera exécuté par une bibliothèque native sur le
 * contenu des notes. Une empreinte tronquée d'un caractère, une majuscule dans l'hexadécimal, un
 * identifiant contenant un séparateur de chemin — rien de tout cela ne se voit à la lecture, et
 * chacun casse le contrôle d'une façon différente.
 *
 * ⚠️ Ce fichier ne vérifie pas que les empreintes sont **les bonnes** : cela demanderait les
 * fichiers, donc le réseau. Il vérifie qu'elles ont la **forme** d'une empreinte utilisable, ce qui
 * attrape la faute de frappe et le copier-coller incomplet.
 */
@DisplayName("SttModelCatalogue — chaque entree porte une empreinte utilisable")
class SttModelCatalogueTest {

    @Test
    @DisplayName("chaque empreinte fait 64 caracteres hexadecimaux minuscules")
    fun empreintesBienFormees() {
        SttModelCatalogue.tous.forEach { modele ->
            assertThat(modele.expectedSha256).matches("[0-9a-f]{64}")
        }
    }

    @Test
    @DisplayName("chaque identifiant donne un nom de fichier, donc respecte la barriere de chemin")
    fun identifiantsSurs() {
        SttModelCatalogue.tous.forEach { modele ->
            // 🔴 `fileName` lève si l'identifiant sort de `^[a-z0-9_-]+$`. L'appeler ici, c'est
            // vérifier la barrière de chemin sur les valeurs réellement livrées.
            assertThat(modele.fileName).isEqualTo("${modele.id}.bin")
        }
    }

    @Test
    @DisplayName("aucun identifiant en double")
    fun identifiantsDistincts() {
        val identifiants = SttModelCatalogue.tous.map { it.id }

        assertThat(identifiants).containsNoDuplicates()
    }

    @Test
    @DisplayName("chaque entree dit quel fichier telecharger, et sa taille approximative")
    fun entreesExploitables() {
        SttModelCatalogue.tous.forEach { modele ->
            // ⚠️ Sans le nom du fichier amont, l'import est impossible : la source publie une
            // trentaine de variantes dont les noms ne diffèrent que par un suffixe.
            assertThat(modele.fichierAmont).endsWith(".bin")
            assertThat(modele.displayName).isNotEmpty()
            assertThat(modele.sizeBytes).isGreaterThan(0L)
        }
    }

    @Test
    @DisplayName("la recherche par identifiant retrouve chaque entree, et rend null au-dela")
    fun rechercheParIdentifiant() {
        SttModelCatalogue.tous.forEach { modele ->
            assertThat(SttModelCatalogue.parIdentifiant(modele.id)).isEqualTo(modele)
        }

        // ⚠️ `null` est le cas d'une préférence désignant un modèle retiré du catalogue par une mise
        // à jour. L'appelant doit reconduire vers l'installation, pas échouer.
        assertThat(SttModelCatalogue.parIdentifiant("whisper-inexistant")).isNull()
    }

    @Test
    @DisplayName("le modele par defaut fait partie du catalogue")
    fun defautCoherent() {
        assertThat(SttModelCatalogue.tous).contains(SttModelCatalogue.parDefaut)
    }
}
