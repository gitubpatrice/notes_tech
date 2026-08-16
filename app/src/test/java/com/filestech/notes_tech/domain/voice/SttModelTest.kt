package com.filestech.notes_tech.domain.voice

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * La barrière de chemin de [SttModel.fileName].
 *
 * L'identifiant d'un modèle sert de **nom de fichier**. Le catalogue est en dur dans le code, donc
 * rien d'externe ne le fabrique aujourd'hui — mais c'est exactement ce qu'on disait des noms de
 * dossier d'une archive avant d'y trouver trois défauts d'affilée (`04-PIEGES.md`). Une garde qui
 * n'a pas de test est une garde qu'on retirera un jour « puisqu'elle ne sert à rien ».
 */
@DisplayName("SttModel — le nom de fichier ne peut pas sortir du répertoire des modèles")
class SttModelTest {

    @Test
    @DisplayName("un identifiant kebab-case donne <id>.bin")
    fun identifiantValide() {
        assertThat(modele("whisper-base-fr").fileName).isEqualTo("whisper-base-fr.bin")
        assertThat(modele("ggml_small_q5").fileName).isEqualTo("ggml_small_q5.bin")
        assertThat(modele("v3").fileName).isEqualTo("v3.bin")
    }

    /**
     * 🔴 Les trois formes qui feraient écrire ailleurs, et deux qui feraient un nom illisible.
     *
     * `..` remonte d'un répertoire, `/` et `\` en désignent un autre, l'espace et les majuscules
     * sortent du contrat annoncé — et un identifiant vide produirait le fichier `.bin`, invisible.
     */
    @Test
    @DisplayName("un identifiant qui pourrait sortir du répertoire est REFUSÉ")
    fun identifiantDangereuxRefuse() {
        for (dangereux in listOf("..", "../secret", "modeles/../..", "a\\b", "a/b", "", " ", "Whisper", "é")) {
            assertThrows<IllegalArgumentException>("« $dangereux » aurait dû être refusé") {
                modele(dangereux).fileName
            }
        }
    }

    /** ⚠️ Le refus porte sur `fileName`, pas sur la construction : un modèle se lit sans risque. */
    @Test
    @DisplayName("construire un modèle à l'identifiant douteux ne lève pas — c'est l'usage qui garde")
    fun constructionToleree() {
        val douteux = modele("../secret")

        assertThat(douteux.id).isEqualTo("../secret")
        assertThrows<IllegalArgumentException> { douteux.fileName }
    }

    private fun modele(id: String) = SttModel(
        id = id,
        displayName = "Whisper Base",
        expectedSha256 = "0".repeat(64),
        sizeBytes = 57_000_000,
        language = "fr",
        notes = "",
        fichierAmont = "ggml-base-q5_1.bin",
    )
}
