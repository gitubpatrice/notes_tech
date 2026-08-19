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
 * le système de fichiers et la base. Cette partie-là se mesure sur appareil.
 *
 * ⚠️⚠️ **Ce paragraphe a lui-même vieilli, et il fallait le relire pour s'en apercevoir.** Il
 * datait cette vérification du 2026-08-14 et l'énumérait avec assurance — mot-clé faux refusé, base
 * et annexes disparues, clé détruite, préférences vidées hors liste blanche, écran de fin affiché,
 * relance sur une base vierge. Tout cela a bien eu lieu ce jour-là. Mais **trois des treize étapes
 * sont arrivées après** : le déplacement du clair juste derrière la clé le 2026-08-15, puis les
 * trois étapes vocales le 2026-08-16. La séquence décrite n'existait donc plus, et la phrase
 * continuait de rassurer sur elle. *Une date ne périme pas une mesure ; c'est l'objet mesuré qui
 * change sous elle.*
 *
 * ⚠️ **Le jumeau publié ne vérifie PAS la même chose**, contrairement à ce qui était écrit ici.
 * `test/panic_service_test.dart` compte huit cas contre quinze, et le `PanicReport` de
 * l'application publiée n'a ni `minimalGuarantee` ni mesure du clair : elle traite tout échec
 * d'étape comme une panique incomplète, cache de nettoyage compris. Les deux fichiers ne peuvent
 * pas « rester d'accord » — l'un couvre un domaine que l'autre n'a pas. Ce qui doit rester vrai est
 * plus étroit : **aucune étape déclarée d'un côté ne doit manquer de l'autre sans raison écrite**.
 *
 * ⚠️ Ce que ces cas ne peuvent pas voir non plus : **ce que les écrans affichent**. Quatre défauts y
 * ont vécu sous cette suite verte jusqu'au 2026-08-19 ; ils sont mesurés depuis par
 * `androidTest/.../ui/panic/PanicEcransTest.kt`. Un test qui prouve la branche ne dit rien de la
 * phrase.
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
            PanicStep.VOICE_MODEL_WIPE,
            PanicStep.PREFS_CLEAR,
            PanicStep.EXPORTS_WIPE,
            PanicStep.VOICE_CAPTURES_WIPE,
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
     * Ce test fige la liste. Il a échoué comme prévu à chacun des trois ajouts de la dictée —
     * `VOICE_CAPTURES_WIPE` avec la capture, puis `VOICE_CANCEL` et `VOICE_MODEL_WIPE` avec l'import
     * du modèle, le 2026-08-16 — et c'est **son rôle** : l'échec force à vérifier que les étapes
     * ajoutées s'exécutent vraiment avant de les inscrire ici.
     */
    @Test
    @DisplayName("la séquence est exactement celle qu'on croit — treize étapes, dans cet ordre")
    fun sequenceFigee() {
        assertThat(PanicStep.entries).containsExactly(
            PanicStep.FORCE_SECURE_WINDOW,
            PanicStep.VOICE_CANCEL,
            PanicStep.CLIPBOARD_CLEAR,
            PanicStep.FOLDERS_LOCK_ALL,
            PanicStep.PIN_KEYS_WIPE,
            PanicStep.KEK_DESTROY,
            PanicStep.EXPORTS_WIPE,
            PanicStep.VOICE_CAPTURES_WIPE,
            PanicStep.DB_WIPE,
            PanicStep.VOICE_MODEL_WIPE,
            PanicStep.LEGACY_MODELS_WIPE,
            PanicStep.PREFS_CLEAR,
            PanicStep.CACHE_PURGE,
        ).inOrder()
    }

    /**
     * 🔴 Le clair part **immédiatement** apres la cle, avant tous les effacements lourds.
     *
     * Les archives d'export sont les seuls fichiers en clair de l'application. Une fois la cle
     * detruite, tout ce qui reste ailleurs est du bruit : les laisser passer devant revient a faire
     * attendre du lisible derriere de l'illisible. Le pire etait l'ordre relatif aux modeles
     * herites, dont la suppression peut prendre plusieurs secondes sur 530 Mo.
     *
     * ⚠️ Ce test ne verifie pas une preference d'ecriture : il verifie qu'une interruption entre le
     * point de non-retour et la fin de sequence ne laisse plus de notes lisibles sur l'appareil.
     */
    @Test
    @DisplayName("TOUT le clair part juste apres la cle, avant les effacements lourds")
    fun clairJusteApresLaCle() {
        // ⚠️ Les DEUX repertoires de clair, pas seulement les archives : un enregistrement de
        // dictee porte la voix de l'utilisateur, donc le contenu de sa note.
        val clairs = listOf(PanicStep.EXPORTS_WIPE, PanicStep.VOICE_CAPTURES_WIPE)
        val lourds = listOf(
            PanicStep.DB_WIPE,
            // ⚠️ Le modele de dictee pese plusieurs dizaines de mega-octets et ne contient rien
            //    de l'utilisateur : il doit passer APRES le clair, comme les modeles herites.
            PanicStep.VOICE_MODEL_WIPE,
            PanicStep.LEGACY_MODELS_WIPE,
            PanicStep.PREFS_CLEAR,
        )

        for (clair in clairs) {
            assertThat(clair.ordinal).isGreaterThan(PanicStep.KEK_DESTROY.ordinal)
            for (lourd in lourds) assertThat(clair.ordinal).isLessThan(lourd.ordinal)
        }
        // Rien ne s'intercale entre la cle et le clair.
        assertThat(clairs.map { it.ordinal }.sorted())
            .containsExactly(PanicStep.KEK_DESTROY.ordinal + 1, PanicStep.KEK_DESTROY.ordinal + 2)
            .inOrder()
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
        //
        // ⚠️⚠️ **Cette phrase a été fausse de la production pendant tout le temps où elle était
        // écrite ici.** `PanicOverlay` branchait sur `isComplete` seul : un test juste, dont le
        // commentaire décrivait un écran qui n'existait pas. Corrigé le 2026-08-15, après que les
        // deux relectures externes et l'audit de cohérence l'ont relevé chacun de leur côté.
        //
        // La leçon vaut plus que le défaut : un commentaire de test qui affirme quelque chose de la
        // production ne le vérifie pas. Il le **cache**, en donnant à lire une garantie là où il n'y
        // a qu'une intention.
        assertThat(bilan.isComplete).isTrue()
    }

    /**
     * 🔴 Le residu se MESURE, il ne se deduit pas d'une etape ratee.
     *
     * Le repertoire d'export encore la a la fin de la sequence, quelle que soit l'etape qui a
     * echoue : c'est ca, du clair sur le disque.
     */
    @Test
    @DisplayName("un repertoire de clair encore present le signale, meme sans etape ratee")
    fun clairRestantEstSignale() {
        val bilan = PanicReport(
            PanicStep.entries.map { PanicOutcome(it) },
            clairSurLeDisque = true,
        )

        assertThat(bilan.minimalGuarantee).isTrue()
        assertThat(bilan.isComplete).isTrue()
        assertThat(bilan.clairPeutSubsister).isTrue()
    }

    /**
     * ⚠️ Le symetrique, et c'est lui qui justifie la mesure : `EXPORTS_WIPE` peut echouer et
     * `CACHE_PURGE` emporter quand meme le repertoire — elle traite `exports` comme un artefact
     * sensible. Se fier a l'issue de l'etape aurait annonce du clair la ou il n'y en a plus.
     */
    @Test
    @DisplayName("export rate mais repertoire parti : aucun clair annonce")
    fun exportRateMaisRepertoireParti() {
        val bilan = rapport(PanicStep.EXPORTS_WIPE)

        assertThat(bilan.isComplete).isFalse()
        assertThat(bilan.clairPeutSubsister).isFalse()
    }

    /**
     * 🔴 **Le presse-papiers est du clair lui aussi**, et il manquait.
     *
     * Une note copiee y attend en clair, lisible par toute application au premier plan. Le KDoc de
     * `clairPeutSubsister` affirmait pourtant que l'export etait « la seule » etape dont l'echec
     * laisse du lisible. Il ne se relit pas — Android refuse la lecture sans focus — donc c'est
     * l'issue de son etape qui fait foi.
     */
    @Test
    @DisplayName("un effacement de presse-papiers rate signale du CLAIR")
    fun pressePapiersRateSignaleDuClair() {
        val bilan = rapport(PanicStep.CLIPBOARD_CLEAR)

        assertThat(bilan.minimalGuarantee).isTrue()
        assertThat(bilan.clairPeutSubsister).isTrue()
    }

    /** ⚠️ Le jumeau : un nettoyage rate AILLEURS ne laisse que du bruit, et ne doit pas alarmer. */
    @Test
    @DisplayName("un autre nettoyage rate ne signale PAS de clair")
    fun autreNettoyageRateNeSignalePasDeClair() {
        for (etape in listOf(
            PanicStep.CACHE_PURGE,
            PanicStep.DB_WIPE,
            PanicStep.LEGACY_MODELS_WIPE,
            // ⚠️ Un modele non efface n'est PAS du clair : c'est un binaire public.
            PanicStep.VOICE_MODEL_WIPE,
        )) {
            val bilan = rapport(etape)
            assertThat(bilan.clairPeutSubsister).isFalse()
        }
    }

    /** Une sequence entierement reussie, disque propre : aucun clair. */
    @Test
    @DisplayName("aucune etape ratee et aucun export sur le disque : aucun clair signale")
    fun aucunEchecAucunClair() {
        assertThat(rapport().clairPeutSubsister).isFalse()
    }

    @Test
    @DisplayName("le rapport ne retient que le TYPE de l'échec, jamais un chemin ni un secret")
    fun echecSansDetail() {
        val issue = PanicOutcome(PanicStep.DB_WIPE, failure = "IllegalStateException")

        assertThat(issue.succeeded).isFalse()
        assertThat(issue.failure).isEqualTo("IllegalStateException")
    }
}
