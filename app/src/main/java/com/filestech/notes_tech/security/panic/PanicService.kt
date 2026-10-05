package com.filestech.notes_tech.security.panic

import android.content.Context
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.LegacyDatabaseLocation
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.voice.SttModelStore
import com.filestech.notes_tech.data.voice.VoiceCapture
import com.filestech.notes_tech.di.ApplicationScope
import com.filestech.notes_tech.security.applock.AppLockKeystore
import com.filestech.notes_tech.security.applock.AppLockKeystoreUnavailableException
import com.filestech.notes_tech.security.applock.BiometricUnlockKey
import com.filestech.notes_tech.security.clipboard.SensitiveClipboard
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.VaultKeystore
import com.filestech.notes_tech.security.vault.VaultParams
import com.filestech.notes_tech.ui.secure.SecureWindowController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Les étapes de la séquence, **dans l'ordre où elles s'exécutent**.
 *
 * ⚠️ Une étape déclarée ici doit s'exécuter. Une constante qui ne correspond à aucun geste rendrait
 * le rapport menteur — c'est l'erreur que la version publiée a commise puis corrigée, en gardant une
 * étape `gemmaUninstall` qui passait par un service supprimé et ne tournait donc plus jamais.
 *
 * ⚠️⚠️ **Ce commentaire a lui-même failli mentir.** Il annonçait, jusqu'au 2026-08-16, qu'il n'y
 * avait « ni `voiceCancel` ni `voiceWipe` ici », la dictée n'existant pas encore. La règle qu'il
 * énonce a été respectée — les trois étapes vocales sont entrées **avec** le code qui les exerce,
 * une par livraison — mais sa dernière phrase serait devenue fausse au premier de ces ajouts si
 * personne ne l'avait relue. Une règle et l'inventaire du moment ne se rédigent pas ensemble : la
 * première reste vraie, le second se périme.
 */
enum class PanicStep {
    /** Empêche l'aperçu des applications récentes de capturer la confirmation et l'écran de fin. */
    FORCE_SECURE_WINDOW,

    /**
     * Interdit la dictée et fait abandonner la capture en cours, **avant tout le reste**.
     *
     * 🔴 **La seule étape qui arrête une PRODUCTION de clair au lieu d'en effacer.** Tout ce qui
     * suit détruit des choses qui existent déjà ; celle-ci empêche qu'il s'en écrive d'autres. Une
     * capture en cours ajoute de la voix — donc le contenu d'une note — sur le disque à chaque
     * milliseconde, y compris pendant que la séquence s'exécute. Effacer le répertoire des captures
     * sans avoir coupé le micro laisserait le fichier suivant se créer derrière l'effacement.
     *
     * ⚠️ Elle ne fait que **poser l'interdiction**, ce qui est instantané et ne peut pas échouer.
     * L'attente de l'arrêt effectif appartient à [VOICE_CAPTURES_WIPE], seul endroit où elle sert :
     * la placer ici retarderait le presse-papiers du temps d'un tampon audio, pour rien.
     *
     * ⚠️⚠️ **Une interdiction, et pas seulement un arrêt.** Arrêter la capture en cours laissait la
     * suivante démarrer derrière cette étape, la demande d'enregistrement remettant elle-même le
     * drapeau d'arrêt à zéro. Cf. `VoiceCapture.couperEtInterdire`.
     */
    VOICE_CANCEL,

    /** Une note copiée y est en clair. La panique n'attend pas l'expiration ordinaire. */
    CLIPBOARD_CLEAR,

    /** Efface de la mémoire les clés des coffres ouverts, avant de toucher au Keystore. */
    FOLDERS_LOCK_ALL,

    /** Supprime toutes les clés `vault_pin_*`, y compris les orphelines. */
    PIN_KEYS_WIPE,

    /**
     * The app lock's two Keystore keys: the one that makes its PIN verifier checkable, and the one
     * behind the biometric unlock (D-023). Added on 2026-09-24 WITH the lock whose keys it deletes.
     *
     * ⚠️ Next to [PIN_KEYS_WIPE] and for the same reason, BEFORE the database key: the app lock PIN
     * is very likely the PIN of a vault — or of a bank card. Its verifier stays in the preferences
     * until [PREFS_CLEAR], near the end; without its key, that verifier can no longer be checked by
     * anyone, so an interruption in between leaves nothing to brute-force.
     *
     * Not swept with the `vault_pin_` keys: their aliases are deliberately outside that prefix, so
     * that neither sweep can delete, or miss, the other's keys by accident.
     */
    APP_LOCK_KEYS_WIPE,

    /** 🔴 **Point de non-retour.** Après cette étape, la base est du bruit. */
    KEK_DESTROY,

    /**
     * Les archives d'export, qui portent le texte intégral des notes.
     *
     * ⚠️ Le titre de cette étape disait « seuls fichiers en clair de l'application ». C'était vrai
     * à l'écriture, faux depuis l'arrivée de la capture, dont l'étape suivante s'occupe. Relevé par
     * une relecture externe (GPT-5.5, 2026-08-16).
     *
     * ⚠️⚠️ Déplacée ici le 2026-08-15, depuis l'avant-dernière position. L'ordre de cette
     * énumération **est** l'ordre d'exécution — `PanicReportTest.sequenceFigee` le fige — et il la
     * plaçait derrière la base, les modèles hérités et les préférences. Or tout ce qui la précédait
     * ne protège plus rien de lisible une fois la clé détruite : une interruption dans cette fenêtre
     * laissait des notes parfaitement lisibles à côté d'une base réduite à du bruit.
     */
    EXPORTS_WIPE,

    /**
     * Les enregistrements de dictée, **du clair eux aussi**.
     *
     * ⚠️ Un WAV de capture porte la voix de l'utilisateur, donc le contenu de sa note. Il est de la
     * même nature qu'une archive d'export et part au même endroit de la séquence : juste après la
     * clé, avant tout ce qui n'est plus lisible.
     *
     * ⚠️⚠️ Déclarée **avec** la capture qui la produit (`data/voice/VoiceCapture.kt`), pas avant.
     * L'énumération refuse les étapes qui ne s'exécutent pas — c'est l'erreur `gemmaUninstall` de
     * l'application publiée. Celle-ci s'exécute : elle efface un répertoire, vide ou non.
     */
    VOICE_CAPTURES_WIPE,

    /** Ferme la base, écrase l'en-tête du fichier, supprime le fichier et ses annexes. */
    DB_WIPE,

    /**
     * `files/stt/` — le modèle de transcription importé par l'utilisateur.
     *
     * ⚠️ **Loin derrière le clair, et c'est justifié** : un modèle ne contient rien de
     * l'utilisateur. C'est un binaire public, identique sur tous les appareils qui l'ont importé, et
     * sa lecture n'apprendrait strictement rien à qui saisirait l'appareil. Ce qu'il révèle tient en
     * un fait — que l'application dicte — et cela ne vaut pas de faire attendre une seule note
     * lisible. Il pèse en revanche plusieurs dizaines de mégaoctets, donc il passe là où la lenteur
     * ne coûte plus rien : après la base, avec les autres fichiers volumineux.
     *
     * ⚠️⚠️ **À ne pas confondre avec [LEGACY_MODELS_WIPE], qui vise `files/models/`.** Les deux
     * répertoires sont voisins et sans rapport ; se tromper effacerait le fichier que l'utilisateur a
     * eu le plus de mal à obtenir. Cf. `SttModelStore`.
     */
    VOICE_MODEL_WIPE,

    /** `files/models/` — jusqu'à 530 Mo laissés par les versions ≤ 1.1.6 qui embarquaient une IA. */
    LEGACY_MODELS_WIPE,

    /** Toutes les préférences sauf deux, par liste blanche. */
    PREFS_CLEAR,

    /** Le reste du cache. */
    CACHE_PURGE,
}

/** Ce qu'une étape a donné. [failure] porte le **type** de l'erreur, jamais un chemin ni un secret. */
data class PanicOutcome(val step: PanicStep, val failure: String? = null) {
    val succeeded: Boolean get() = failure == null
}

/**
 * Le bilan d'une panique.
 *
 * ## ⚠️ Deux questions différentes, et l'écran de fin doit poser la seconde
 *
 * « Tout s'est-il bien passé ? » n'est pas « suis-je protégé ? ». La destruction de la clé suffit à
 * rendre la base illisible ; des étapes de nettoyage peuvent échouer sans que cela change quoi que
 * ce soit à la protection. À l'inverse, si c'est **elle** qui a échoué, dix étapes réussies ne
 * protègent rien.
 */
data class PanicReport(
    val outcomes: List<PanicOutcome>,
    /**
     * 🔴 **Mesuré à la fin de la séquence**, pas déduit d'une étape.
     *
     * Un répertoire de **clair** existe-t-il encore une fois tout terminé — archives d'export ou
     * enregistrements de dictée ? Se fier à l'issue des étapes donnait deux réponses fausses en sens
     * inverse : une étape peut échouer et [PanicStep.CACHE_PURGE] emporter quand même le répertoire
     * — elle traite `exports` comme un artefact sensible — et le contraire reste concevable. Un état
     * se **regarde**, il ne se déduit pas d'un journal d'étapes. Relevé par une relecture externe
     * (GPT-5.2, 2026-08-15).
     *
     * ⚠️ Vaut `true` quand la mesure elle-même est impossible : le doute penche du côté qui
     * n'annonce pas une protection qu'on n'a pas constatée.
     */
    val clairSurLeDisque: Boolean = false,
) {

    /** Toutes les étapes ont abouti. */
    val isComplete: Boolean get() = outcomes.all(PanicOutcome::succeeded)

    /**
     * 🔴 La garantie qui compte : la clé de la base a été détruite dans **toutes** ses sources.
     *
     * `false` ici veut dire que les notes restent déchiffrables par qui possède l'appareil. C'est la
     * seule information que l'écran de fin ne doit jamais adoucir.
     */
    val minimalGuarantee: Boolean
        get() = outcomes.any { it.step == PanicStep.KEK_DESTROY && it.succeeded }

    val failedSteps: List<PanicStep> get() = outcomes.filterNot(PanicOutcome::succeeded).map(PanicOutcome::step)

    /**
     * 🔴 Du contenu **lisible** peut être resté sur l'appareil.
     *
     * Toutes les étapes ratées ne laissent pas le même résidu. Une purge de cache ou un effacement
     * de base qui échouent laissent des octets **chiffrés sous une clé détruite** — du bruit. Trois
     * choses seulement sont du **clair** : une archive d'export, qui porte le texte intégral des
     * notes, coffres ouverts compris ; un enregistrement de dictée, qui porte la voix de
     * l'utilisateur donc le contenu de sa note ; et le presse-papiers, où une note copiée attend.
     *
     * ⚠️⚠️ **Cet inventaire s'est déjà trompé DEUX FOIS, chaque fois par omission.** Il annonçait
     * « l'export est la seule », en oubliant le presse-papiers — un effacement raté laissait alors
     * l'écran promettre qu'il ne restait que de l'illisible avec une note en clair à portée de toute
     * application au premier plan. Puis « deux choses seulement », en oubliant les enregistrements
     * de dictée, arrivés depuis. Les deux fois, le commentaire était juste **le jour où il a été
     * écrit**. Un inventaire vieillit ; un critère non. Relevés par des relectures externes (Gemini,
     * 2026-08-15 puis 2026-08-16) — jamais par une relecture du fichier seul.
     *
     * ⚠️ Les fichiers se jugent sur [clairSurLeDisque] — un état **mesuré** — et le presse-papiers
     * sur l'issue de son étape, parce qu'il ne se relit pas : Android refuse la lecture à une
     * application qui n'a pas le focus. ⚠️ Le lien pointait ici vers un `exportsSurLeDisque` qui
     * n'existe plus depuis que la mesure a été étendue aux captures.
     *
     * ⚠️ **Ce n'est pas une quatrième issue.** [minimalGuarantee] reste acquise — la base est du
     * bruit, et le dire autrement affolerait quelqu'un qui est en réalité protégé pour l'essentiel.
     * Ce que cette propriété change, c'est **la nature du résidu annoncée à l'écran**, donc la
     * décision de se séparer ou non de l'appareil.
     */
    val clairPeutSubsister: Boolean
        get() = clairSurLeDisque ||
            outcomes.any { it.step == PanicStep.CLIPBOARD_CLEAR && !it.succeeded }
}

/**
 * **Mode panique — destruction irréversible.**
 *
 * Transposition de `services/security/panic_service.dart`. Destinataire : quelqu'un — journaliste,
 * avocat, praticien — confronté à une fouille ou à une contrainte physique, appareil déjà
 * déverrouillé entre les mains de l'autre. L'objectif est de rendre les notes irrécupérables en
 * quelques secondes.
 *
 * ## 🔴 L'ordre des étapes est la conception, pas une commodité
 *
 * La séquence est écrite pour qu'**une interruption à n'importe quel instant** — extinction, batterie
 * morte, processus tué par quelqu'un qui a compris ce qui se passe — laisse l'état le plus sûr
 * atteignable à cet instant.
 *
 * D'où la destruction de la clé **avant** les effacements lourds. Les inverser paraîtrait naturel —
 * on efface les fichiers, puis la clé — et ouvrirait une fenêtre de plusieurs secondes pendant
 * laquelle la base est encore déchiffrable. La version publiée avait cet ordre-là, et un audit l'a
 * fait corriger.
 *
 * Les clés du Keystore passent **avant** elles aussi : elles ne dépendent pas de la base, donc rien
 * n'oblige à les traiter après, et un attaquant qui aurait déjà copié la base ailleurs ne doit pas
 * conserver le moyen de rejouer un coffre à code sur un appareil restauré.
 *
 * ## ⚠️⚠️ Ce que cette promesse a coûté avant d'être tenue
 *
 * La phrase ci-dessus — « une interruption à n'importe quel instant laisse l'état le plus sûr » —
 * était **fausse** jusqu'au 2026-08-15, et c'est le commentaire lui-même qui a mis les deux
 * relectures externes sur la piste. Les archives d'export, alors les seuls fichiers **en clair** de
 * l'application — la dictée n'existait pas encore —, étaient effacées en avant-dernier : derrière
 * la base (déjà réduite à du bruit),
 * derrière les modèles hérités (plusieurs secondes sur 530 Mo) et derrière les préférences. Une
 * interruption dans cette fenêtre laissait des notes lisibles à côté d'une base illisible.
 *
 * > **Une garantie écrite dans un commentaire n'est pas une garantie tenue par le code.** Celle-ci
 * > l'est maintenant : le clair part immédiatement après la clé.
 *
 * ## ⚠️ Aucune étape n'interrompt les suivantes
 *
 * Chaque étape est indépendante et son échec est **enregistré**, pas propagé. Une clé du Keystore
 * qui résiste ne doit pas empêcher la destruction de la clé de la base.
 *
 * Mais enregistrer n'est pas taire : le rapport porte chaque échec, et l'écran de fin s'en sert. Un
 * rapport optimiste sur cet écran-là se paierait en sécurité physique.
 */
@Singleton
class PanicService @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val secureWindow: SecureWindowController,
    private val vaults: FolderVaultService,
    private val keystore: VaultKeystore,
    private val kek: KekRepository,
    private val databases: DatabaseProvider,
    private val prefs: LegacyPreferences,
    private val clipboard: SensitiveClipboard,
    private val voiceCapture: VoiceCapture,
    private val appLockKeystore: AppLockKeystore,
    private val biometricUnlockKey: BiometricUnlockKey,
    private val journal: PanicJournal,
) {

    private val verrou = Any()
    private var enVol: Deferred<PanicReport>? = null

    val isInProgress: Boolean get() = synchronized(verrou) { enVol?.isActive == true }

    /**
     * Déclenche la séquence. **Ne lève jamais.**
     *
     * ## ⚠️ Un second appel rejoint le premier, il n'en démarre pas un autre
     *
     * Deux appuis rapides sur le bouton de confirmation lanceraient sinon deux séquences
     * concurrentes qui fermeraient la même base et se disputeraient les mêmes fichiers. Le rapport
     * rendu serait celui de la course, pas celui de la destruction.
     *
     * ## ⚠️ La portée est celle du PROCESSUS
     *
     * L'écran qui déclenche la panique disparaît pendant qu'elle s'exécute. Sur `viewModelScope`,
     * la séquence serait annulée en plein milieu — après la destruction de la clé et avant
     * l'effacement du fichier, dans le pire des cas. C'est la leçon de la sauvegarde finale de
     * l'éditeur, appliquée à un geste qui ne se rejoue pas.
     */
    fun trigger(): Deferred<PanicReport> = synchronized(verrou) {
        enVol?.takeIf { it.isActive }?.let { return it }
        val nouveau = applicationScope.async { withContext(NonCancellable) { executer() } }
        enVol = nouveau
        nouveau
    }

    private suspend fun executer(): PanicReport {
        val issues = mutableListOf<PanicOutcome>()

        // 🔴 Before anything: a sequence cut from here on is finished at the next launch (audit
        // 2026-09-26, P2). Written with `commit`; a refusal is logged, never a reason to stop.
        if (!journal.markStarted()) Timber.w("panique : journal de reprise non ecrit")

        // 0. Le drapeau d'abord : ce qui suit ne doit pas se retrouver dans l'aperçu des
        //    applications récentes, où il survivrait jusqu'au redémarrage de l'appareil.
        //    Demande PERMANENTE — la séquence est sans retour, il n'y a rien à rendre.
        //
        //    ⚠️ **Cette étape ne peut pas échouer, et ce n'est pas un mensonge.** Elle enregistre
        //    une DEMANDE dans le compteur ; c'est `MainActivity` qui pose le drapeau sur la fenêtre,
        //    de façon réactive et depuis la composition. Vérifier ici que la fenêtre l'a reçu
        //    supposerait d'en détenir une référence — c'est-à-dire de faire remonter un objet
        //    d'interface dans un service de sécurité, pour une garantie que le compteur donne déjà.
        //
        //    L'asymétrie avec les autres étapes est donc voulue et bornée : signalée par un audit
        //    de sécurité (2026-08-14), qui concluait lui-même à l'absence de chemin d'exploitation
        //    dans une application mono-activité.
        issues += etape(PanicStep.FORCE_SECURE_WINDOW) { secureWindow.forcePermanently() }

        // 1. 🔴 Le micro, s'il enregistre. AVANT le presse-papiers, parce que c'est la seule source
        //    qui CONTINUE d'écrire du clair pendant que la séquence s'exécute : chaque milliseconde
        //    de plus est de la voix en plus sur le disque. Poser le drapeau est instantané, donc
        //    rien n'est retardé derrière.
        //
        //    ⚠️ L'attente de l'arrêt effectif est à l'étape 7, juste avant l'effacement — le seul
        //    endroit où elle change quelque chose.
        //    ⚠️⚠️ `couperEtInterdire`, pas `arreter` : la seconde est un drapeau de geste, que la
        //    demande d'enregistrement suivante remet à zéro. Une capture déjà lancée et en attente
        //    du verrou repartirait donc derrière cette étape — micro ouvert, voix sur le disque,
        //    après le passage de ce qui devait le couper.
        issues += etape(PanicStep.VOICE_CANCEL) { voiceCapture.couperEtInterdire() }

        // 2. Le presse-papiers, tôt : une note copiée y est en clair, et lisible par toute
        //    application au premier plan.
        issues += etape(PanicStep.CLIPBOARD_CLEAR) { clipboard.annulerEtEffacer() }

        // 3. Les clés des coffres ouverts, effacées de la mémoire vive AVANT de toucher au
        //    Keystore. Sans ça, une panique déclenchée coffre ouvert laisse sa clé en RAM pendant
        //    toute la séquence.
        issues += etape(PanicStep.FOLDERS_LOCK_ALL) { vaults.lockAll() }

        // 4. Les clés `vault_pin_*`. Elles ne dépendent pas de la base — donc exécutables même si
        //    elle est déjà illisible — et elles sont la seule barrière d'un coffre à code contre
        //    une attaque menée hors de l'appareil.
        issues += etape(PanicStep.PIN_KEYS_WIPE) {
            val effacees = keystore.deleteKeysWithPrefix(VaultParams.PIN_KEYSTORE_ALIAS_PREFIX)
            Timber.i("panique : %d clés de coffre à code effacées", effacees)
        }

        // 4 bis. The app lock's keys. Both are attempted even if the first resists; the step fails
        //    if either survives — each deletion re-reads the Keystore, so "gone" is observed, not
        //    assumed.
        issues += etape(PanicStep.APP_LOCK_KEYS_WIPE) {
            var firstFailure: Exception? = null
            for (delete in listOf(appLockKeystore::deleteKey, biometricUnlockKey::delete)) {
                try {
                    delete()
                } catch (e: AppLockKeystoreUnavailableException) {
                    if (firstFailure == null) firstFailure = e
                }
            }
            firstFailure?.let { throw it }
        }

        // 5. 🔴 POINT DE NON-RETOUR. À partir d'ici, un arrêt brutal ne perd plus la garantie
        //    minimale : la base chiffrée est du bruit, même récupérée bit à bit.
        issues += etape(PanicStep.KEK_DESTROY) { kek.destroy() }

        // 6. 🔴 **Les archives d'export, AUSSITÔT APRÈS la clé — et non en avant-dernier.**
        //
        //    Elles étaient en avant-dernier, derrière l'effacement de la base, celui des modèles hérités
        //    et les préférences. Or elles sont **en clair**, comme les enregistrements de dictée de
        //    l'étape suivante : tout ce
        //    qui les précédait désormais ne protège plus rien de lisible, puisque la clé est partie
        //    à l'étape 5. Un processus tué dans cette fenêtre laissait une base réduite à du bruit
        //    **et des notes parfaitement lisibles à côté** — coffres ouverts compris.
        //
        //    ⚠️⚠️ Le pire des trois était l'ordre relatif aux modèles hérités : leur suppression
        //    peut prendre **plusieurs secondes sur 530 Mo**, et du clair attendait derrière.
        //
        //    Relevé CONFIRMÉ par les DEUX relectures externes du 2026-08-15, chacune par un chemin
        //    différent — l'une par l'ordre, l'autre par le commentaire de classe qui promettait
        //    « l'état le plus sûr atteignable à tout instant ».
        //
        //    ⚠️ Toujours par le MÊME chemin que celui qui les écrit : deux définitions du répertoire,
        //    et la panique nettoierait un dossier que l'export n'utilise plus.
        //
        //    ⚠️ **Après** la destruction de la clé, pas avant : celle-ci est une écriture unique et
        //    quasi instantanée qui couvre *toutes* les notes, là où l'effacement des archives est un
        //    parcours de fichiers dont la durée dépend de ce que l'utilisateur a exporté. Placer un
        //    parcours devant la garantie qui protège le plus, ce serait refaire l'erreur qu'on
        //    corrige ici, dans l'autre sens.
        //
        //    🔴 **And every other plaintext of the cache, at the same rank** (security audit of
        //    2026-09-26, P2): what notes_tech 2.x left at its root — the `share_plus/` copies of its
        //    last export, a note exported alone, a dictation — waited for [PanicStep.CACHE_PURGE],
        //    the LAST step, behind the database and 530 MB of legacy models. An interruption in
        //    between left it for good. Same definition as the final measure: [ClairDuCache].
        issues += etape(PanicStep.EXPORTS_WIPE) {
            supprimerLeDossier(NoteExporter.repertoireDExport(context))
            val survivants = withContext(Dispatchers.IO) { ClairDuCache.purger(context) }
            if (survivants.isNotEmpty()) error("clair du cache survivant : ${survivants.size}")
        }

        // 7. Les enregistrements de dictée : du clair, comme les archives, donc au même rang.
        //
        //    ⚠️⚠️ **L'attente est ici, et l'effacement a lieu de toute façon.** Le drapeau posé à
        //    l'étape 1 n'est lu par la boucle de capture qu'en sortant de `micro.read`, qui bloque
        //    le temps d'un tampon. Supprimer le répertoire sans attendre laisserait la capture finir
        //    d'écrire **après** — un fichier de voix réapparu derrière son propre effacement.
        //
        //    L'ordre des trois lignes compte : on attend, on efface, et **seulement ensuite** on
        //    signale l'échec éventuel. Signaler avant effacerait moins que ce qu'on peut effacer.
        issues += etape(PanicStep.VOICE_CAPTURES_WIPE) {
            val arretee = voiceCapture.attendreLArret(ATTENTE_ARRET_CAPTURE_MS)
            supprimerLeDossier(VoiceCapture.repertoireDeCapture(context))
            if (!arretee) error("capture micro toujours en cours apres $ATTENTE_ARRET_CAPTURE_MS ms")
        }

        // 8. Le fichier de base. Défense en profondeur : la clé est déjà partie.
        issues += etape(PanicStep.DB_WIPE) { effacerLaBase() }

        // 9. Le modèle de transcription. Il ne contient rien de l'utilisateur — c'est un binaire
        //    public — mais il pèse plusieurs dizaines de mégaoctets, d'où ce rang : là où la lenteur
        //    ne fait plus attendre quoi que ce soit de lisible.
        //
        //    ⚠️ `files/stt/`, à ne pas confondre avec `files/models/` de l'étape suivante.
        issues += etape(PanicStep.VOICE_MODEL_WIPE) {
            supprimerLeDossier(SttModelStore.repertoireDesModeles(context))
        }

        // 10. Les modèles hérités des versions qui embarquaient une IA. Après la garantie de
        //    sécurité, parce que la suppression peut prendre plusieurs secondes sur 530 Mo.
        issues += etape(PanicStep.LEGACY_MODELS_WIPE) { supprimerLeDossier(File(context.filesDir, MODELS_DIR)) }

        // 11. Les préférences, par liste blanche.
        issues += etape(PanicStep.PREFS_CLEAR) {
            val effacees = prefs.clearAllExcept(PREFERENCES_CONSERVEES)
            Timber.i("panique : %d préférences effacées", effacees)
        }

        // 12. Le reste du cache — aperçus, fichiers temporaires, résidus de bibliothèques.
        issues += etape(PanicStep.CACHE_PURGE) { viderLeCache() }

        // ⚠️ **Regarder, pas déduire.** L'état du disque après la séquence entière, y compris ce
        // que la purge du cache a pu emporter en plus.
        val clairRestant = clairSurLeDisque()
        val bilan = PanicReport(issues, clairSurLeDisque = clairRestant)
        // The sequence has run to its end: nothing left to resume, whatever its failures — running
        // it again would meet them again.
        if (!journal.clear()) Timber.w("panique : journal de reprise non retire")
        Timber.w(
            "panique terminée — garantie minimale : %s, étapes en échec : %s",
            bilan.minimalGuarantee,
            bilan.failedSteps,
        )
        return bilan
    }

    /**
     * Exécute une étape et **enregistre** son issue.
     *
     * ⚠️ Le message conservé est le **nom de la classe** de l'exception, pas son texte. Un message
     * d'exception d'entrée-sortie contient le chemin du fichier, et ce chemin porte l'identifiant de
     * l'application ; sur l'écran de fin d'une panique, il n'a rien à faire.
     *
     * ⚠️ Une annulation n'est pas une étape en échec : elle interromprait toute la séquence. La
     * relancer est le seul comportement correct — mais la séquence tourne sous `NonCancellable`,
     * donc ce chemin ne devrait jamais s'ouvrir. Ce `catch` est là pour que, s'il s'ouvrait un jour,
     * une annulation ne se déguise pas en « destruction incomplète ».
     */
    private suspend fun etape(step: PanicStep, geste: suspend () -> Unit): PanicOutcome = try {
        geste()
        PanicOutcome(step)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Timber.e(e, "panique : étape %s en échec", step)
        PanicOutcome(step, e::class.java.simpleName)
    }

    /**
     * Ferme la base, écrase l'en-tête de ses fichiers, les supprime, puis **vérifie**.
     *
     * ## ⚠️ Pourquoi seulement l'en-tête, et pas tout le fichier
     *
     * Sur une mémoire flash à répartition d'usure, écrire des zéros à un décalage logique n'a
     * **aucun lien garanti** avec les blocs physiques qui portaient l'ancienne donnée : le
     * contrôleur peut se contenter de marquer les anciens libres. Écraser cent mégaoctets prendrait
     * plusieurs secondes sur un S9 pour une garantie que le support ne donne pas.
     *
     * La sécurité vient de la clé détruite à l'étape précédente. Cet écrasement-ci est une seconde
     * ligne, au cas où une faiblesse serait un jour découverte sur AES-256-GCM ; il vise les
     * premiers mégaoctets, qui portent l'en-tête et les premières pages.
     *
     * ⚠️ **L'existence est vérifiée après la suppression.** Sans ce contrôle, l'étape se déclarerait
     * réussie sur un fichier verrouillé par un autre processus, et le rapport mentirait.
     */
    private suspend fun effacerLaBase() = withContext(Dispatchers.IO) {
        // ⚠️ `sealForPanic` et non `close` : fermer ne fait qu'oublier l'instance, et le prochain
        // `get()` — un `Flow` de Room encore abonné, une portée applicative en cours — rouvrirait
        // la base. Ne trouvant plus ni clé ni fichier, la fabrique en générerait une paire neuve :
        // un fichier de base recréé quelques millisecondes après l'effacement, sur un appareil dont
        // on vient d'annoncer qu'il n'en restait rien. Le contrôle d'existence ci-dessous se serait
        // exécuté AVANT cette recréation et aurait déclaré l'étape réussie.
        databases.sealForPanic()
        val base = LegacyDatabaseLocation.databaseFile(context)
        val survivants = mutableListOf<String>()
        for (suffixe in LegacyDatabaseLocation.SIDECAR_SUFFIXES) {
            val cible = File(base.path + suffixe)
            if (!cible.exists()) continue
            ecraserPuisSupprimer(cible)
            if (cible.exists()) survivants += cible.name
        }
        if (survivants.isNotEmpty()) {
            error("fichiers de base survivants : ${survivants.size}")
        }
    }

    /**
     * ⚠️⚠️ **`RandomAccessFile` et non `outputStream()`, et ce n'est pas un détail de style.**
     *
     * `File.outputStream()` construit un `FileOutputStream` sans mode ajout, donc ouvre avec
     * `O_TRUNC` : le fichier est ramené à zéro octet **avant** que le premier zéro soit écrit. Le
     * système libère alors les blocs qui portaient la donnée, et les seize mégaoctets qui suivent
     * vont dans des blocs **fraîchement alloués**. L'en-tête SQLite et les premières pages ne sont
     * jamais recouverts — ils sont seulement marqués libres, c'est-à-dire exactement l'état où les
     * laisserait un `delete()` seul. L'étape écrivait seize mégaoctets pour rien.
     *
     * `RandomAccessFile(fichier, "rw")` ouvre sans tronquer, au décalage zéro, et écrit **par
     * dessus**.
     *
     * ⚠️ **`sync()` et non `flush()`.** `flush()` sur un `FileOutputStream` ne fait rien — il n'y a
     * aucun tampon applicatif à vider. Les zéros restaient donc des pages sales du noyau, et le
     * `delete()` qui suit immédiatement autorise le noyau à les abandonner sans jamais les écrire.
     * Le second défaut annulait ce qui restait du premier.
     *
     * Ce que cet écrasement ne peut pas promettre est dit plus haut : sur une mémoire flash à
     * répartition d'usure, écrire au décalage zéro ne garantit pas d'atteindre les blocs physiques
     * d'origine. C'est une seconde ligne, pas la protection — celle-ci vient de la clé détruite à
     * l'étape précédente. Mais une seconde ligne qui ne s'exécute pas ne vaut rien du tout.
     */
    private fun ecraserPuisSupprimer(fichier: File) {
        try {
            val aEcraser = minOf(fichier.length(), OCTETS_ECRASES.toLong())
            RandomAccessFile(fichier, "rw").use { acces ->
                val bloc = ByteArray(TAILLE_DE_BLOC)
                var ecrits = 0L
                while (ecrits < aEcraser) {
                    val n = minOf(TAILLE_DE_BLOC.toLong(), aEcraser - ecrits).toInt()
                    acces.write(bloc, 0, n)
                    ecrits += n
                }
                acces.fd.sync()
            }
        } catch (e: Exception) {
            // Au mieux effort : la clé détruite suffit déjà à rendre le contenu illisible. On
            // supprime quand même, et c'est la suppression qui est vérifiée par l'appelant.
            Timber.w(e, "écrasement impossible avant suppression — on supprime tout de même")
        }
        fichier.delete()
    }

    /** ⚠️ Lève si le répertoire résiste : c'est ce qui empêche l'étape de mentir. */
    private suspend fun supprimerLeDossier(dossier: File) = withContext(Dispatchers.IO) {
        if (!dossier.exists()) return@withContext
        dossier.deleteRecursively()
        if (dossier.exists()) error("répertoire non supprimé")
    }

    /**
     * Vide le cache **entrée par entrée**, sans supprimer le répertoire lui-même.
     *
     * ⚠️ Ne pas signaler ce qui résiste au-delà de ce qui nous appartient. Le cache est partagé avec
     * les bibliothèques et le système, dont certains tiennent leurs fichiers ouverts : faire échouer
     * l'étape sur n'importe quel résidu transformerait l'avertissement « effacement incomplet » en
     * alarme permanente, donc en bruit qu'on apprend à ignorer — précisément au moment où il doit
     * être cru. C'est un correctif que la version publiée a déjà dû appliquer.
     */
    private suspend fun viderLeCache() = withContext(Dispatchers.IO) {
        val survivantsSensibles = context.cacheDir.listFiles().orEmpty().filter { entree ->
            entree.deleteRecursively()
            entree.exists() && ClairDuCache.estUnArtefactSensible(context, entree.name)
        }
        if (survivantsSensibles.isNotEmpty()) {
            error("artefacts de cache survivants : ${survivantsSensibles.size}")
        }
    }

    /**
     * 🔴🔴 **Reste-t-il du clair sur le disque ? — mesuré par la MÊME définition que l'effacement.**
     *
     * ## Ce que cette fonction a corrigé, le 2026-08-19
     *
     * La mesure ne regardait que deux répertoires, `exports/` et `captures/`. Or
     * [ClairDuCache.estUnArtefactSensible] déclare que **toute** archive, tout document
     * Markdown et tout enregistrement du cache portent du clair — et c'est sur cette base que
     * [PanicStep.CACHE_PURGE] **échoue**. Deux définitions du même mot vivaient dans le même
     * fichier, et elles divergeaient.
     *
     * Conséquence exacte : un `.md` resté à la racine du cache faisait échouer la purge sans
     * qu'aucun des deux répertoires n'existe. [PanicReport.clairPeutSubsister] valait alors `false`,
     * et l'écran de fin annonçait « des fichiers **illisibles** peuvent subsister » — devant une
     * note parfaitement lisible, à quelqu'un en train de décider s'il peut se séparer de son
     * appareil.
     *
     * ⚠️⚠️ Relevé par les **deux** relectures externes du 2026-08-19, chacune par un chemin
     * différent : l'une en partant de ce qui fait échouer la purge, l'autre en comparant les deux
     * inventaires. Aucune relecture du seul écran ne pouvait le voir. *La duplication n'était pas du
     * code recopié : c'était une **notion** définie deux fois.*
     *
     * ## ⚠️ Récursive, là où l'effacement ne l'est pas
     *
     * `viderLeCache` n'examine que le premier niveau — un `note.md` rangé dans un sous-répertoire au
     * nom anodin survit à une purge qui se déclare réussie. La mesure, elle, descend : c'est le
     * dernier regard porté sur le disque, il n'a aucune raison d'être le plus myope des deux.
     *
     * @return `true` aussi quand la mesure elle-même échoue — le doute penche du côté qui n'annonce
     *   pas une protection qu'on n'a pas constatée.
     */
    private fun clairSurLeDisque(): Boolean = try {
        NoteExporter.repertoireDExport(context).exists() ||
            VoiceCapture.repertoireDeCapture(context).exists() ||
            context.cacheDir.walkTopDown()
                // ⚠️⚠️ Par défaut, `walkTopDown` **ignore** un répertoire qu'il ne peut pas
                // lister : la mesure rendrait `false` sur un cache partiellement illisible, et
                // ce serait un faux négatif exactement là où le repli existe. Ne pas pouvoir
                // regarder n'est pas une réponse. Relevé par une relecture externe (GPT-5.2).
                .onFail { _, e -> throw e }
                .any { it.isFile && ClairDuCache.estUnArtefactSensible(context, it.name) }
    } catch (e: Exception) {
        Timber.w(e, "panique : etat du clair sur le disque illisible")
        true
    }

    internal companion object {
        /** `files/models/` — cf. `services/legacy_model_files.dart`. ⚠️ `stt/` ne doit PAS y passer. */
        const val MODELS_DIR = "models"

        /**
         * Combien de temps attendre qu'une capture en cours s'arrête, avant d'effacer sans elle.
         *
         * ⚠️ La boucle de capture relit son drapeau à chaque tampon — de l'ordre de la centaine de
         * millisecondes. Deux secondes couvrent très largement le cas normal ; au-delà, c'est que
         * quelque chose ne répond plus, et le mode panique ne s'arrête pas pour attendre. **La borne
         * est là pour que la séquence continue**, pas pour donner sa chance au micro.
         */
        const val ATTENTE_ARRET_CAPTURE_MS = 2_000L

        /** 16 Mio : l'en-tête et les premières pages, sans immobiliser l'appareil plusieurs secondes. */
        const val OCTETS_ECRASES = 16 * 1024 * 1024

        const val TAILLE_DE_BLOC = 64 * 1024

        /**
         * Les deux seules préférences qui survivent, conformément à `PRIVACY.{fr,en}.md`.
         *
         * - `secure_window_enabled` : l'effacer ferait perdre la protection contre les captures
         *   d'écran pendant que l'utilisateur reconfigure une application vide, c'est-à-dire juste
         *   après l'événement qui a motivé la panique.
         * - `db_encrypted_v1` : l'effacer ferait croire au démarrage suivant qu'une base en clair
         *   attend d'être migrée.
         *
         * ⚠️ `vault_wipe_pending_*` doit rester **effacé** : ce sont des reprises d'effacement de
         * coffre après incident, qui n'ont plus d'objet une fois la base détruite.
         */
        val PREFERENCES_CONSERVEES = setOf("secure_window_enabled", "db_encrypted_v1", PanicJournal.KEY)
    }
}
