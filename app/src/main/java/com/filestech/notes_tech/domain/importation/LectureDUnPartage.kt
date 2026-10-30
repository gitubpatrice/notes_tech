package com.filestech.notes_tech.domain.importation

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.ZoneId

/**
 * What "Share → Notes Tech" sent, turned into notes (3.2.0, `docs/13-VERSION-3.2.md` §4).
 *
 * Pure: the Android side reads the bytes and hands them here; everything this decides — the bounds,
 * the text versus the files, the decoding — is tested on the JVM.
 *
 * - **Files win over text.** An app that shares a file often adds a text describing it; the file is
 *   what the user chose.
 * - **A file is read like an imported one** ([LecteurMarkdown]): a note exported by Notes Tech and
 *   shared back keeps its title, tags and dates.
 * - **Strict UTF-8.** A file that is not text — a picture shared by mistake — is counted, never
 *   imported as a note full of replacement characters.
 * - 🔴 **One file at a time, under one budget** (security review of 3.2.0, M2): 50 files of 5 MiB each
 *   once made 250 MiB held at the same time, and an `OutOfMemoryError` — not an exception any `catch`
 *   here sees — took the whole app down, a note being edited with it. Each file is read, decoded and
 *   let go before the next; once [BUDGET] bytes are read, the rest is counted, not read.
 */
object LectureDUnPartage {

    /**
     * A shared text longer than this is refused, not cut: cutting would lose its end silently. See
     * [LongueurDUneNote].
     */
    const val TEXTE_MAX = LongueurDUneNote.CARACTERES_MAX

    /** Bytes read per file, before its text is counted — see [LongueurDUneNote]. */
    const val FICHIER_MAX = LongueurDUneNote.OCTETS_MAX

    /** For the whole share: what is read, at most, all files together. */
    const val BUDGET = 16 * 1024 * 1024

    /** Files in one share; the rest is counted as ignored. */
    const val FICHIERS_MAX = 20

    /** A shared text, with no file: one note titled by its subject, else its first line. */
    fun lireUnTexte(texte: String?, sujet: String?): PartageRecu {
        if (texte.isNullOrBlank() || texte.length > TEXTE_MAX) return PartageRecu(notes = emptyList(), ignores = 1)
        val note = NoteImportee(
            titre = LecteurMarkdown.titreDUnTexte(sujet, texte),
            contenu = texte,
            etiquettes = emptyList(),
            dossier = null,
            creeeLe = null,
            modifieeLe = null,
            epinglee = false,
            favorite = false,
        )
        return PartageRecu(notes = listOf(note), ignores = 0)
    }

    /**
     * The [nombre] files of a share, read one by one through [lire].
     *
     * @param lire reads the file of that index — its name, and its first `maxOctets + 1` bytes at most
     *   (one more than allowed is how an oversized file is told), or `null` bytes when it cannot be
     *   opened. Called at most [FICHIERS_MAX] times, never once the budget is spent.
     */
    fun lireDesFichiers(
        nombre: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        lire: (index: Int, maxOctets: Int) -> FichierPartage,
    ): PartageRecu {
        val notes = mutableListOf<NoteImportee>()
        var budget = BUDGET
        val lus = minOf(nombre, FICHIERS_MAX)
        for (index in 0 until lus) {
            if (budget <= 0) break
            val maxOctets = minOf(FICHIER_MAX, budget)
            val fichier = lire(index, maxOctets)
            val octets = fichier.octets
            budget -= octets?.size ?: 0
            val note = octets?.takeIf {
                it.size <= maxOctets
            }?.let(::texteDUneNote)?.let { LecteurMarkdown.lire(it, fichier.nom, zone) }
            if (note != null) notes += note
        }
        return PartageRecu(notes = notes, ignores = nombre - notes.size)
    }
}

/**
 * [octets] as text if they are UTF-8 and nothing else — never a text full of replacement characters
 * from a file that is not one. Shared by the share and the import (3.2.0).
 */
internal fun utf8Strict(octets: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(octets))
        .toString()
        // A NUL is what a binary file has and a text never does.
        .takeIf { '\u0000' !in it }
} catch (_: CharacterCodingException) {
    null
}

/** One file of a share: its display name, and its bytes (`null` when it could not be read). */
class FichierPartage(val nom: String?, val octets: ByteArray?)

/** The notes a share produced, and how many of its items could not be read. */
data class PartageRecu(val notes: List<NoteImportee>, val ignores: Int)
