package com.filestech.notes_tech.security.panic

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * L'ordre des étapes et l'honnêteté du rapport.
 *
 * ## Ce que ces tests couvrent, et ce qu'ils ne couvrent pas
 *
 * Ils portent sur les deux parties **pures** du mode panique : la séquence déclarée par
 * [PanicStep], et ce que [PanicReport] conclut d'une liste d'issues. Ce sont aussi les deux parties
 * dont une régression serait invisible — un ordre modifié compile, un rapport optimiste s'affiche.
 *
 * ⚠️ Ils ne couvrent **pas** l'exécution de la séquence, qui touche le presse-papiers, le Keystore,
 * le système de fichiers et la base. Cette partie-là a été vérifiée **sur le S9**, de bout en bout,
 * le 2026-08-14 : mot-clé faux refusé, base et annexes disparues, clé détruite et vérifiée,
 * préférences vidées hors liste blanche, écran de fin affiché, processus mort à la fermeture,
 * relance sur une base vierge. Le dire ici plutôt que de laisser croire à une couverture complète.
 *
 * L'application publiée a le jumeau de ce fichier — `test/panic_service_test.dart` — et il vérifie
 * exactement la même chose. Les deux doivent rester d'accord.
 */
class PanicReportTest {

    // ── L'ordre EST la conception ────────────────────────────────────────────

    /**
     * 🔴 La règle qui porte toute la sûreté de la séquence.
     *
     * La clé est détruite **avant** les effacements lourds, pour qu'une interruption à n'importe
     * quel instant — extinction, batterie morte, processus tué par quelqu'un qui a compris ce qui
     * se passe — laisse l'état le plus sûr atteignable. L'ordre inverse, qui paraît naturel,
     * ouvrirait une fenêtre de plusieurs secondes pendant laquelle la base reste déchiffrable.
     *
     * L'application publiée avait cet ordre-là, et un audit l'a fait corriger.
     */
    @Test
    @DisplayName("la clé est détruite AVANT tout effacement de fichier")
    fun cleAvantLesFichiers() {
        val cle = PanicStep.KEK_DESTROY.ordinal
        for (effacement in listOf(
            PanicStep.DB_WIPE,
            PanicStep.LEGACY_MODELS_WIPE,
            PanicStep.PREFS_CLEAR,
            PanicStep.EXPORTS_WIPE,
            PanicStep.CACHE_PURGE,
        )) {
            assertThat(effacement.ordinal).isGreaterThan(cle)
        }
    }

    /**
     * Les clés du Keystore ne dépendent pas de la base : rien n'oblige à les traiter après, et un
     * attaquant qui aurait déjà copié la base ailleurs ne doit pas conserver le moyen de rejouer un
     * coffre à code sur un appareil restauré.
     */
    @Test
    @DisplayName("les clés de coffre partent avant la clé de la base")
    fun clesDeCoffreAvantLaCle() {
        assertThat(PanicStep.PIN_KEYS_WIPE.ordinal).isLessThan(PanicStep.KEK_DESTROY.ordinal)
    }

    /**
     * ⚠️ Les clés des coffres ouverts sont effacées de la **mémoire vive** avant qu'on touche au
     * Keystore. Sans cela, une panique déclenchée coffre ouvert laisse sa clé en RAM pendant toute
     * la séquence.
     */
    @Test
    @DisplayName("les coffres en mémoire sont verrouillés avant le Keystore")
    fun memoireAvantKeystore() {
        assertThat(PanicStep.FOLDERS_LOCK_ALL.ordinal).isLessThan(PanicStep.PIN_KEYS_WIPE.ordinal)
    }

    @Test
    @DisplayName("le drapeau de fenêtre protégée est la toute première étape")
    fun drapeauEnPremier() {
        assertThat(PanicStep.entries.first()).isEqualTo(PanicStep.FORCE_SECURE_WINDOW)
    }

    /**
     * Une note copiée est en clair dans le presse-papiers, et lisible par toute application au
     * premier plan. La panique n'attend pas son expiration ordinaire.
     */
    @Test
    @DisplayName("le presse-papiers est vidé tôt, avant tout travail long")
    fun pressePapiersTot() {
        assertThat(PanicStep.CLIPBOARD_CLEAR.ordinal).isLessThan(PanicStep.KEK_DESTROY.ordinal)
    }

    /**
     * ⚠️ **Une étape déclarée doit s'exécuter.** Une constante qui ne correspond à aucun geste rend
     * le rapport menteur — c'est l'erreur que l'application publiée a commise, en gardant une étape
     * `gemmaUninstall` qui passait par un service supprimé et ne tournait donc plus jamais.
     *
     * Ce test fige la liste. Il échouera à l'ajout de la dictée vocale en phase 7, et c'est
     * **voulu** : ce jour-là, il faudra vérifier que les étapes ajoutées s'exécutent vraiment.
     */
    @Test
    @DisplayName("la séquence est exactement celle qu'on croit — dix étapes, dans cet ordre")
    fun sequenceFigee() {
        assertThat(PanicStep.entries).containsExactly(
            PanicStep.FORCE_SECURE_WINDOW,
            PanicStep.CLIPBOARD_CLEAR,
            PanicStep.FOLDERS_LOCK_ALL,
            PanicStep.PIN_KEYS_WIPE,
            PanicStep.KEK_DESTROY,
            PanicStep.DB_WIPE,
            PanicStep.LEGACY_MODELS_WIPE,
            PanicStep.PREFS_CLEAR,
            PanicStep.EXPORTS_WIPE,
            PanicStep.CACHE_PURGE,
        ).inOrder()
    }

    // ── Le rapport ne doit jamais mentir ─────────────────────────────────────

    private fun rapport(vararg echecs: PanicStep) = PanicReport(
        PanicStep.entries.map { PanicOutcome(it, failure = if (it in echecs) "Simule" else null) },
    )

    @Test
    @DisplayName("toutes les étapes réussies : complet, et la garantie minimale est acquise")
    fun toutReussi() {
        val bilan = rapport()

        assertThat(bilan.isComplete).isTrue()
        assertThat(bilan.minimalGuarantee).isTrue()
        assertThat(bilan.failedSteps).isEmpty()
    }

    /**
     * 🔴 **Les deux questions ne sont pas la même.**
     *
     * « Tout s'est-il bien passé ? » n'est pas « suis-je protégé ? ». Un nettoyage de cache qui
     * échoue ne change rien à la protection : la base est déjà du bruit.
     */
    @Test
    @DisplayName("une étape de nettoyage en échec n'entame PAS la garantie minimale")
    fun nettoyageEnEchec() {
        val bilan = rapport(PanicStep.CACHE_PURGE)

        assertThat(bilan.isComplete).isFalse()
        assertThat(bilan.minimalGuarantee).isTrue()
        assertThat(bilan.failedSteps).containsExactly(PanicStep.CACHE_PURGE)
    }

    /**
     * 🔴 **Le seul cas où l'écran de fin ne doit rien adoucir.**
     *
     * Si la destruction de la clé a échoué, dix étapes réussies ne protègent rien : les notes
     * restent déchiffrables par qui possède l'appareil, et quelqu'un est peut-être sur le point de
     * s'en séparer.
     */
    @Test
    @DisplayName("la clé non détruite retire la garantie, quoi que fasse le reste")
    fun cleNonDetruite() {
        val bilan = rapport(PanicStep.KEK_DESTROY)

        assertThat(bilan.minimalGuarantee).isFalse()
        assertThat(bilan.isComplete).isFalse()
    }

    /**
     * ⚠️ Un rapport auquel il **manque** l'étape de destruction ne doit pas se lire comme un succès.
     *
     * Le cas se produirait si la séquence était interrompue avant d'y arriver. Sans ce test, une
     * implémentation de `minimalGuarantee` écrite avec `none { … !succeeded }` rendrait `true` sur
     * une liste vide — un rapport vide passerait pour une destruction accomplie.
     */
    @Test
    @DisplayName("une séquence interrompue avant la destruction n'a AUCUNE garantie")
    fun sequenceInterrompue() {
        val bilan = PanicReport(
            listOf(
                PanicOutcome(PanicStep.FORCE_SECURE_WINDOW),
                PanicOutcome(PanicStep.CLIPBOARD_CLEAR),
                PanicOutcome(PanicStep.FOLDERS_LOCK_ALL),
            ),
        )

        assertThat(bilan.minimalGuarantee).isFalse()
        // ⚠️ `isComplete` est vrai — les trois étapes présentes ont abouti. C'est bien pour cela
        // que l'écran de fin s'appuie sur `minimalGuarantee` et non sur `isComplete`.
        assertThat(bilan.isComplete).isTrue()
    }

    @Test
    @DisplayName("le rapport ne retient que le TYPE de l'échec, jamais un chemin ni un secret")
    fun echecSansDetail() {
        val issue = PanicOutcome(PanicStep.DB_WIPE, failure = "IllegalStateException")

        assertThat(issue.succeeded).isFalse()
        assertThat(issue.failure).isEqualTo("IllegalStateException")
    }
}
