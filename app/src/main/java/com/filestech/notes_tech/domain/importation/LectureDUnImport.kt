package com.filestech.notes_tech.domain.importation

import com.filestech.notes_tech.core.text.DartTextSemantics
import com.filestech.notes_tech.domain.export.NoteArchive
import com.filestech.notes_tech.domain.export.NoteMarkdown
import com.filestech.notes_tech.domain.model.Note
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * What the Settings' "Import" reads (3.2.0, `docs/13-VERSION-3.2.md` D-032): Markdown or text files,
 * or ZIPs of them — an export of Notes Tech, an Obsidian vault — turned into notes, each with the
 * ordinary folder it goes to. Documents are added one by one ([ajouter]), the answer read at the end
 * ([resultat]).
 *
 * Pure: the Android side opens the documents the user chose; everything decided here — the bounds,
 * the folders, what is a note and what is not — is tested on the JVM.
 *
 * - **A file alone goes to the Inbox.** In a ZIP, the first level of its path names an ordinary folder
 *   (`inbox/`, the export's own name for the Inbox, the Inbox); a file at the root, the Inbox.
 * - The export's `README.md` is left out; so is what is not a document — `__MACOSX/`, `.DS_Store`, a
 *   `.obsidian/` folder: anything hidden, by a name starting with a dot. Neither counts as ignored.
 * - **Strict UTF-8**, [LongueurDUneNote] characters per file: a file that is not text, or too long, is
 *   counted as ignored.
 * - 🔴 **Bounded on what is really read** — 100 MiB for the whole import, [FICHIERS_MAX] files.
 *   A ZIP read as a stream inflates the rest of an entry to reach the next one: an entry skipped is
 *   read all the same, so its bytes count too. Without that, one entry of zeros, a few kilobytes
 *   compressed, would keep the import inflating for hours. Past a bound, the import stops ([arrete]),
 *   and keeps what it had.
 * - 🔴 **Bounded on what is kept** — [TEXTE_RETENU_MAX] characters of notes: every note read waits in
 *   memory for the one transaction that writes them all (D-032), and 100 MiB of text — 200 MiB as
 *   UTF-16 — exceeds the heap of a modest phone, with an `OutOfMemoryError` no `catch` sees (audit 3
 *   axes of 3.2.0, P1; the share had the same defect, M2 of its review). Past it, the import stops too.
 * - **A folder's name from an archive is cleaned** ([nomDeDossier]): no control or bidirectional
 *   formatting character, which could disguise it in the folders' drawer, and at most
 *   [NOM_DE_DOSSIER_MAX] characters (same audit, P2).
 */
class LectureDUnImport(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val texteRetenuMax: Long = TEXTE_RETENU_MAX,
) {

    private val notes = ArrayList<NoteAImporter>()
    private var ignores = 0
    private var fichiers = 0
    private var lus = 0L
    private var retenus = 0L
    private var arrete = false

    /**
     * One document the user chose: [nom], its display name; [flux], its bytes — `null` when it could
     * not be opened. The caller closes [flux].
     */
    fun ajouter(nom: String?, flux: InputStream?) {
        val nomDuDocument = nom.orEmpty()
        try {
            when {
                arrete || flux == null -> ignores++
                nomDuDocument.estUneArchive() -> lireUneArchive(flux)
                !nomDuDocument.estUneNote() -> ignores++
                compterUnFichier() -> lireUneNote(nomDuDocument, flux, dossier = null)
                else -> ignores++
            }
        } catch (_: IOException) {
            // A provider failing in the middle of a read (a cloud document, a removed card).
            ignores++
        }
    }

    fun resultat(): Import = Import(notes = notes.toList(), ignores = ignores, arrete = arrete)

    private fun lireUneArchive(flux: InputStream) {
        try {
            val zip = ZipInputStream(flux)
            while (!arrete) {
                val entree = zip.nextEntry ?: break
                val chemin = entree.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                when {
                    entree.isDirectory -> Unit
                    // Not a document: read through — its bytes count — and not counted.
                    chemin.isEmpty() || chemin.any { it.startsWith('.') || it == DOSSIER_MACOS } -> lireBorne(zip, 0)
                    !compterUnFichier() -> Unit
                    !chemin.last().estUneNote() -> {
                        ignores++
                        lireBorne(zip, 0)
                    }
                    chemin.size == 1 -> lireUneNote(chemin.single(), zip, dossier = null, racine = true)
                    else -> lireUneNote(chemin.last(), zip, dossier = dossierDe(chemin.first()))
                }
            }
        } catch (_: IOException) {
            // A damaged archive (ZipException is one): what was read before it is kept.
            ignores++
        } catch (_: IllegalArgumentException) {
            // An entry name that is not valid in the archive's encoding.
            ignores++
        }
    }

    private fun lireUneNote(nom: String, flux: InputStream, dossier: String?, racine: Boolean = false) {
        val texte = lireBorne(flux, FICHIER_MAX)?.let(::texteDUneNote)
        when {
            arrete -> Unit
            texte == null -> ignores++
            racine && nom == NoteArchive.README_NAME && texte.startsWith(NoteMarkdown.README_HEADING) -> Unit
            retenus + texte.length > texteRetenuMax -> arrete = true
            else -> {
                retenus += texte.length
                notes += NoteAImporter(LecteurMarkdown.lire(texte, nom, zone), dossier)
            }
        }
    }

    /** One more file; past [FICHIERS_MAX], the import stops. */
    private fun compterUnFichier(): Boolean {
        if (fichiers == FICHIERS_MAX) {
            arrete = true
            return false
        }
        fichiers++
        return true
    }

    /**
     * Reads [flux] to its end, keeping at most [max] bytes: what it holds, or `null` past [max]. Every
     * byte read counts toward [LUS_MAX], kept or not; past it, the import stops.
     */
    private fun lireBorne(flux: InputStream, max: Int): ByteArray? {
        val gardes = ByteArrayOutputStream()
        val tampon = ByteArray(TAMPON)
        var trop = false
        while (true) {
            val n = flux.read(tampon)
            if (n < 0) break
            lus += n
            if (lus > LUS_MAX) {
                arrete = true
                return null
            }
            if (!trop && gardes.size() + n <= max) gardes.write(tampon, 0, n) else trop = true
        }
        return if (trop) null else gardes.toByteArray()
    }

    /** The folder a ZIP's first-level directory names: `null` for the Inbox. */
    private fun dossierDe(repertoire: String): String? {
        val nom = nomDeDossier(repertoire)
        return if (nom.isEmpty() || nom == NoteMarkdown.INBOX_DIR_NAME) null else nom
    }

    private fun String.estUneArchive() = endsWith(".zip", ignoreCase = true)

    private fun String.estUneNote() = EXTENSIONS.any { endsWith(it, ignoreCase = true) }

    companion object {
        /** Files considered in one import, the documents chosen and the entries of their archives. */
        const val FICHIERS_MAX = 5_000

        /** Bytes read per file, before its text is counted — see [LongueurDUneNote]. */
        const val FICHIER_MAX = LongueurDUneNote.OCTETS_MAX

        /** Bytes read in all, inflated ones included: an archive is bounded by what it holds, not its size. */
        const val LUS_MAX = 100L * 1024 * 1024

        /** Characters of notes kept until they are written: 60 MB of heap at most. */
        const val TEXTE_RETENU_MAX = 30_000_000L

        /** Characters of a folder's name taken from an archive. */
        const val NOM_DE_DOSSIER_MAX = 100

        /**
         * [repertoire] as a folder's name: control and bidirectional formatting characters dropped —
         * a right-to-left override shows "fdp.exe" as "exe.pdf" —, trimmed, at most
         * [NOM_DE_DOSSIER_MAX] characters, never cutting a surrogate pair.
         */
        internal fun nomDeDossier(repertoire: String): String {
            val propre = repertoire.filterNot { Character.isISOControl(it) || it in FORMATS_BIDI }
            val nom = DartTextSemantics.trim(propre)
            if (nom.length <= NOM_DE_DOSSIER_MAX) return nom
            val coupe = NOM_DE_DOSSIER_MAX
            val fin = if (Character.isHighSurrogate(nom[coupe - 1])) coupe - 1 else coupe
            return DartTextSemantics.trim(nom.substring(0, fin))
        }

        /** LRM, RLM, ALM, the embeddings and overrides, the isolates. */
        private val FORMATS_BIDI = setOf(
            '\u200E', '\u200F', '\u061C',
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069',
        )

        private val EXTENSIONS = listOf(".md", ".markdown", ".txt")
        private const val DOSSIER_MACOS = "__MACOSX"
        private const val TAMPON = 64 * 1024
    }
}

/** A note read by the import, and the ordinary folder it goes to by name — `null`: the Inbox. */
data class NoteAImporter(val note: NoteImportee, val dossier: String?)

/**
 * What an import read: its notes; how many documents or entries could not be read as one; and
 * whether a bound stopped it before the end ([LectureDUnImport.LUS_MAX], [LectureDUnImport.FICHIERS_MAX]).
 */
data class Import(val notes: List<NoteAImporter>, val ignores: Int, val arrete: Boolean)

/**
 * What an import wrote: its [notes]; the ordinary folders it had to create; and the notes sent to the
 * Inbox because the folder their archive named is a vault's.
 */
data class BilanDImport(val notes: List<Note>, val dossiersCrees: Int, val versLaBoiteDeReception: Int)
