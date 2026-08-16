package com.filestech.notes_tech.data.voice

import com.filestech.notes_tech.domain.voice.SttModelCatalogue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Les deux règles de taille de l'import, isolées de tout appareil.
 *
 * ⚠️ Elles se ressemblent et ne servent pas à la même chose. La **tolérance** rejette d'emblée un
 * fichier qui n'a rien à voir, d'après ce que la source annonce. La **borne** limite ce qui sera
 * réellement écrit, d'après ce que le modèle attend — et elle tient même quand la source ne dit
 * rien, ce qui est précisément le cas où la première ne peut pas s'appliquer.
 */
@DisplayName("SttModelStore — la tolerance ecarte, la borne contient")
class SttModelStoreTaillesTest {

    @Test
    @DisplayName("un ecart de quelques pour cent passe, un fichier sans rapport ne passe pas")
    fun tolerance() {
        val attendue = 59_700_000L

        assertThat(SttModelStore.horsTolerance(attendue, attendue)).isFalse()
        assertThat(SttModelStore.horsTolerance((attendue * 1.05).toLong(), attendue)).isFalse()
        assertThat(SttModelStore.horsTolerance((attendue * 0.95).toLong(), attendue)).isFalse()

        // Une photo, une vidéo, un fichier tronqué : la question n'est pas de valider un modèle,
        // c'est de ne pas lire cinquante mégaoctets pour découvrir que ce n'en était pas un.
        assertThat(SttModelStore.horsTolerance(4_000_000L, attendue)).isTrue()
        assertThat(SttModelStore.horsTolerance(3_000_000_000L, attendue)).isTrue()
    }

    @Test
    @DisplayName("la borne laisse passer la taille attendue et son jeu, jamais le double")
    fun borne() {
        SttModelCatalogue.tous.forEach { modele ->
            val borne = SttModelStore.borneDeCopie(modele)

            assertThat(borne).isGreaterThan(modele.sizeBytes)
            assertThat(borne).isLessThan(modele.sizeBytes * 2)
        }
    }

    @Test
    @DisplayName("une taille attendue nulle desarme la tolerance, jamais la borne")
    fun tailleInconnue() {
        // ⚠️ Le cas n'existe pas dans le catalogue actuel, et c'est justement pourquoi il est testé :
        // il décrit ce qui se passerait si une entrée future omettait sa taille. La tolérance se tait
        // — comparer à zéro n'a pas de sens — mais la borne reste strictement positive, donc la copie
        // reste limitée. Une garde désarmée est pire qu'absente : elle se lit comme une protection.
        assertThat(SttModelStore.horsTolerance(12_345L, 0L)).isFalse()
        assertThat(SttModelStore.borneDeCopie(SttModelCatalogue.parDefaut.copy(sizeBytes = 0L)))
            .isAtLeast(1L)
    }
}
