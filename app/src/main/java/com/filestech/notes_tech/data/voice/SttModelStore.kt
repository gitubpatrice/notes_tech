package com.filestech.notes_tech.data.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.filestech.notes_tech.domain.voice.CopieVerifiee
import com.filestech.notes_tech.domain.voice.SttException
import com.filestech.notes_tech.domain.voice.SttModel
import com.filestech.notes_tech.domain.voice.SttModelChecksumMismatchException
import com.filestech.notes_tech.domain.voice.SttModelImportFailedException
import com.filestech.notes_tech.domain.voice.SttModelSourceInvalidException
import com.filestech.notes_tech.domain.voice.SttModelStorageFullException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** L'avancement d'un import, en octets. [fraction] vaut `0` tant que la taille attendue est nulle. */
data class SttImportProgress(val octetsTraites: Long, val octetsAttendus: Long) {
    val fraction: Float
        get() = if (octetsAttendus <= 0L) 0f else (octetsTraites.toFloat() / octetsAttendus).coerceIn(0f, 1f)
}

/**
 * Où vit le modèle de transcription, et comment il y entre.
 *
 * ## 🔴 `files/stt/`, et surtout **pas** `files/models/`
 *
 * Les deux répertoires existent, et ils n'ont rien à voir :
 *
 * - `files/models/` contenait l'IA embarquée des versions ≤ 1.1.6 — jusqu'à 530 Mo qu'aucun écran ne
 *   mentionne. `PanicStep.LEGACY_MODELS_WIPE` le détruit, et le démarrage le purge une fois.
 * - `files/stt/` est **le nôtre**, et il doit survivre à ces deux purges.
 *
 * ⚠️⚠️ **La confusion n'a rien d'abstrait : elle coûterait à l'utilisateur ce qu'il a fait de plus
 * pénible.** Il a dû trouver le bon fichier, le télécharger sur un autre appareil, le transférer, et
 * l'importer. Le purger par erreur le lui ferait recommencer sans qu'aucun message n'explique
 * pourquoi. L'application publiée porte un test dédié à cette seule question — que `stt/` survive à
 * la purge de `models/` — et le commentaire de `legacy_model_files.dart` l'avertit en toutes lettres.
 *
 * ## Ce qui entre ici est vérifié, ou n'entre pas
 *
 * Un modèle est un binaire de plusieurs dizaines de mégaoctets, exécuté par une bibliothèque native
 * sur le contenu des notes. Il arrive de l'extérieur — l'application n'a aucun moyen de le
 * télécharger — donc **l'empreinte est le seul contrôle d'origine qui existe**. D'où la séquence :
 *
 * 1. taille plausible, pour rejeter une photo ou une vidéo sans lire cinquante mégaoctets ;
 * 2. place disponible, pour dire « libérez de la place » plutôt que d'échouer au milieu ;
 * 3. copie dans un fichier **temporaire**, empreinte calculée dans le même passage ;
 * 4. empreinte conforme ⇒ renommage ; sinon le temporaire est effacé et l'échec est dit.
 *
 * ⚠️ Le renommage est la dernière étape **pour que le fichier définitif n'existe jamais à moitié**.
 * Un import interrompu — batterie, arrêt du processus — ne laisse qu'un `.tmp`, que la tentative
 * suivante écrase. Même raison que le fichier temporaire de l'export ; même raison, aussi, que
 * l'en-tête WAV réécrit en dernier.
 */
@Singleton
class SttModelStore @Inject constructor(@param:ApplicationContext private val context: Context) {

    /**
     * Un seul import à la fois.
     *
     * ⚠️ Deux imports du même modèle se disputeraient le même fichier temporaire : le premier
     * renommerait ce que le second est en train d'écrire. Le verrou rend le second simplement plus
     * lent, et il trouvera le modèle déjà installé — donc rendra la main tout de suite.
     */
    private val verrou = Mutex()

    /** Le fichier où ce modèle est attendu. N'implique pas qu'il existe. */
    fun fichierDuModele(modele: SttModel): File = File(repertoireDesModeles(context), modele.fileName)

    /**
     * Le modèle est-il présent **et conforme à son empreinte** ?
     *
     * ⚠️ Relit tout le fichier : environ une seconde et demie pour 57 Mo sur un appareil de 2018. À
     * ne pas appeler à chaque recomposition. Le contrôle bon marché est [estPresent].
     */
    suspend fun estInstalle(modele: SttModel): Boolean = withContext(Dispatchers.IO) {
        val fichier = fichierDuModele(modele)
        if (!estPlausible(fichier, modele)) return@withContext false
        try {
            FileInputStream(fichier).use { flux ->
                CopieVerifiee.empreinteDe(flux, borneDeCopie(modele)).empreinteSha256
            } == modele.expectedSha256
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "modele : lecture de %s impossible", fichier.name)
            false
        } catch (e: SttException) {
            // Un fichier plus gros que la borne : conforme à rien, donc pas installé.
            Timber.w(e, "modele : %s refuse a la verification", fichier.name)
            false
        }
    }

    /**
     * Contrôle bon marché : le fichier est là, et sa taille est plausible.
     *
     * ⚠️⚠️ **Ne dit rien de son contenu, et ne doit jamais servir à décider d'un chargement.** Il
     * répond à « faut-il proposer l'écran d'installation ? », pas à « ce binaire est-il celui qu'on
     * croit ? ». La seconde question se pose avant chaque chargement natif, et se tranche par
     * [estInstalle].
     */
    fun estPresent(modele: SttModel): Boolean = estPlausible(fichierDuModele(modele), modele)

    /**
     * Importe le fichier désigné par [source] comme étant [modele].
     *
     * Le fichier d'origine n'est **pas** touché : il appartient à l'utilisateur, qui peut vouloir le
     * garder pour réimporter plus tard.
     *
     * Idempotent : si le modèle est déjà installé et conforme, rend le fichier existant sans rien
     * lire de la source.
     *
     * @param onProgress appelé au fil de la copie, **hors du fil principal**.
     * @throws SttModelSourceInvalidException fichier illisible, disparu, ou de taille sans rapport.
     * @throws SttModelStorageFullException il n'y a pas la place.
     * @throws SttModelChecksumMismatchException l'empreinte ne correspond pas. **Le fichier copié est
     *   alors supprimé** : le garder inviterait à réessayer avec exactement les mêmes octets.
     * @throws SttModelImportFailedException toute autre panne d'écriture.
     */
    suspend fun importer(source: Uri, modele: SttModel, onProgress: ((SttImportProgress) -> Unit)? = null): File =
        verrou.withLock { withContext(Dispatchers.IO) { importerSousVerrou(source, modele, onProgress) } }

    private suspend fun importerSousVerrou(
        source: Uri,
        modele: SttModel,
        onProgress: ((SttImportProgress) -> Unit)?,
    ): File {
        val cible = fichierDuModele(modele)
        if (estInstalle(modele)) return cible

        val racine = preparerLeRepertoire()
        controlerLaTailleAnnoncee(source, modele)
        controlerLaPlace(racine, modele)

        val temporaire = File(racine, "${modele.fileName}.tmp")
        effacerOuSignaler(temporaire)

        try {
            val resultat = context.contentResolver.openInputStream(source).let { entree ->
                // ⚠️ `openInputStream` rend `null` quand le fournisseur ne sait pas ouvrir le
                // document — révoqué, supprimé depuis le choix, ou jamais lisible. Sans ce
                // contrôle, l'appel suivant lèverait une `NullPointerException` : une panne hors
                // hiérarchie, pour la cause la plus banale de toutes.
                entree ?: throw SttModelSourceInvalidException("le fichier choisi n'est pas lisible")
            }.use { entree ->
                FileOutputStream(temporaire).use { sortie ->
                    val copie = CopieVerifiee.copier(
                        source = entree,
                        cible = sortie,
                        octetsMax = borneDeCopie(modele),
                        onProgress = { octets ->
                            onProgress?.invoke(SttImportProgress(octets, modele.sizeBytes))
                        },
                    )
                    // 🔴 Forcer l'écriture **avant** le renommage. Sans cela, une coupure de courant
                    // juste après le renommage peut laisser un fichier au bon nom et au contenu
                    // incomplet : le renommage, lui, aura été journalisé. L'empreinte le rattraperait
                    // au chargement suivant — mais en présentant un modèle « corrompu » là où il n'y
                    // a jamais eu qu'une écriture inachevée.
                    sortie.flush()
                    sortie.fd.sync()
                    copie
                }
            }

            if (resultat.empreinteSha256 != modele.expectedSha256) {
                effacerOuSignaler(temporaire)
                throw SttModelChecksumMismatchException(
                    "empreinte non conforme pour \"${modele.id}\" ; le fichier importe a ete supprime",
                )
            }

            renommer(temporaire, cible)
            return cible
        } catch (e: Throwable) {
            // ⚠️ `Throwable` : une annulation doit emporter le fichier partiel elle aussi. C'est la
            // même règle que la capture micro, et pour la même raison — un abandon ne doit rien
            // laisser derrière lui.
            effacerOuSignaler(temporaire)
            throw traduire(e)
        }
    }

    /**
     * 🔴 **Rien ne sort d'ici hors de [SttException]** — sauf une annulation, qui n'est pas un échec.
     *
     * ⚠️⚠️ Ce filtre manquait, et le `catch` relançait tel quel. `openInputStream`, `FileOutputStream`,
     * l'écriture et `fd.sync()` lèvent des `IOException` ; un fournisseur tiers peut lever une
     * `SecurityException` en révoquant l'accès entre le choix et la lecture. Toutes traversaient la
     * fonction alors que sa documentation promet quatre types précis, et qu'un `when` exhaustif chez
     * l'appelant les aurait laissées filer — exactement le défaut que `SpeechToText.transcribeFile` a
     * déjà eu à documenter, et que la classe [SttModelImportFailedException] dit exister pour éviter.
     * Trouvé en relisant le delta entier, après que les fichiers eurent été relus un par un.
     */
    private fun traduire(e: Throwable): Throwable = when (e) {
        is CancellationException -> e
        is SttException -> e
        else -> SttModelImportFailedException("import interrompu : ${e::class.java.simpleName}", cause = e)
    }

    /** Retire le modèle de l'appareil. Sans effet s'il n'y est pas. */
    suspend fun desinstaller(modele: SttModel) = withContext(Dispatchers.IO) {
        effacerOuSignaler(fichierDuModele(modele))
    }

    // ------------------------------------------------------------------------------------------
    // Internes
    // ------------------------------------------------------------------------------------------

    private fun estPlausible(fichier: File, modele: SttModel): Boolean =
        fichier.isFile && !horsTolerance(fichier.length(), modele.sizeBytes)

    private fun preparerLeRepertoire(): File {
        val racine = repertoireDesModeles(context)
        // ⚠️ `mkdirs` rend `false` quand le répertoire existe déjà — le cas normal. C'est
        // `isDirectory` qui tranche, et l'inverse a déjà produit un faux échec ailleurs.
        if (!racine.mkdirs() && !racine.isDirectory) {
            throw SttModelImportFailedException("repertoire des modeles inaccessible")
        }
        return racine
    }

    /**
     * Rejette d'emblée un fichier dont la taille n'a aucun rapport.
     *
     * ⚠️ **Muet quand la source ne dit pas sa taille**, ce qui arrive avec certains fournisseurs. Ce
     * n'est pas une permission de copier sans limite : la borne de [CopieVerifiee.copier] tient de
     * toute façon, et elle se calcule sur ce que **le modèle** attend — jamais sur ce que la source
     * annonce, qui n'engage personne.
     */
    private fun controlerLaTailleAnnoncee(source: Uri, modele: SttModel) {
        val annoncee = tailleAnnoncee(source) ?: return
        // 🔴 **Une taille nulle ou negative veut dire « je ne sais pas », pas « fichier vide ».**
        //
        // Plusieurs fournisseurs — stockage en nuage, documents virtuels — rendent `0` ou `-1` plutot
        // qu'une colonne absente. Sans ce controle, un modele parfaitement conforme etait REJETE
        // avant meme d'etre lu, uniquement parce que l'application avait cru une metadonnee tierce
        // qui n'engage personne. Releve par une relecture externe (GPT-5.5, 2026-08-16).
        //
        // ⚠️ La garde de taille n'est pas retiree pour autant, contrairement a ce que la relecture
        // proposait : ecarter une video de 3 Go sans en copier un octet a une vraie valeur. Elle est
        // simplement ramenee a ce qu'elle sait faire — juger une valeur qu'on a, jamais une absence.
        if (annoncee <= 0L) return
        if (horsTolerance(annoncee, modele.sizeBytes)) {
            throw SttModelSourceInvalidException(
                "taille inattendue : $annoncee octets recus, environ ${modele.sizeBytes} attendus " +
                    "pour \"${modele.id}\"",
            )
        }
    }

    private fun tailleAnnoncee(source: Uri): Long? = try {
        context.contentResolver
            .query(source, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { curseur ->
                if (curseur.moveToFirst() && !curseur.isNull(0)) curseur.getLong(0) else null
            }
    } catch (e: RuntimeException) {
        // ⚠️ Interroger un fournisseur tiers peut échouer de bien des façons — permission retirée,
        // fournisseur mort, colonne absente. Aucune n'est un échec d'import : on se passe du
        // contrôle bon marché et la borne de copie fait le reste.
        Timber.w(e, "modele : taille de la source illisible")
        null
    }

    private fun controlerLaPlace(racine: File, modele: SttModel) {
        val disponible = racine.usableSpace
        // ⚠️ `usableSpace` rend `0` quand la question n'a pas de réponse. Traiter ce zéro comme
        // « disque plein » ferait échouer un import parfaitement possible : le doute penche ici du
        // côté de l'essai, parce que l'échec réel serait de toute façon signalé par l'écriture.
        if (disponible > 0L && disponible < modele.sizeBytes + MARGE_DE_PLACE) {
            throw SttModelStorageFullException(
                "place insuffisante : $disponible octets libres, environ ${modele.sizeBytes} necessaires",
            )
        }
    }

    /**
     * Le renommage final.
     *
     * ⚠️⚠️ **[File.renameTo] rend un booléen que personne ne regarde**, et c'est un des pièges
     * classiques de l'API : un échec passe pour un succès, et l'appelant croit avoir installé un
     * modèle qui n'existe pas. Ici l'échec est dit.
     *
     * La cible peut exister — un import précédent dont l'empreinte s'est révélée fausse au
     * chargement. Le renommage la remplace en une seule opération sur ce système de fichiers ; s'il
     * refuse, on retire la cible et on retente une fois. ⚠️ Perdre ainsi un fichier déjà installé est
     * sans conséquence : on n'arrive ici **qu'après** avoir constaté qu'il n'est pas conforme.
     */
    private fun renommer(temporaire: File, cible: File) {
        if (temporaire.renameTo(cible)) return
        effacerOuSignaler(cible)
        if (!temporaire.renameTo(cible)) {
            throw SttModelImportFailedException("le modele n'a pas pu etre mis en place")
        }
    }

    /** Voir `NoteExporter.effacerOuSignaler` : même règle, même raison. */
    private fun effacerOuSignaler(fichier: File) {
        try {
            if (fichier.exists() && !fichier.delete()) {
                Timber.e("modele : %s n'a PAS pu etre efface", fichier.name)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "modele : effacement de %s refuse", fichier.name)
        }
    }

    companion object {

        /**
         * Le seul endroit où un modèle est écrit.
         *
         * ⚠️ Une seule définition, comme pour les exports et les captures : deux définitions, et une
         * purge nettoierait un répertoire que l'import n'utilise plus.
         */
        fun repertoireDesModeles(context: Context): File = File(context.filesDir, NOM_DU_REPERTOIRE)

        /**
         * Efface **tous** les modèles, quel qu'en soit l'identifiant.
         *
         * ⚠️ Réservé au mode panique. Un modèle ne contient rien de l'utilisateur — c'est un binaire
         * public, identique sur tous les appareils qui l'ont importé — mais sa présence dit que
         * l'application dicte, et le geste de panique efface ce qu'il peut effacer. Il passe **après**
         * tout ce qui est lisible, parce que plusieurs dizaines de mégaoctets prennent du temps.
         */
        fun purgerLesModeles(context: Context) {
            val racine = repertoireDesModeles(context)
            if (racine.exists() && !racine.deleteRecursively()) {
                Timber.e("modeles : le repertoire %s n'a PAS pu etre purge", racine.name)
            }
        }

        /** ⚠️ Voisin de `models/`, et sans aucun rapport avec lui. Voir la note de classe. */
        const val NOM_DU_REPERTOIRE = "stt"

        /**
         * Tolérance sur la taille annoncée.
         *
         * ⚠️ **Large exprès.** Les tailles du catalogue sont approximatives et l'empreinte est le
         * vrai contrôle ; une tolérance serrée ne renforcerait rien et rejetterait un fichier
         * parfaitement valide dont la taille aurait été notée à la centaine de kilo-octets près. Son
         * rôle est de distinguer un modèle d'une photo, pas de valider un modèle.
         */
        const val TOLERANCE_DE_TAILLE = 0.10

        /** De quoi ne pas remplir le stockage au dernier octet. */
        private const val MARGE_DE_PLACE = 8L * 1024 * 1024

        /** La borne dure de la copie, calculée sur ce que le modèle attend. */
        internal fun borneDeCopie(modele: SttModel): Long =
            (modele.sizeBytes * (1 + TOLERANCE_DE_TAILLE)).toLong().coerceAtLeast(1L)

        internal fun horsTolerance(reelle: Long, attendue: Long): Boolean {
            if (attendue <= 0L) return false
            val ecart = kotlin.math.abs(reelle - attendue)
            return ecart > attendue * TOLERANCE_DE_TAILLE
        }
    }
}
