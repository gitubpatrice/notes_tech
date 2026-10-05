package com.filestech.notes_tech.data.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.domain.voice.SttPermissionDeniedException
import com.filestech.notes_tech.domain.voice.SttRecordingFailedException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **L'interdiction du mode panique, l'attente d'arrêt, et le répertoire que la panique surveille.**
 *
 * Dernière ligne de `docs/05-PARITE.md` : `voice_service.dart`. `WhisperStt` et `SttModelStore` ont
 * 15 cas instrumentés et 12 JVM ; `VoiceCapture`, **601 lignes**, n'était effleuré que par
 * `BorneDeDureeTest` — trois cas JVM sur la seule fonction pure du fichier.
 *
 * ## 🔴 Ce qui n'était pas mesuré, et qui appartient au mode panique
 *
 * `couperEtInterdire()` pose un **état**, pas un drapeau de geste : rien ne l'efface. Le KDoc
 * explique pourquoi — `arreter()` seul est remis à zéro par `enregistrer()`, si bien qu'une capture
 * qui attend le verrou repartirait **après** l'étape de panique censée couper le micro.
 *
 * Personne ne vérifiait que l'interdiction tienne, ni qu'elle réponde par un **échec** plutôt que
 * par `null`. La distinction est écrite dans le code et elle compte : `null` dit « vous n'avez rien
 * dit », l'exception dit « le système a été mis à l'arrêt ». Un appelant qui les confondrait
 * afficherait « aucun son détecté » après une panique.
 *
 * ## ⚠️ Pourquoi un contexte à permission refusée, plutôt que l'état réel de l'appareil
 *
 * Le contrôle négatif doit prouver qu'une instance **non interdite** dépasse la garde. Le faire avec
 * la vraie permission ouvrirait le micro du S9 pour de bon. Un `ContextWrapper` qui refuse
 * `RECORD_AUDIO` rend le cas **déterministe** et sans effet de bord — et il mesure du même coup
 * **l'ordre des deux gardes** : l'interdiction est lue avant la permission, donc une application
 * paniquée ne réclame pas un droit qu'elle refuserait d'utiliser.
 *
 * ## ⚠️ Ce que ces cas NE prouvent pas
 *
 * Qu'un enregistrement réel s'arrête. Cela demande un micro et du son ; c'est le domaine de
 * `TranscriptionSurAppareilTest`, et de la vérification à la main sur appareil.
 */
@RunWith(AndroidJUnit4::class)
class CaptureVocaleTest {

    private lateinit var contexte: Context
    private lateinit var horloge: Clock

    @Before
    fun setUp() {
        contexte = InstrumentationRegistry.getInstrumentation().targetContext
        horloge = HorlogeFigee(Instant.ofEpochMilli(1_760_000_000_000L))
        VoiceCapture.purgerLesCaptures(contexte)
    }

    @After
    fun tearDown() {
        // ⚠️ Ce répertoire porte de la voix en clair. Le mode panique compte dessus, et un test qui
        // en laisserait ferait échouer une mesure de panique sans rapport.
        VoiceCapture.purgerLesCaptures(contexte)
    }

    private fun capture(permission: Boolean) = VoiceCapture(ContexteDePermission(contexte, permission), horloge)

    // ── L'interdiction du mode panique ───────────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Après `couperEtInterdire`, enregistrer ÉCHOUE — et ne rend pas `null`.**
     *
     * ⚠️ Le cas exige le **type** et non seulement l'échec : `SttPermissionDeniedException` serait
     * un échec lui aussi, et dirait tout autre chose à l'utilisateur.
     */
    @Test
    fun couper_et_interdire_fait_ECHOUER_les_captures_suivantes() = runBlocking {
        val capture = capture(permission = false)
        capture.couperEtInterdire()

        var levee: Throwable? = null
        try {
            capture.enregistrer()
        } catch (e: Throwable) {
            levee = e
        }

        assertThat(levee).isInstanceOf(SttRecordingFailedException::class.java)
    }

    /**
     * ⚠️⚠️ **Le contrôle négatif, et il mesure aussi l'ORDRE des deux gardes.**
     *
     * Sans interdiction, la même instance atteint le contrôle de permission — donc la garde de
     * panique n'est pas un refus permanent posé par erreur. Et comme la permission est refusée par
     * construction, l'exception qui sort dit **laquelle** des deux gardes a parlé : l'interdiction
     * est lue **avant** la permission.
     */
    @Test
    fun sans_interdiction_la_capture_atteint_le_controle_de_permission() = runBlocking {
        val capture = capture(permission = false)

        var levee: Throwable? = null
        try {
            capture.enregistrer()
        } catch (e: Throwable) {
            levee = e
        }

        assertThat(levee).isInstanceOf(SttPermissionDeniedException::class.java)
    }

    /**
     * 🔴 **`arreter()` seul n'interdit RIEN**, et c'est toute la raison d'être de `couperEtInterdire`.
     *
     * Le KDoc le dit : `arretDemande` est remis à zéro par `enregistrer()`, avant le verrou. Une
     * capture qui a franchi cette ligne repartirait après l'étape de panique. Ce cas fige la
     * différence entre les deux gestes — sans lui, « simplifier » l'un en l'autre passerait.
     */
    @Test
    fun arreter_seul_n_interdit_pas_la_capture_suivante() = runBlocking {
        val capture = capture(permission = false)
        capture.arreter()

        var levee: Throwable? = null
        try {
            capture.enregistrer()
        } catch (e: Throwable) {
            levee = e
        }

        // La permission, donc la garde de panique ne s'est pas déclenchée.
        assertThat(levee).isInstanceOf(SttPermissionDeniedException::class.java)
    }

    /** ⚠️ L'interdiction est un **état** : rien ne l'efface, pas même une demande d'enregistrer. */
    @Test
    fun l_interdiction_survit_a_une_seconde_demande() = runBlocking {
        val capture = capture(permission = false)
        capture.couperEtInterdire()

        repeat(3) {
            var levee: Throwable? = null
            try {
                capture.enregistrer()
            } catch (e: Throwable) {
                levee = e
            }
            assertThat(levee).isInstanceOf(SttRecordingFailedException::class.java)
        }
    }

    // ── L'attente d'arrêt ────────────────────────────────────────────────────────────────────────

    /**
     * ⚠️ Une capture **déjà arrêtée** rend la main tout de suite, sans consommer le délai.
     *
     * Le `StateFlow` émet sa valeur courante à la souscription : c'est le cas ordinaire du mode
     * panique, où l'attente ne doit rien retarder. Une implémentation qui attendrait une
     * *transition* consommerait les cinq secondes puis rendrait `false`.
     *
     * ⚠️⚠️ Le cas mesure **les deux** : la valeur rendue et le temps écoulé. La valeur seule ne
     * distinguerait pas « rend vrai tout de suite » de « rend vrai au bout de cinq secondes ».
     */
    @Test
    fun attendre_l_arret_d_une_capture_deja_arretee_rend_vrai_sans_attendre() = runBlocking {
        val capture = capture(permission = false)

        val avant = SystemClock.elapsedRealtime()
        val arretee = capture.attendreLArret(5_000)
        val ecoule = SystemClock.elapsedRealtime() - avant

        assertThat(arretee).isTrue()
        assertThat(ecoule).isLessThan(1_000)
    }

    /**
     * ⚠️⚠️ **Un délai de zéro rend `false`, même sur une capture arrêtée — et c'est kotlinx.**
     *
     * `withTimeoutOrNull(0)` rend `null` **sans jamais exécuter son bloc** : la souscription au
     * `StateFlow` n'a pas lieu, donc la valeur courante n'est jamais lue. Aucun appelant du portage
     * ne passe zéro — `PanicService` passe une constante — et ce cas est là pour que ça reste vrai.
     *
     * Le jour où quelqu'un calculera un *reliquat* de budget et le passera ici, une capture pourtant
     * arrêtée sera annoncée « encore en cours », et la panique croira écrire sous un enregistrement
     * qui n'existe plus. Le défaut ne serait pas dans cette fonction ; il serait chez l'appelant, et
     * il est plus facile à voir écrit ici qu'à retrouver là-bas.
     */
    @Test
    fun un_delai_de_zero_rend_faux_et_c_est_une_propriete_de_kotlinx() = runBlocking {
        val capture = capture(permission = false)

        assertThat(capture.attendreLArret(0)).isFalse()
    }

    // ── Le répertoire que la panique surveille ───────────────────────────────────────────────────

    /**
     * 🔴 **Un seul chemin, et c'est celui que le mode panique efface.**
     *
     * `PanicService` appelle `VoiceCapture.repertoireDeCapture` plutôt que de recomposer le chemin :
     * deux définitions, et la panique nettoierait un dossier que la capture n'utilise plus. Ce cas
     * fige le contrat des deux côtés — l'emplacement **et** le fait que la purge l'emporte.
     */
    @Test
    fun la_purge_efface_le_repertoire_que_la_panique_surveille() {
        val racine = VoiceCapture.repertoireDeCapture(contexte)
        assertThat(racine).isEqualTo(File(contexte.cacheDir, "captures"))

        racine.mkdirs()
        File(racine, "dictee.wav").writeText("RIFF")
        assertThat(racine.exists()).isTrue()

        VoiceCapture.purgerLesCaptures(contexte)

        assertThat(racine.exists()).isFalse()
    }

    /** Un contexte dont la réponse à `RECORD_AUDIO` est décidée par le test. */
    private class ContexteDePermission(base: Context, private val accordee: Boolean) : ContextWrapper(base) {
        override fun checkSelfPermission(permission: String): Int =
            if (accordee) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (accordee) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    private class HorlogeFigee(private val maintenant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = maintenant
    }
}
