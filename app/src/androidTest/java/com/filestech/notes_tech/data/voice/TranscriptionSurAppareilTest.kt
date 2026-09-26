package com.filestech.notes_tech.data.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.domain.voice.SttModel
import com.filestech.notes_tech.domain.voice.SttModelCatalogue
import com.filestech.notes_tech.domain.voice.WavPcm16
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Le moteur transcrit-il ?** Posée pour la première fois le 2026-08-16.
 *
 * ## Pourquoi ce test existe
 *
 * Tout le reste de la dictée était vérifié — la capture, l'import du modèle, l'interface, les
 * empreintes — et la seule chose qui n'avait **jamais** été exercée était celle qui donne son nom à
 * la fonction. Ce qui était prouvé sur l'appareil se résumait à : `libnotes_stt.so` **se charge**.
 *
 * Patrice a alors signalé « rien n'a été entendu, aucun texte inséré ». Deux causes possibles et
 * indiscernables depuis l'écran : la **capture** ne produit rien, ou la **transcription** rend du
 * vide. Ce test coupe la question en deux — il transcrit un enregistrement dont on connaît le
 * contenu, sans micro et sans voix.
 *
 * ⚠️ **`jfk.wav` vient de `whisper.cpp`** (`samples/jfk.wav`), PCM 16 bits, 16 kHz, mono — exactement
 * le format que produit [VoiceCapture]. Il dit *« And so my fellow Americans, ask not what your
 * country can do for you, ask what you can do for your country. »*
 *
 * ⚠️ Il porte un bloc `LIST` **avant** ses données, ce que nos propres captures n'ont pas. C'est un
 * hasard utile : il exerce le décodeur sur un fichier qu'il n'a pas écrit lui-même.
 *
 * ## Ce que le test ne prétend pas mesurer
 *
 * Ni la qualité française, ni la détection de langue, ni le découpage en segments. Il répond à une
 * seule question, celle qui bloquait : **du son connu entre-t-il et du texte sort-il ?**
 *
 * ⚠️ [assumeTrue] plutôt qu'un échec quand aucun modèle n'est installé : le modèle pèse 57 Mo, il
 * s'importe à la main, et aucune intégration continue ne l'aura. Un test rouge chez tout le monde
 * finit ignoré par tout le monde. ⚠️⚠️ Contrepartie à connaître : **il compte alors comme
 * « ignoré », pas comme réussi** — c'est la raison pour laquelle le décompte d'ignorés se lit dans
 * le XML, cf. `04-PIEGES.md` §45.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionSurAppareilTest {

    @Test
    fun un_enregistrement_connu_produit_du_texte() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cible = instrumentation.targetContext
        val magasin = SttModelStore(cible)

        val modele: SttModel? = SttModelCatalogue.tous.firstOrNull { magasin.estPresent(it) }
        assumeTrue("aucun modele installe sur cet appareil", modele != null)

        val wav = File(cible.cacheDir, "jfk-test.wav")
        instrumentation.context.assets.open("jfk.wav").use { entree ->
            wav.outputStream().use { entree.copyTo(it) }
        }

        try {
            val moteur = WhisperStt(magasin)
            val resultat = runBlocking {
                moteur.initialize(modele!!)
                try {
                    moteur.transcribeFile(wav.absolutePath)
                } finally {
                    moteur.dispose()
                }
            }

            // 🔴 Le cœur du test : du texte, pas du vide. C'est exactement la distinction que
            // `IssueDeDictee.Silence` ne permet pas de faire depuis l'écran.
            assertThat(resultat.isEmpty).isFalse()

            // ⚠️ Un seul mot, en minuscules et sans ponctuation : assez pour prouver que c'est bien
            // CE son qui a été transcrit, assez peu pour ne pas casser au premier changement de
            // modèle ou de casse. Un test qui exigerait la phrase entière mesurerait le modèle, pas
            // le câblage.
            assertThat(resultat.text.lowercase()).contains("country")
        } finally {
            wav.delete()
        }
    }

    /**
     * 🔴 **A cancelled transcription stops the engine, it does not run to its end** — the relay the
     * security audit of 2026-09-26 found dead (note of cell 4): it was set on the job running the
     * native call, and a cancelled job completes only once that call returns.
     *
     * Sixteen times `jfk.wav`, about three minutes of sound. Measured on the S9 with eight (88 s): the
     * dead relay gave control back after 22.7 s — the whole transcription; the live one after 7.2 s —
     * the engine checks its stop flag between its passes, so the pass under way finishes first. The
     * bound, [ARRET_MAX_MS], sits between the two, and the longer sound widens the gap.
     */
    @Test
    fun une_transcription_annulee_arrete_le_moteur() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cible = instrumentation.targetContext
        val magasin = SttModelStore(cible)
        val modele: SttModel? = SttModelCatalogue.tous.firstOrNull { magasin.estPresent(it) }
        assumeTrue("aucun modele installe sur cet appareil", modele != null)

        val jfk = instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }
        val pcm = WavPcm16.echantillons(jfk).let { valeurs ->
            ByteArray(valeurs.size * 2).also { octets ->
                valeurs.forEachIndexed { i, v ->
                    val s = (v * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    octets[2 * i] = (s and 0xFF).toByte()
                    octets[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                }
            }
        }
        val donnees = ByteArray(pcm.size * REPETITIONS)
        repeat(REPETITIONS) { pcm.copyInto(donnees, it * pcm.size) }
        val wav = File(cible.cacheDir, "long-test.wav")
        wav.writeBytes(WavPcm16.entete(donnees.size.toLong()) + donnees)

        try {
            val moteur = WhisperStt(magasin)
            runBlocking {
                moteur.initialize(modele!!)
                try {
                    val travail = async(Dispatchers.Default) { moteur.transcribeFile(wav.absolutePath) }
                    delay(AVANT_ANNULATION_MS)
                    val debut = SystemClock.elapsedRealtime()
                    travail.cancel()
                    travail.join()
                    val attente = SystemClock.elapsedRealtime() - debut

                    assertThat(travail.isCancelled).isTrue()
                    assertThat(attente).isLessThan(ARRET_MAX_MS)
                } finally {
                    moteur.dispose()
                }
            }
        } finally {
            wav.delete()
        }
    }

    private companion object {
        const val REPETITIONS = 16
        const val AVANT_ANNULATION_MS = 1_500L
        const val ARRET_MAX_MS = 12_000L
    }
}
