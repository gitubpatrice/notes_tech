package com.filestech.notes_tech.domain.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * La copie vérifiée, **à la valeur**.
 *
 * ⚠️ Une empreinte fausse ne se voit nulle part : l'import réussit, le fichier pèse le bon poids, et
 * ce qui a été laissé passer est un binaire que personne n'a contrôlé. C'est le motif de `WavPcm16`
 * — *le format est un contrat avec un autre logiciel* — poussé d'un cran : ici le contrat porte sur
 * l'origine de ce qu'on exécutera.
 */
@DisplayName("CopieVerifiee — l'empreinte decrit les octets ecrits, et la borne tient")
class CopieVerifieeTest {

    @Test
    @DisplayName("l'empreinte d'un flux vide est celle du vide")
    fun fluxVide() = runBlocking {
        val sortie = ByteArrayOutputStream()

        val resultat = CopieVerifiee.copier(ByteArrayInputStream(ByteArray(0)), sortie, octetsMax = 16)

        assertThat(resultat.octets).isEqualTo(0)
        assertThat(resultat.empreinteSha256)
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(sortie.size()).isEqualTo(0)
    }

    @Test
    @DisplayName("l'empreinte est celle du contenu, en hexadecimal minuscule")
    fun empreinteConnue() = runBlocking {
        val sortie = ByteArrayOutputStream()

        val resultat = CopieVerifiee.copier(ByteArrayInputStream("abc".toByteArray()), sortie, octetsMax = 16)

        assertThat(resultat.empreinteSha256)
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(sortie.toByteArray()).isEqualTo("abc".toByteArray())
    }

    @Test
    @DisplayName("sur plusieurs blocs, elle vaut celle calculee d'un seul tenant")
    fun plusieursBlocs() = runBlocking {
        // ⚠️ Le vrai risque est là : un contenu plus grand qu'un bloc passe par plusieurs `update`,
        // et une erreur de décalage ne se verrait que sur des tailles non multiples du bloc. D'où
        // une taille volontairement bancale.
        val contenu = ByteArray(CopieVerifiee.TAILLE_DU_BLOC * 3 + 517) { (it * 31 % 251).toByte() }
        val attendue = MessageDigest.getInstance("SHA-256").digest(contenu)
        val sortie = ByteArrayOutputStream()

        val resultat = CopieVerifiee.copier(
            ByteArrayInputStream(contenu),
            sortie,
            octetsMax = contenu.size.toLong(),
        )

        assertThat(resultat.octets).isEqualTo(contenu.size.toLong())
        assertThat(resultat.empreinteSha256).isEqualTo(enHexadecimal(attendue))
        assertThat(sortie.toByteArray()).isEqualTo(contenu)
    }

    @Test
    @DisplayName("le depassement de la borne coupe AVANT d'ecrire le bloc de trop")
    fun borneRespectee() {
        val sortie = ByteArrayOutputStream()

        assertThrows<SttModelSourceInvalidException> {
            runBlocking { CopieVerifiee.copier(ByteArrayInputStream(ByteArray(11)), sortie, octetsMax = 10) }
        }

        // 🔴 Rien n'a été écrit : la borne est contrôlée avant l'écriture, pas après. Contrôlée
        // après, le fichier de destination porterait les octets qu'on vient de refuser.
        assertThat(sortie.size()).isEqualTo(0)
    }

    @Test
    @DisplayName("une source qui n'avance plus finit par etre abandonnee")
    fun sourceImmobile() {
        val immobile = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException("non utilise")
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }

        assertThrows<SttModelSourceInvalidException> {
            runBlocking { CopieVerifiee.copier(immobile, ByteArrayOutputStream(), octetsMax = 1024) }
        }
    }

    @Test
    @DisplayName("la progression est espacee, et le dernier appel porte le total exact")
    fun progression() = runBlocking {
        val octets = (CopieVerifiee.PAS_DE_PROGRESSION * 2 + 1234).toInt()
        val etapes = mutableListOf<Long>()

        val resultat = CopieVerifiee.copier(
            ByteArrayInputStream(ByteArray(octets)),
            ByteArrayOutputStream(),
            octetsMax = octets.toLong(),
            onProgress = { etapes += it },
        )

        assertThat(etapes.last()).isEqualTo(resultat.octets)
        assertThat(etapes).isInOrder()
        // ⚠️ Deux tranches franchies, plus l'appel final : l'espacement tient, et la barre atteint
        // bien la fin — c'est l'un ou l'autre qui manque quand on se trompe de côté.
        assertThat(etapes).hasSize(3)
    }

    @Test
    @DisplayName("une portee annulee arrete la copie au bloc suivant")
    fun annulation() = runBlocking {
        // 🔴 Le défaut que ce test empêche de revenir : `read` est bloquant et ignore les coroutines.
        // Sans le contrôle d'annulation dans la boucle, la copie irait jusqu'au bout de la source et
        // l'annulation n'apparaîtrait qu'après le retour — donc hors du `try` qui efface le fichier
        // partiel. Trouvé le 2026-08-15 dans la boucle de capture, par les deux relectures externes.
        lateinit var travail: Job
        val sortie = ByteArrayOutputStream()
        val source = object : InputStream() {
            var appels = 0
            override fun read(): Int = throw UnsupportedOperationException("non utilise")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                appels++
                if (appels == 3) travail.cancel()
                java.util.Arrays.fill(b, off, off + 8, 1.toByte())
                return 8
            }
        }

        // ⚠️ Démarrage différé : la source annule `travail` depuis l'intérieur de la copie, donc la
        // variable doit être affectée avant que le premier bloc ne soit lu. Un `launch` ordinaire
        // laisserait la course décider, et le test échouerait un jour sur trois.
        travail = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            CopieVerifiee.copier(source, sortie, octetsMax = 10_000_000)
        }
        travail.start()
        travail.join()

        assertThat(travail.isCancelled).isTrue()
        // Trois lectures ont abouti — celle qui annule écrit encore, le contrôle est en tête de
        // boucle — puis plus rien. Une source infinie, arrêtée en trois tours.
        assertThat(sortie.size()).isEqualTo(24)
        assertThat(source.appels).isEqualTo(3)
    }

    @Test
    @DisplayName("l'empreinte sans copie donne le meme resultat, sans rien conserver")
    fun empreinteSansCopie() = runBlocking {
        val contenu = "abc".toByteArray()

        val resultat = CopieVerifiee.empreinteDe(ByteArrayInputStream(contenu), octetsMax = 16)

        assertThat(resultat.octets).isEqualTo(3)
        assertThat(resultat.empreinteSha256)
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    @DisplayName("une borne nulle ou negative est refusee")
    fun borneInvalide() {
        val sortie: OutputStream = ByteArrayOutputStream()

        assertThrows<IllegalArgumentException> {
            runBlocking { CopieVerifiee.copier(ByteArrayInputStream(ByteArray(1)), sortie, octetsMax = 0) }
        }
    }

    private fun enHexadecimal(octets: ByteArray): String = octets.joinToString("") { "%02x".format(it) }
}
