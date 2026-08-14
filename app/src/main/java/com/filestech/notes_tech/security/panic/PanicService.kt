package com.filestech.notes_tech.security.panic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.LegacyDatabaseLocation
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.di.ApplicationScope
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Les étapes de la séquence, **dans l'ordre où elles s'exécutent**.
 *
 * ⚠️ Une étape déclarée ici doit s'exécuter. Une constante qui ne correspond à aucun geste rendrait
 * le rapport menteur — c'est l'erreur que la version publiée a commise puis corrigée, en gardant une
 * étape `gemmaUninstall` qui passait par un service supprimé et ne tournait donc plus jamais.
 *
 * C'est pourquoi il n'y a **ni** `voiceCancel` **ni** `voiceWipe` ici : la dictée arrive en phase 7,
 * et ces deux étapes y entreront avec elle. Les déclarer maintenant ferait annoncer à l'utilisateur
 * l'effacement d'un enregistrement qui n'existe pas.
 */
enum class PanicStep {
    /** Empêche l'aperçu des applications récentes de capturer la confirmation et l'écran de fin. */
    FORCE_SECURE_WINDOW,

    /** Une note copiée y est en clair. La panique n'attend pas l'expiration ordinaire. */
    CLIPBOARD_CLEAR,

    /** Efface de la mémoire les clés des coffres ouverts, avant de toucher au Keystore. */
    FOLDERS_LOCK_ALL,

    /** Supprime toutes les clés `vault_pin_*`, y compris les orphelines. */
    PIN_KEYS_WIPE,

    /** 🔴 **Point de non-retour.** Après cette étape, la base est du bruit. */
    KEK_DESTROY,

    /** Ferme la base, écrase l'en-tête du fichier, supprime le fichier et ses annexes. */
    DB_WIPE,

    /** `files/models/` — jusqu'à 530 Mo laissés par les versions ≤ 1.1.6 qui embarquaient une IA. */
    LEGACY_MODELS_WIPE,

    /** Toutes les préférences sauf deux, par liste blanche. */
    PREFS_CLEAR,

    /** Les archives d'export, qui sont du clair sur le disque. */
    EXPORTS_WIPE,

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
data class PanicReport(val outcomes: List<PanicOutcome>) {

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

        // 1. Le presse-papiers, tôt : une note copiée y est en clair, et lisible par toute
        //    application au premier plan.
        issues += etape(PanicStep.CLIPBOARD_CLEAR) { viderLePressePapiers() }

        // 2. Les clés des coffres ouverts, effacées de la mémoire vive AVANT de toucher au
        //    Keystore. Sans ça, une panique déclenchée coffre ouvert laisse sa clé en RAM pendant
        //    toute la séquence.
        issues += etape(PanicStep.FOLDERS_LOCK_ALL) { vaults.lockAll() }

        // 3. Les clés `vault_pin_*`. Elles ne dépendent pas de la base — donc exécutables même si
        //    elle est déjà illisible — et elles sont la seule barrière d'un coffre à code contre
        //    une attaque menée hors de l'appareil.
        issues += etape(PanicStep.PIN_KEYS_WIPE) {
            val effacees = keystore.deleteKeysWithPrefix(VaultParams.PIN_KEYSTORE_ALIAS_PREFIX)
            Timber.i("panique : %d clés de coffre à code effacées", effacees)
        }

        // 4. 🔴 POINT DE NON-RETOUR. À partir d'ici, un arrêt brutal ne perd plus la garantie
        //    minimale : la base chiffrée est du bruit, même récupérée bit à bit.
        issues += etape(PanicStep.KEK_DESTROY) { kek.destroy() }

        // 5. Le fichier. Défense en profondeur : la clé est déjà partie.
        issues += etape(PanicStep.DB_WIPE) { effacerLaBase() }

        // 6. Les modèles hérités des versions qui embarquaient une IA. Après la garantie de
        //    sécurité, parce que la suppression peut prendre plusieurs secondes sur 530 Mo.
        issues += etape(PanicStep.LEGACY_MODELS_WIPE) { supprimerLeDossier(File(context.filesDir, MODELS_DIR)) }

        // 7. Les préférences, par liste blanche.
        issues += etape(PanicStep.PREFS_CLEAR) {
            val effacees = prefs.clearAllExcept(PREFERENCES_CONSERVEES)
            Timber.i("panique : %d préférences effacées", effacees)
        }

        // 8. Les archives d'export : du clair sur le disque, par le MÊME chemin que celui qui les
        //    écrit. Deux définitions du répertoire, et la panique nettoierait un dossier que
        //    l'export n'utilise plus.
        issues += etape(PanicStep.EXPORTS_WIPE) { supprimerLeDossier(NoteExporter.repertoireDExport(context)) }

        // 9. Le reste du cache — aperçus, fichiers temporaires, résidus de bibliothèques.
        issues += etape(PanicStep.CACHE_PURGE) { viderLeCache() }

        val bilan = PanicReport(issues)
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
     * Vide le presse-papiers.
     *
     * ⚠️ `clearPrimaryClip` n'existe qu'à partir de l'API 28. En dessous, le seul moyen est d'y
     * poser une valeur vide : le presse-papiers n'est alors pas vide, il contient une chaîne vide.
     * C'est la seule chose que la plateforme permette, et c'est suffisant — le texte de la note
     * n'y est plus.
     */
    private fun viderLePressePapiers() {
        val presse = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            presse.clearPrimaryClip()
        } else {
            presse.setPrimaryClip(ClipData.newPlainText("", ""))
        }
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

    private fun ecraserPuisSupprimer(fichier: File) {
        try {
            val aEcraser = minOf(fichier.length(), OCTETS_ECRASES.toLong())
            fichier.outputStream().use { flux ->
                val bloc = ByteArray(TAILLE_DE_BLOC)
                var ecrits = 0L
                while (ecrits < aEcraser) {
                    val n = minOf(TAILLE_DE_BLOC.toLong(), aEcraser - ecrits).toInt()
                    flux.write(bloc, 0, n)
                    ecrits += n
                }
                flux.flush()
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
            entree.exists() && estUnArtefactSensible(entree.name)
        }
        if (survivantsSensibles.isNotEmpty()) {
            error("artefacts de cache survivants : ${survivantsSensibles.size}")
        }
    }

    /** Ce qui, dans le cache, peut porter le contenu d'une note. Le reste ne nous appartient pas. */
    private fun estUnArtefactSensible(nom: String): Boolean {
        val n = nom.lowercase()
        return n == "exports" || n.endsWith(".zip") || n.endsWith(".md") || n.endsWith(".wav")
    }

    private companion object {
        /** `files/models/` — cf. `services/legacy_model_files.dart`. ⚠️ `stt/` ne doit PAS y passer. */
        const val MODELS_DIR = "models"

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
        val PREFERENCES_CONSERVEES = setOf("secure_window_enabled", "db_encrypted_v1")
    }
}
