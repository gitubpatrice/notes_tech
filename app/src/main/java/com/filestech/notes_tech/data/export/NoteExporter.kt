package com.filestech.notes_tech.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.export.NoteArchive
import com.filestech.notes_tech.domain.export.NoteMarkdown
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.security.vault.FolderVaultService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ce qu'un export a produit.
 *
 * @param uri à passer telle quelle à un partage. Elle désigne un fichier du cache privé, exposé au
 *   destinataire par une permission ponctuelle et non par un chemin lisible de tous.
 */
data class ExportResult(val uri: Uri, val fileName: String, val exported: Int, val skippedLocked: Int) {
    val isComplete: Boolean get() = skippedLocked == 0
}

/**
 * L'export de toutes les notes en une archive Markdown.
 *
 * ## 🔴 Ce fichier est du clair sur le disque, et c'est le point délicat
 *
 * L'archive contient le texte intégral des notes — y compris celui des coffres **ouverts au moment
 * de l'export**, déchiffré exprès pour le destinataire. Elle vit dans `cache/exports/`, hors de
 * portée des autres applications, et le mode panique la détruit ; mais entre sa création et son
 * partage, c'est le seul endroit du téléphone où les notes d'un coffre existent en clair sur un
 * support persistant.
 *
 * Trois conséquences, toutes délibérées :
 *
 * 1. Un seul répertoire racine, `cache/exports/`, pour que **un seul geste** suffise à tout effacer.
 * 2. Chaque export écrit dans **son propre sous-répertoire**, et rien n'est purgé en cours de
 *    session. Voir [preparerRepertoire] : la règle précédente — vider la racine avant chaque export
 *    — retirait le fichier d'un partage encore ouvert.
 * 3. Une note de coffre **fermé** n'est pas exportée. Écrire son blob dans un `.md` produirait un
 *    fichier illisible que l'utilisateur croirait être sa note ; l'omettre et le **dire** est la
 *    seule issue honnête — d'où [ExportResult.skippedLocked].
 */
@Singleton
class NoteExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notes: NotesRepository,
    private val folders: FoldersRepository,
    private val vaults: FolderVaultService,
    private val clock: Clock,
) {

    /**
     * Un seul export écrit à la fois.
     *
     * Cet objet est unique dans le graphe d'injection, et les deux chemins d'export partagent la
     * racine `cache/exports/`. Sans verrou, l'archive des réglages et l'export d'une note depuis
     * l'éditeur peuvent se chevaucher.
     *
     * ⚠️⚠️ **Ce verrou sérialise les écritures. Il ne protège PAS la durée de vie du fichier**, et
     * la première version de ce commentaire prétendait le contraire — « toute la fenêtre pendant
     * laquelle le fichier doit exister ». C'est faux : le verrou tombe quand la fonction rend son
     * [ExportResult], et le partage Android commence **après**. Rien n'empêchait un second export
     * d'acquérir le verrou et de purger le fichier qu'un partage encore ouvert désignait.
     *
     * Le scénario était atteignable dès aujourd'hui, avec le seul `exportAll` : exporter, laisser le
     * sélecteur de partage ouvert, revenir, exporter à nouveau. Relevé par la relecture externe du
     * 2026-08-15 — sur le correctif écrit le matin même, et sur son commentaire.
     *
     * Ce qui ferme réellement le trou est [preparerRepertoire], qui ne purge plus rien. Le verrou
     * reste utile pour ce qu'il sait faire : deux exports n'écrivent pas en même temps, et le coût
     * est nul sur un geste déclenché à la main.
     */
    private val verrou = Mutex()

    /**
     * Fabrique l'archive et rend de quoi la partager.
     *
     * @param inboxLabel le nom affiché de la boîte de réception, dans la langue de l'interface.
     *   ⚠️ Il sert au **frontmatter**, jamais au nom du dossier dans l'archive : deux exports faits
     *   dans deux langues doivent produire la même arborescence.
     * @param vaultMention appelé avec le nom d'un dossier coffre pour produire la mention portée en
     *   commentaire YAML des notes qui en viennent.
     */
    suspend fun exportAll(inboxLabel: String, vaultMention: (String) -> String): ExportResult =
        verrou.withLock { fabriquerLArchive(inboxLabel, vaultMention) }

    private suspend fun fabriquerLArchive(inboxLabel: String, vaultMention: (String) -> String): ExportResult =
        withContext(Dispatchers.IO) {
            val instant = clock.instant()
            val zone = ZoneId.systemDefault()

            val toutes = notes.listAllAlive()
            val dossiers = folders.listAll().associateBy { it.id }
            val ouverts = vaults.unlockedFolderIds.value

            val (exportables, dechiffres) = dechiffrerCeQuiPeutLEtre(toutes, ouverts)

            val repertoire = preparerRepertoire(instant)
            val nom = "notes-tech-export-${instant.toEpochMilli()}.zip"
            val fichier = File(repertoire, nom)

            // 🔴 **La fabrication de l'URI est DANS le `try`.**
            //
            // Elle était après. `FileProvider.getUriForFile` lève si l'autorité est mal déclarée ou
            // si le fichier tombe hors des chemins publiés — rare, mais alors l'exception remontait
            // en laissant **une archive complète, en clair, que personne ne partagera jamais**.
            // Elle attendait la purge du prochain démarrage. Relevé CONFIRMÉ par une relecture
            // externe (GPT-5.2, 2026-08-15).
            try {
                val bilan = fichier.outputStream().use { flux ->
                    NoteArchive.write(
                        output = flux,
                        notes = exportables,
                        foldersById = dossiers,
                        unlockedVaultFolderIds = dechiffres,
                        inboxLabel = inboxLabel,
                        vaultMention = { dossier -> vaultMention(dossier) },
                        exportedAt = instant,
                        zone = zone,
                    )
                }
                ExportResult(
                    uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", fichier),
                    fileName = nom,
                    exported = bilan.exported,
                    skippedLocked = bilan.skippedLocked,
                )
            } catch (e: Throwable) {
                // ⚠️ On **tente** de retirer le demi-fichier : il porterait du clair sans être
                // partagé. « Tente » et non « garantit » — cf. [effacerOuSignaler], dont l'échec est
                // journalisé et non propagé ; le commentaire promettait auparavant qu'aucun
                // demi-fichier ne restait, ce que `delete()` ne garantit pas.
                //
                // `Throwable` et non `Exception` parce qu'une annulation doit nettoyer elle aussi —
                // puis repartir telle quelle.
                effacerOuSignaler(fichier)
                throw e
            }
        }

    /**
     * Le fichier Markdown d'**une seule** note, prêt à partager.
     *
     * ⚠️⚠️ **L'origine « coffre » se lit AVANT le déchiffrement.**
     *
     * Une fois `vaults.decrypt` passé, la note en main est du clair ordinaire : plus rien en elle ne
     * dit qu'elle sortait d'un coffre. C'est précisément ce que le suffixe ` [unlocked]` et la
     * mention YAML servent à dire — et cette fonction les omettait tous les deux, là où le chemin
     * archive les pose. Un même secret exporté seul ou dans un lot ne portait pas la même marque :
     * jumeau asymétrique, relevé par la relecture externe du 2026-08-15.
     *
     * @param vaultMention appelé avec le nom du dossier coffre, comme dans [exportAll]. C'est une
     *   **fonction** et non un gabarit : passer `"%s"` puis formater casserait en silence le jour où
     *   la chaîne traduite gagne un paramètre.
     */
    suspend fun exportOne(note: Note, folderLabel: String, vaultMention: (String) -> String): ExportResult =
        verrou.withLock { fabriquerLeFichier(note, folderLabel, vaultMention) }

    private suspend fun fabriquerLeFichier(
        note: Note,
        folderLabel: String,
        vaultMention: (String) -> String,
    ): ExportResult = withContext(Dispatchers.IO) {
        val instant = clock.instant()
        // 🔴 **L'origine « coffre » se lit sur le DOSSIER, pas seulement sur la note.**
        //
        // Le critère était `note.isLocked`. Il rate le cas d'une note **en clair dans un dossier
        // coffre** — état bien réel : c'est celui qu'une conversion partielle laisse derrière elle,
        // et celui qu'une annulation arrivée trop tard produit en entier (`11-COFFRES.md` §10).
        // Cette note sortait alors **sans** suffixe ` [unlocked]` ni mention YAML, là où l'archive
        // les pose — deux exports du même secret, une seule marque. Jumeau asymétrique, relevé
        // CONFIRMÉ par une relecture externe (Gemini, 2026-08-15).
        //
        // ⚠️ Le déchiffrement, lui, reste conditionné à `isLocked` : une note déjà en clair n'a rien
        // à déchiffrer, et l'envoyer au coffre lèverait sur une note qui n'a jamais été scellée.
        val venaitDunCoffre = note.isLocked || dossierEstUnCoffre(note.folderId)
        val claire = if (note.isLocked) vaults.decrypt(note) else note
        val repertoire = preparerRepertoire(instant)
        val nom = NoteMarkdown.safeFileName(claire.title, claire.id, fromUnlockedVault = venaitDunCoffre)
        val fichier = File(repertoire, nom)

        // Même raison qu'à l'archive : l'URI se fabrique **dans** le `try`, sinon son échec laisse
        // un fichier en clair complet et orphelin.
        try {
            fichier.writeText(
                NoteMarkdown.render(
                    note = claire,
                    folderLabel = folderLabel,
                    vaultMention = if (venaitDunCoffre) vaultMention(folderLabel) else null,
                    zone = ZoneId.systemDefault(),
                ),
            )
            ExportResult(
                uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", fichier),
                fileName = nom,
                exported = 1,
                skippedLocked = 0,
            )
        } catch (e: Throwable) {
            effacerOuSignaler(fichier)
            throw e
        }
    }

    /**
     * Le dossier d'une note est-il un coffre ? **Une question qui ne doit jamais faire échouer un
     * export.**
     *
     * ⚠️ Elle interroge la base, là où le critère précédent (`note.isLocked`) se lisait en mémoire.
     * Une base fermée, indisponible ou corrompue ferait donc échouer un export d'une seule note qui
     * aurait parfaitement abouti avant ce correctif — une régression introduite par un correctif de
     * marquage, sur un chemin qui n'a rien à voir. Relevé par les DEUX relectures externes du
     * 2026-08-15.
     *
     * En cas de refus, on retombe sur ce que la note dit d'elle-même : le marquage ` [unlocked]` est
     * alors éventuellement omis, ce qui est exactement le comportement d'avant. **Perdre une
     * mention vaut mieux que perdre l'export.**
     */
    private suspend fun dossierEstUnCoffre(folderId: String): Boolean = try {
        folders.find(folderId)?.isVault == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "export : origine coffre indeterminee pour le dossier %s", folderId)
        false
    }

    /**
     * Efface un fichier d'export raté, et **dit** s'il n'y arrive pas.
     *
     * ⚠️ `File.delete()` rend un booléen que les deux chemins de rattrapage jetaient. Un effacement
     * refusé — verrou du système de fichiers, descripteur encore ouvert — laissait donc du clair sur
     * le disque **en silence**, sur le seul chemin dont le rôle est justement de n'en pas laisser.
     * C'est la règle du dépôt appliquée à un cas de plus : un contrôle qui ne regarde pas son
     * résultat n'en dit rien. Relevé CONFIRMÉ par une relecture externe (GPT-5.2, 2026-08-15).
     *
     * ⚠️⚠️ **Le `try` interne n'est pas décoratif, et sa première version manquait.**
     *
     * Cette fonction est appelée depuis un `catch` dont l'exception d'origine est la vraie cause. Or
     * `exists()` comme `delete()` peuvent lever une `SecurityException` : sans cette garde, elle
     * remontait **à la place** de l'exception d'origine, et l'appelant recevait un refus de
     * permission là où il fallait lire « espace insuffisant » ou « annulation ». Le KDoc affirmait
     * pourtant « on ne lève pas ici ». Relevé CONFIRMÉ par les DEUX relectures externes du
     * 2026-08-15 — sur un correctif écrit vingt minutes plus tôt, et sur son commentaire.
     *
     * ⚠️ **Ce nettoyage est un « au mieux », et il faut le dire.** Un `delete()` refusé laisse le
     * fichier ; on le journalise et on continue. Les `catch` appelants ne peuvent donc pas promettre
     * qu'aucun demi-fichier ne reste — seulement qu'on a essayé et qu'on sait si on a échoué. La
     * borne réelle reste la purge au démarrage.
     */
    private fun effacerOuSignaler(fichier: File) {
        try {
            if (fichier.exists() && !fichier.delete()) {
                Timber.e("export : le fichier en clair %s n'a PAS pu etre efface", fichier.name)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "export : effacement du fichier en clair %s refuse", fichier.name)
        }
    }

    /**
     * Déchiffre les notes des coffres **ouverts**, laisse les autres scellées.
     *
     * ⚠️ Un échec de déchiffrement ne fait pas échouer l'export : la note reste scellée, donc
     * [NoteArchive.write] la comptera comme omise. C'est le comportement voulu — le coffre a pu se
     * refermer entre le relevé des sessions ouvertes et cette boucle, et un export qui échoue en
     * entier pour une note vaut moins qu'un export complet à une note près, annoncé comme tel.
     */
    private suspend fun dechiffrerCeQuiPeutLEtre(
        toutes: List<Note>,
        ouverts: Set<String>,
    ): Pair<List<Note>, Set<String>> {
        val dechiffres = mutableSetOf<String>()
        val exportables = toutes.map { note ->
            if (!note.isLocked || note.folderId !in ouverts) {
                note
            } else {
                val claire = dechiffrerOuNull(note)
                if (claire != null) dechiffres += note.folderId
                // ⚠️ Le repli rend la note **encore scellée**, pas une note vide : elle sera donc
                // comptée comme omise par l'archive, et l'utilisateur le saura. Un repli qui aurait
                // rendu la note en clair partiel serait passé pour un export réussi.
                claire ?: note
            }
        }
        return exportables to dechiffres
    }

    private suspend fun dechiffrerOuNull(note: Note): Note? = try {
        vaults.decrypt(note)
    } catch (e: CancellationException) {
        // ⚠️ Une annulation n'est pas un échec de déchiffrement : l'avaler ferait passer un export
        // interrompu pour un export dont les coffres ont refusé de s'ouvrir.
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Crée un sous-répertoire **propre à cet export**, sans rien purger.
     *
     * ## ⚠️⚠️ Pourquoi on ne vide plus la racine avant chaque export
     *
     * C'était la règle précédente, et elle avait sa raison : ne jamais laisser s'accumuler du clair
     * dans le cache. Mais elle retirait le fichier sous les pieds d'un partage encore ouvert —
     * exporter, laisser le sélecteur affiché, revenir, exporter à nouveau, et la première adresse ne
     * désignait plus rien. Aucun message : le partage échouait chez l'application destinataire.
     *
     * Un verrou ne pouvait pas fermer ça, parce que la fenêtre à protéger n'est pas l'écriture mais
     * **la durée de vie de l'adresse partagée**, qui commence quand l'exporteur a fini et dont rien
     * ne signale la fin.
     *
     * ## Ce qu'on a gardé, et ce qu'on a lâché
     *
     * **Gardé** — la propriété qui protège vraiment : rien ne survit à la session. La racine est
     * effacée au démarrage ([purgerLesArchives]) et par le mode panique, tous deux par le même
     * chemin. Aucune archive n'attend dans le cache d'un jour sur l'autre.
     *
     * **Lâché** — « une seule archive à la fois ». Une session où l'utilisateur exporte trois fois
     * garde trois archives en clair jusqu'à la fermeture, au lieu d'une. C'est une exposition en
     * plus, bornée par les gestes de l'utilisateur, dans un répertoire privé, et que la panique
     * efface. Contre un partage cassé en silence, l'échange est bon.
     */
    private fun preparerRepertoire(instant: Instant): File {
        val racine = repertoireDExport(context)
        // ⚠️ L'horloge est injectée, donc figée dans les tests : deux exports peuvent porter le même
        // instant. Le suffixe évite qu'ils se retrouvent dans le même répertoire, où le second
        // écraserait le fichier du premier — la course qu'on vient de fermer, en plus petit.
        var candidat = File(racine, instant.toEpochMilli().toString())
        var suffixe = 1
        while (candidat.exists()) {
            candidat = File(racine, "${instant.toEpochMilli()}-$suffixe")
            suffixe++
        }
        candidat.mkdirs()
        return candidat
    }

    companion object {
        /**
         * Le seul endroit où une archive d'export est écrite.
         *
         * Exposé pour que le mode panique le purge **par le même chemin** : deux définitions de ce
         * répertoire, et la panique nettoierait un dossier que l'export n'utilise plus.
         */
        fun repertoireDExport(context: Context): File = File(context.cacheDir, "exports")

        /**
         * Efface les archives laissées par les sessions précédentes.
         *
         * ## 🔴 Ce n'est pas du ménage, c'est la fin de vie du fichier
         *
         * Une archive contient le texte intégral des notes, coffres ouverts compris. Rien dans le
         * partage Android ne dit quand le destinataire a fini de la lire : la supprimer à un délai
         * fixe après l'envoi, comme le fait la version publiée, la retire sous les pieds de qui met
         * quarante secondes à choisir une application.
         *
         * La règle retenue est donc : **le fichier vit tant que la session dure, jamais au-delà**.
         * Rien n'est purgé pendant la session — cf. [preparerRepertoire], où vider la racine avant
         * chaque export retirait le fichier d'un partage encore ouvert. C'est cet appel-ci, au
         * démarrage, qui borne la durée de vie, avec le mode panique et **par le même chemin**.
         *
         * ⚠️ À appeler au démarrage du processus. Sans cet appel, une archive survit à la fermeture
         * de l'application et attend indéfiniment dans le cache — et personne ne se rappelle
         * qu'elle est là.
         */
        fun purgerLesArchives(context: Context) {
            repertoireDExport(context).deleteRecursively()
        }
    }
}
