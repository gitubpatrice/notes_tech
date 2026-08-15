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
import java.io.File
import java.time.Clock
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
 * 1. Un seul répertoire, `cache/exports/`, pour que **un seul geste** suffise à tout effacer.
 * 2. Le répertoire est vidé **avant** chaque export : deux archives ne s'accumulent jamais, et une
 *    tentative interrompue ne laisse pas de moitié de fichier derrière elle.
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
     * ⚠️⚠️ **Un seul export à la fois, et ce n'est pas du confort.**
     *
     * `preparerRepertoire()` commence par `deleteRecursively()` sur `cache/exports/`, qui est
     * partagé par les deux chemins d'export. Cet objet étant unique dans le graphe d'injection, deux
     * exports partis de deux endroits — l'archive des réglages pendant qu'un éditeur exporte sa
     * note — se marchent dessus : le second efface le fichier que le premier est en train d'écrire,
     * ou celui qu'il vient de rendre. L'utilisateur reçoit alors une adresse de partage qui ne
     * désigne plus rien, sans erreur pour le lui dire.
     *
     * Le verrou porte de la préparation du répertoire jusqu'à la fabrication de l'adresse, c'est-à-
     * dire toute la fenêtre pendant laquelle le fichier doit exister. Sérialiser coûte une attente
     * sur un geste que l'utilisateur déclenche à la main et rarement — le prix est nul.
     *
     * Signalé comme PROBABLE par la relecture externe du 2026-08-15 ; le second chemin n'a pas
     * encore d'appelant, la course est donc latente et non observée. Elle s'ouvrirait à la phase 6.4
     * avec le menu de l'éditeur.
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

            val repertoire = preparerRepertoire()
            val nom = "notes-tech-export-${instant.toEpochMilli()}.zip"
            val fichier = File(repertoire, nom)

            val bilan = try {
                fichier.outputStream().use { flux ->
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
            } catch (e: Throwable) {
                // ⚠️ Un demi-fichier ne doit pas rester : il porterait du clair sans être partagé,
                // et le prochain export le laisserait là. `Throwable` et non `Exception` parce
                // qu'une annulation doit nettoyer elle aussi — puis repartir telle quelle.
                fichier.delete()
                throw e
            }

            ExportResult(
                uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", fichier),
                fileName = nom,
                exported = bilan.exported,
                skippedLocked = bilan.skippedLocked,
            )
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
        val venaitDunCoffre = note.isLocked
        val claire = if (venaitDunCoffre) vaults.decrypt(note) else note
        val repertoire = preparerRepertoire()
        val nom = NoteMarkdown.safeFileName(claire.title, claire.id, fromUnlockedVault = venaitDunCoffre)
        val fichier = File(repertoire, nom)

        try {
            fichier.writeText(
                NoteMarkdown.render(
                    note = claire,
                    folderLabel = folderLabel,
                    vaultMention = if (venaitDunCoffre) vaultMention(folderLabel) else null,
                    zone = ZoneId.systemDefault(),
                ),
            )
        } catch (e: Throwable) {
            fichier.delete()
            throw e
        }

        ExportResult(
            uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", fichier),
            fileName = nom,
            exported = 1,
            skippedLocked = 0,
        )
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
     * Vide et recrée `cache/exports/`.
     *
     * ⚠️ Le vidage est la moitié qui compte. Sans lui, chaque export laisserait le précédent en
     * place : au bout de quelques mois, le cache contiendrait l'historique complet des notes en
     * clair, que plus personne ne se rappelle avoir créé.
     */
    private fun preparerRepertoire(): File = repertoireDExport(context).apply {
        deleteRecursively()
        mkdirs()
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
         * La règle retenue est donc **une archive à la fois, effacée au démarrage suivant et avant
         * chaque nouvel export**. Le fichier vit tant que la session dure, jamais au-delà.
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
