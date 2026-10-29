package com.filestech.notes_tech.domain.importation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * A Markdown file read back into a note (3.2.0): what a share from another app and the Markdown import
 * both go through, so that a file means the same thing whichever door it comes in by.
 *
 * ## What it reads
 *
 * - **The frontmatter of Notes Tech's own export** (`NoteMarkdown.render`), exactly: double-quoted
 *   strings with its escapes (`\\`, `\"`, `\n`, `\r`, `\t`, `\xHH`), local dates without an offset,
 *   inline tag lists. A note exported then imported keeps its title, tags, dates, pin and favourite.
 * - **The common forms of other tools** (Obsidian, Logseq, Bear): unquoted values, single quotes, a
 *   block list of tags (`- tag`), dates with an offset or a date alone.
 * - Unknown keys are ignored; a frontmatter that never closes is not one, and the whole file is the body.
 * - Windows files: a byte-order mark is dropped and `\r\n` becomes `\n`.
 *
 * It never fails: whatever the text, the result is a note. Bounds (file size, number of files) are
 * the caller's, which knows where the text came from.
 */
object LecteurMarkdown {

    /** The longest title the repository accepts (`NotesRepository.TITLE_MAX_LENGTH`). */
    const val TITRE_MAX = 200

    /**
     * @param nomDuFichier the file's name, if there is one: the title when the frontmatter gives none.
     * @param zone the time zone of a date written without an offset — the export's own form.
     */
    fun lire(texte: String, nomDuFichier: String? = null, zone: ZoneId = ZoneId.systemDefault()): NoteImportee {
        val propre = texte.removePrefix("\uFEFF").replace("\r\n", "\n")
        val (champs, corps) = separer(propre)
        val titre = champs["title"]?.let(::scalaire)?.takeIf { it.isNotBlank() }
            ?: nomDuFichier?.let(::titreDuNom)?.takeIf { it.isNotBlank() }
            ?: ""
        return NoteImportee(
            titre = tronquer(titre.trim()),
            contenu = corps,
            // ⚠️ Split on commas too: tags are stored comma-joined, unescaped (`TagCodec`), so a tag
            // carrying one would come back as two anyway — better now than half-broken in the database.
            etiquettes = champs["tags"]?.let(::liste).orEmpty()
                .flatMap { it.split(',') }.map(String::trim).filter(String::isNotEmpty).distinct(),
            dossier = champs["folder"]?.let(::scalaire)?.takeIf { it.isNotBlank() },
            creeeLe = champs["created"]?.let { date(scalaire(it), zone) },
            modifieeLe = champs["updated"]?.let { date(scalaire(it), zone) },
            epinglee = champs["pinned"]?.let(::scalaire)?.trim() == "true",
            favorite = champs["favorite"]?.let(::scalaire)?.trim() == "true",
        )
    }

    /** A title for a shared text: its subject if it has one, otherwise its first non-blank line. */
    fun titreDUnTexte(sujet: String?, texte: String): String = tronquer(
        (
            sujet?.takeIf {
                it.isNotBlank()
            } ?: texte.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            ).trim(),
    )

    // ── Frontmatter ─────────────────────────────────────────────────────────────────────────────

    /**
     * The raw values by key, and the body. A value is the rest of its line; a block list (`tags:` then
     * `  - a` lines) is kept as its lines, joined by `\n`, for [liste].
     */
    private fun separer(texte: String): Pair<Map<String, String>, String> {
        if (!texte.startsWith("---\n")) return emptyMap<String, String>() to texte
        val fin = texte.indexOf("\n---", startIndex = 3)
        if (fin < 0) return emptyMap<String, String>() to texte
        val apresFin = texte.indexOf('\n', fin + 4).let { if (it < 0) texte.length else it + 1 }
        if (texte.substring(fin + 4, if (apresFin == texte.length) texte.length else apresFin - 1).isNotBlank()) {
            return emptyMap<String, String>() to texte
        }
        val champs = linkedMapOf<String, String>()
        var cleCourante: String? = null
        for (ligne in texte.substring(4, fin + 1).lineSequence()) {
            val enListe = ligne.trimStart()
            val deuxPoints = ligne.indexOf(':')
            when {
                cleCourante != null && enListe.startsWith("- ") ->
                    champs[cleCourante] = champs[cleCourante].orEmpty() + "\n" + enListe.removePrefix("- ")
                // Blank, a comment, or an indented line that is not a list item.
                ligne.isBlank() || enListe.startsWith("#") || ligne.first().isWhitespace() -> Unit
                deuxPoints <= 0 -> cleCourante = null
                else -> {
                    cleCourante = ligne.substring(0, deuxPoints).trim()
                    champs[cleCourante] = ligne.substring(deuxPoints + 1).trim()
                }
            }
        }
        // The export writes a blank line after the closing fence, and a final line break the note may
        // not have had.
        val corps = texte.substring(apresFin).removePrefix("\n").removeSuffix("\n")
        return champs to corps
    }

    /** A YAML scalar: double-quoted with escapes, single-quoted, or plain. */
    private fun scalaire(brut: String): String {
        val valeur = brut.trim()
        return when {
            valeur.length >= 2 && valeur.startsWith('"') && valeur.endsWith('"') ->
                desechapper(valeur.substring(1, valeur.length - 1))
            valeur.length >= 2 && valeur.startsWith('\'') && valeur.endsWith('\'') ->
                valeur.substring(1, valeur.length - 1).replace("''", "'")
            else -> valeur.substringBefore(" #")
        }
    }

    /** `[a, "b, c"]`, a block list, or a single value. */
    private fun liste(brut: String): List<String> {
        val valeur = brut.trim()
        if (valeur.contains('\n')) {
            return valeur.split('\n').map(String::trim).filter(String::isNotEmpty).map(::scalaire)
        }
        if (!valeur.startsWith('[') ||
            !valeur.endsWith(']')
        ) {
            return listOfNotNull(valeur.takeIf { it.isNotEmpty() }?.let(::scalaire))
        }
        val elements = mutableListOf<String>()
        val courant = StringBuilder()
        var guillemet: Char? = null
        var echappe = false
        for (c in valeur.substring(1, valeur.length - 1)) {
            when {
                echappe -> {
                    courant.append(c)
                    echappe = false
                }
                guillemet == '"' && c == '\\' -> {
                    courant.append(c)
                    echappe = true
                }
                guillemet != null && c == guillemet -> {
                    courant.append(c)
                    guillemet = null
                }
                guillemet == null && (c == '"' || c == '\'') -> {
                    courant.append(c)
                    guillemet = c
                }
                guillemet == null && c == ',' -> {
                    elements += courant.toString()
                    courant.clear()
                }
                else -> courant.append(c)
            }
        }
        elements += courant.toString()
        return elements.map(String::trim).filter(String::isNotEmpty).map(::scalaire)
    }

    /** The escapes `NoteMarkdown.yamlString` writes, and nothing else: an unknown one is kept as written. */
    private fun desechapper(s: String): String {
        val sortie = StringBuilder(s.length)
        var i = 0
        while (i < s.length) i += lireUnCaractere(s, i, sortie)
        return sortie.toString()
    }

    /** Appends what starts at [i] — one character, or one escape — and returns how many it consumed. */
    private fun lireUnCaractere(s: String, i: Int, sortie: StringBuilder): Int {
        if (s[i] != '\\' || i + 1 >= s.length) {
            sortie.append(s[i])
            return 1
        }
        val simple = when (s[i + 1]) {
            '\\' -> '\\'
            '"' -> '"'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            else -> null
        }
        val code = if (s[i + 1] == 'x' && i + 4 <= s.length) s.substring(i + 2, i + 4).toIntOrNull(16) else null
        return when {
            simple != null -> 2.also { sortie.append(simple) }
            code != null -> 4.also { sortie.append(code.toChar()) }
            else -> 2.also { sortie.append('\\').append(s[i + 1]) }
        }
    }

    /** The export's local form first, then an offset, then a date alone (at midnight). Else none. */
    private fun date(texte: String, zone: ZoneId): Instant? {
        val t = texte.trim()
        return try {
            LocalDateTime.parse(t).atZone(zone).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(t).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(t).atStartOfDay(zone).toInstant()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    private fun titreDuNom(nom: String): String {
        val seul = nom.substringAfterLast('/')
        val sansExtension = seul.substringBeforeLast('.', seul)
        // The suffix the export adds to a note of an unlocked vault (`NoteMarkdown.safeFileName`).
        return sansExtension.removeSuffix(" [unlocked]")
    }

    /** At most [TITRE_MAX] UTF-16 units, never half of a surrogate pair. */
    private fun tronquer(titre: String): String {
        if (titre.length <= TITRE_MAX) return titre
        val coupe = titre.take(TITRE_MAX)
        return if (coupe.last().isHighSurrogate()) coupe.dropLast(1) else coupe
    }
}

/** A note read from a Markdown file, before it has an id or a folder. */
data class NoteImportee(
    val titre: String,
    val contenu: String,
    val etiquettes: List<String>,
    /** The `folder:` of the frontmatter, if any — the export's folder label. */
    val dossier: String?,
    val creeeLe: Instant?,
    val modifieeLe: Instant?,
    val epinglee: Boolean,
    val favorite: Boolean,
)
