package com.filestech.notes_tech.data.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.domain.voice.SttModel
import com.filestech.notes_tech.domain.voice.SttModelCatalogue
import com.google.common.truth.Truth.assertThat
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
}
