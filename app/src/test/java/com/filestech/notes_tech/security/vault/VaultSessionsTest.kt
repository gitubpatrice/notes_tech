package com.filestech.notes_tech.security.vault

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le verrouillage automatique et le freinage après échec, sur une horloge que le test pilote.
 *
 * ## ⚠️ L'horloge est injectée **des deux côtés**
 *
 * Poser une échéance avec l'horloge réelle puis la consulter avec une horloge de test — ou l'inverse
 * — donne des délais absurdes et des tests verts pour la mauvaise raison. Le portefeuille l'a déjà
 * payé : quatre tests d'un autre projet passaient sur un délai négatif. Ici [HorlogeDeTest] est la
 * seule source de temps, et rien dans [VaultSessions] n'en lit une autre.
 */
class VaultSessionsTest {

    private class HorlogeDeTest(var millis: Long = 0) : MonotonicClock {
        override fun elapsedMillis(): Long = millis

        fun avance(de: Long) {
            millis += de
        }
    }

    private val horloge = HorlogeDeTest()
    private val sessions = VaultSessions(horloge).apply { autoLockMillis = 60_000 }

    private fun cle(remplissage: Byte) = ByteArray(VaultParams.FOLDER_KEY_BYTES) { remplissage }

    @Test
    @DisplayName("une session inactive au-delà du délai n'est plus servie, même sans balayage")
    fun uneSessionPerimeeNEstPlusServie() {
        sessions.open("coffre", cle(1))
        horloge.avance(59_999)
        assertThat(sessions.sessionKey("coffre")).isNotNull()

        horloge.avance(60_000)

        // Personne n'a balayé entre-temps : c'est la lecture elle-même qui doit refuser. Sans ce
        // contrôle, une clé expirée resterait utilisable jusqu'au prochain réveil du balayage.
        assertThat(sessions.sessionKey("coffre")).isNull()
    }

    @Test
    @DisplayName("la clé d'une session périmée est effacée, pas seulement oubliée")
    fun laClePerimeeEstEffacee() {
        val cle = cle(7)
        sessions.open("coffre", cle)
        horloge.avance(60_000)

        sessions.sessionKey("coffre")

        // Oublier la référence laisserait la clé dans le tas jusqu'au ramasse-miettes. Le tableau
        // que l'appelant avait fourni doit être à zéro.
        assertThat(cle.toList()).containsExactlyElementsIn(ByteArray(cle.size).toList())
    }

    @Test
    @DisplayName("consulter une session repousse son échéance")
    fun consulterRepousseLEcheance() {
        sessions.open("coffre", cle(1))
        repeat(5) {
            horloge.avance(30_000)
            assertThat(sessions.sessionKey("coffre")).isNotNull()
        }
        // Deux minutes et demie se sont écoulées pour un délai d'une minute : sans le report,
        // le coffre serait fermé depuis longtemps.
        assertThat(sessions.isUnlocked("coffre")).isTrue()
    }

    @Test
    @DisplayName("l'activité sur un coffre ne repousse PAS l'échéance de l'autre")
    fun lActiviteSurUnCoffreNAffameParLAutre() {
        // 🔴 La famine que l'application publiée a connue : un seul réveil pour tous les coffres,
        // réarmé au délai complet à chaque activité. Avec deux coffres ouverts et de l'activité
        // régulière sur le premier, le second ne se verrouillait jamais.
        sessions.open("actif", cle(1))
        sessions.open("oublie", cle(2))

        // L'échéance annoncée suit le coffre le PLUS PROCHE de l'expiration — donc « oublie », qui
        // n'est jamais touché — et décroît à chaque tour. La réarmer au délai complet à chaque
        // activité est exactement ce qui produisait la famine.
        horloge.avance(20_000)
        sessions.touch("actif")
        assertThat(sessions.nextDeadlineMillis()).isEqualTo(40_000)

        horloge.avance(20_000)
        sessions.touch("actif")
        assertThat(sessions.nextDeadlineMillis()).isEqualTo(20_000)

        horloge.avance(20_000)
        sessions.touch("actif")

        sessions.sweep()

        assertThat(sessions.isUnlocked("actif")).isTrue()
        assertThat(sessions.isUnlocked("oublie")).isFalse()
    }

    @Test
    @DisplayName("un déverrouillage en cours ne fait pas taire le planificateur")
    fun unDeverrouillageEnCoursNArretePasLePlanificateur() {
        // 🔴 Le défaut que ce test ferme, trouvé sur mon propre code : la session en cours
        // d'ouverture était exclue **du calcul d'échéance** autant que du verrouillage. Seule
        // session ouverte, elle faisait rendre `null` — « plus rien à surveiller ». Le
        // planificateur s'arrêtait, l'ouverture se terminait, et plus personne ne rappelait le
        // balayage : la clé restait en mémoire indéfiniment.
        sessions.open("coffre", cle(1))

        kotlinx.coroutines.runBlocking {
            sessions.whileUnlocking("coffre") {
                assertThat(sessions.sweep()).isNotNull()
                assertThat(sessions.nextDeadlineMillis()).isNotNull()
            }
        }
    }

    @Test
    @DisplayName("le balayage n'annonce plus d'échéance quand tout est fermé")
    fun leBalayageSeTaitQuandToutEstFerme() {
        sessions.open("coffre", cle(1))
        horloge.avance(60_000)

        assertThat(sessions.sweep()).isNull()
        assertThat(sessions.unlockedFolderIds.value).isEmpty()
    }

    @Test
    @DisplayName("un déverrouillage en cours protège sa session du balayage")
    fun unDeverrouillageEnCoursEstProtege() {
        // La dérivation Argon2id dure de l'ordre de la seconde sur un appareil ancien. Une échéance
        // qui tombe pendant ce calcul ne doit pas effacer la clé entre sa pose et son usage.
        sessions.open("coffre", cle(1))
        horloge.avance(60_000)

        kotlinx.coroutines.runBlocking {
            sessions.whileUnlocking("coffre") {
                sessions.sweep()
                assertThat(sessions.unlockedFolderIds.value).contains("coffre")
            }
        }

        sessions.sweep()
        assertThat(sessions.unlockedFolderIds.value).doesNotContain("coffre")
    }

    @Test
    @DisplayName("un délai nul désactive le verrouillage automatique")
    fun unDelaiNulDesactiveLeVerrouillage() {
        sessions.autoLockMillis = 0
        sessions.open("coffre", cle(1))
        horloge.avance(Long.MAX_VALUE / 2)

        assertThat(sessions.sessionKey("coffre")).isNotNull()
        assertThat(sessions.sweep()).isNull()
    }

    @Test
    @DisplayName("le freinage suit 1, 2, 4, 8, 16 puis 30 secondes")
    fun leFreinageCroitPuisSePlafonne() {
        val attendus = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        for ((index, attendu) in attendus.withIndex()) {
            assertThat(sessions.recordFailure("coffre")).isEqualTo(index + 1)
            assertThat(sessions.lockoutRemainingMillis("coffre")).isEqualTo(attendu)
            horloge.avance(attendu)
        }
    }

    @Test
    @DisplayName("une réussite efface le freinage ET le compteur d'échecs")
    fun uneReussiteEffaceLesDeux() {
        // ⚠️ Jumeau asymétrique corrigé côté Flutter : un seul des deux chemins purgeait les deux.
        // Après un échec puis une réussite, verrouiller et rouvrir se heurtait à un freinage
        // fantôme hérité de l'échec précédent.
        sessions.recordFailure("coffre")
        sessions.recordFailure("coffre")

        sessions.clearFailures("coffre")

        assertThat(sessions.lockoutRemainingMillis("coffre")).isEqualTo(0)
        // Et le compteur repart de un, pas de trois : sinon le prochain échec isolé imposerait
        // d'emblée huit secondes.
        assertThat(sessions.recordFailure("coffre")).isEqualTo(1)
    }

    @Test
    @DisplayName("le freinage expire tout seul, sans qu'on ait à le purger")
    fun leFreinageExpireSeul() {
        sessions.recordFailure("coffre")
        horloge.avance(1_000)

        assertThat(sessions.lockoutRemainingMillis("coffre")).isEqualTo(0)
    }

    @Test
    @DisplayName("rouvrir un coffre déjà ouvert efface l'ancienne clé")
    fun rouvrirEffaceLAncienneCle() {
        val ancienne = cle(3)
        sessions.open("coffre", ancienne)
        sessions.open("coffre", cle(4))

        assertThat(ancienne.toList()).containsExactlyElementsIn(ByteArray(ancienne.size).toList())
    }

    @Test
    @DisplayName("tout fermer efface toutes les clés")
    fun toutFermerEffaceTout() {
        val a = cle(5)
        val b = cle(6)
        sessions.open("a", a)
        sessions.open("b", b)

        sessions.lockAll()

        assertThat(a.toList()).containsExactlyElementsIn(ByteArray(a.size).toList())
        assertThat(b.toList()).containsExactlyElementsIn(ByteArray(b.size).toList())
        assertThat(sessions.unlockedFolderIds.value).isEmpty()
    }
}
