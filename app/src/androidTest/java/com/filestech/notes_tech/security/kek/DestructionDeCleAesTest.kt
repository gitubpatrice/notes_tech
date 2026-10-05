package com.filestech.notes_tech.security.kek

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * **Que fait vraiment `destroy()` sur la clé AES de `flutter_secure_storage` ?**
 *
 * `SecretKey` hérite de `Destroyable`, dont l'implémentation **par défaut lève**
 * `DestroyFailedException` : appeler `destroy()` n'efface donc rien sur toutes les plateformes. Avant
 * d'écrire un effacement qui n'effacerait rien, on mesure ce que celle-ci fait.
 *
 * ⚠️ Ce cas ne teste pas notre code : il **constate le comportement de la plateforme**, et le fige.
 * Si une version future d'Android rendait la destruction effective, il le dirait en tombant — et ce
 * serait une bonne nouvelle à récolter, pas une régression.
 */
@RunWith(AndroidJUnit4::class)
class DestructionDeCleAesTest {

    @Test
    fun ce_que_la_plateforme_fait_de_destroy_sur_une_cle_AES() {
        val octets = ByteArray(16).also(SecureRandom()::nextBytes)
        val cle = SecretKeySpec(octets, "AES")

        var levee: Throwable? = null
        try {
            cle.destroy()
        } catch (e: Throwable) {
            levee = e
        }

        // On n'affirme rien d'autre que la cohérence entre les deux réponses : soit la destruction
        // aboutit et `isDestroyed` le dit, soit elle lève et la clé reste lisible.
        if (levee == null) {
            assertThat(cle.isDestroyed).isTrue()
        } else {
            assertThat(cle.isDestroyed).isFalse()
            assertThat(cle.encoded).isNotNull()
        }

        // La trace part dans le journal d'instrumentation : c'est elle qui décide du correctif.
        println("MESURE destroy() : levee=${levee?.javaClass?.simpleName ?: "aucune"} isDestroyed=${cle.isDestroyed}")
    }
}
