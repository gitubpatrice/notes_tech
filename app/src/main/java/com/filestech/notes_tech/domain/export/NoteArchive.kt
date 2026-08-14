package com.filestech.notes_tech.domain.export

import com.filestech.notes_tech.core.text.DartTextSemantics
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * L'archive ZIP d'un ensemble de notes, rangées par dossier.
 *
 * ## ⚠️ Elle s'écrit dans un flux, elle ne se construit pas en mémoire
 *
 * La version publiée fabrique l'archive entière comme un tableau d'octets, puis l'écrit. Sur un
 * corpus de mille notes, cela veut dire tout le texte de l'utilisateur en clair dans le tas, en
 * double — une fois dans les notes déchiffrées, une fois dans l'archive compressée — pendant tout le
 * temps de l'encodage. Ici l'archive part vers son flux au fur et à mesure.
 *
 * Ce n'est pas qu'une économie : un pic de mémoire est aussi une durée d'exposition. Un dépôt
 * mémoire pris à cet instant contiendrait l'intégralité des notes, coffres déverrouillés compris.
 */
object NoteArchive {

    /**
     * Ce qu'une archive a réellement contenu.
     *
     * @param exported le nombre de notes écrites.
     * @param skippedLocked le nombre de notes **omises** parce que leur coffre était fermé. Ce
     *   compteur n'est pas décoratif : sans lui, l'interface annoncerait « export terminé » à
     *   quelqu'un dont une partie des notes manque, et il ne le découvrirait qu'en ayant besoin
     *   d'elles.
     */
    data class Summary(val exported: Int, val skippedLocked: Int)

    /** Le nom du fichier d'accueil, à la racine de l'archive. */
    private const val README_NAME = "README.md"

    /**
     * Écrit l'archive dans [output], qui est **fermé** en sortie.
     *
     * @param notes déjà filtrées par l'appelant : la corbeille n'a pas à figurer dans un export.
     *   Les notes encore scellées sont comptées dans [Summary.skippedLocked] et non écrites — un
     *   blob chiffré dans un fichier `.md` serait illisible et donnerait le sentiment d'un export
     *   corrompu.
     * @param unlockedVaultFolderIds les dossiers dont le coffre était ouvert au moment de l'export,
     *   donc dont les notes ont pu être déchiffrées par l'appelant.
     * @param vaultMention appelé avec le nom du dossier pour produire la mention portée en
     *   commentaire dans le frontmatter. `null` pour ne rien écrire.
     */
    fun write(
        output: OutputStream,
        notes: List<Note>,
        foldersById: Map<String, Folder>,
        unlockedVaultFolderIds: Set<String> = emptySet(),
        inboxLabel: String = NoteMarkdown.INBOX_DIR_NAME,
        vaultMention: (folderName: String) -> String? = { null },
        exportedAt: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Summary {
        val nomsUtilises = mutableMapOf<String, Int>()
        var ecrites = 0
        var omises = 0

        ZipOutputStream(output.buffered()).use { zip ->
            for (note in notes) {
                if (note.isLocked) {
                    omises++
                    continue
                }
                val dossier = foldersById[note.folderId]
                // ⚠️ `inboxLabel` n'entre PAS ici — il sert au frontmatter, pas à l'arborescence.
                val nomDeDossier = NoteMarkdown.safeFolderName(dossier, note.folderId)
                val ouvert = note.folderId in unlockedVaultFolderIds
                val nomDeFichier = NoteMarkdown.safeFileName(
                    title = note.title,
                    fallbackId = note.id,
                    fromUnlockedVault = ouvert,
                )
                val chemin = desambigue(nomsUtilises, "$nomDeDossier/$nomDeFichier")

                val libelle = dossier?.name
                    ?: if (note.folderId == Folder.INBOX_ID) inboxLabel else note.folderId
                val contenu = NoteMarkdown.render(
                    note = note,
                    folderLabel = libelle,
                    vaultMention = if (ouvert) vaultMention(libelle) else null,
                    zone = zone,
                )
                zip.ecrire(chemin, contenu, exportedAt)
                ecrites++
            }

            zip.ecrire(README_NAME, NoteMarkdown.readme(ecrites, exportedAt, zone), exportedAt)
        }

        return Summary(exported = ecrites, skippedLocked = omises)
    }

    private fun ZipOutputStream.ecrire(nom: String, contenu: String, horodatage: Instant) {
        val entree = ZipEntry(nom).apply { time = horodatage.toEpochMilli() }
        putNextEntry(entree)
        write(contenu.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /**
     * Rend un chemin unique dans l'archive, en suffixant `-2`, `-3`… avant l'extension.
     *
     * ## ⚠️ La clé de comptage est en minuscules, la valeur rendue garde sa casse
     *
     * Sans cela, deux notes intitulées « Reiki » et « reiki » produiraient deux entrées distinctes
     * dans l'archive — et une seule chez le destinataire, parce que l'extraction sur FAT32 comme
     * sous Windows ne distingue pas la casse. La perte est **silencieuse** : l'archive est valide,
     * l'écrasement se produit à la décompression.
     *
     * ## ⚠️ Le suffixe peut lui-même être déjà pris
     *
     * Trois notes « Note », « Note-2 », « Note » produisaient deux entrées `note-2.md` : la boucle
     * ci-dessous est ce qui l'empêche. C'est un cas qu'on n'imagine pas et qu'un utilisateur qui
     * numérote ses titres rencontre au premier export.
     */
    private fun desambigue(utilises: MutableMap<String, Int>, chemin: String): String {
        val cle = DartTextSemantics.lowercase(chemin)
        val vues = utilises[cle] ?: 0
        utilises[cle] = vues + 1
        if (vues == 0) return chemin

        val point = chemin.lastIndexOf('.')
        val racine = if (point < 0) chemin else chemin.substring(0, point)
        val extension = if (point < 0) "" else chemin.substring(point)

        var n = vues + 1
        var candidat = "$racine-$n$extension"
        while (DartTextSemantics.lowercase(candidat) in utilises) {
            n++
            candidat = "$racine-$n$extension"
        }
        utilises[DartTextSemantics.lowercase(candidat)] = 1
        return candidat
    }
}
