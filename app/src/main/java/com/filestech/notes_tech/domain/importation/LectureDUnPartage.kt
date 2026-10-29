package com.filestech.notes_tech.domain.importation

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.ZoneId

/**
 * What "Share → Notes Tech" sent, turned into notes (3.2.0, `docs/13-VERSION-3.2.md` §4).
 *
 * Pure: the Android side reads the bytes (bounded) and hands them here; everything this decides — the
 * bounds, the text versus the files, the decoding — is tested on the JVM.
 *
 * - **Files win over text.** An app that shares a file often adds a text describing it; the file is
 *   what the user chose.
 * - **A file is read like an imported one** ([LecteurMarkdown]): a note exported by Notes Tech and
 *   shared back keeps its title, tags and dates.
 * - **Strict UTF-8.** A file that is not text — a picture shared by mistake — is counted, never
 *   imported as a note full of replacement characters.
 */
object LectureDUnPartage {

    /** A shared text longer than this is refused, not cut: cutting would lose its end silently. */
    const val TEXTE_MAX = 1_048_576

    /** Per file; the Android side stops reading at this many bytes plus one. */
    const val FICHIER_MAX = 5 * 1024 * 1024

    /** Files in one share; the rest is counted as ignored. */
    const val FICHIERS_MAX = 50

    /**
     * @param fichiers the files of the share, each read up to [FICHIER_MAX] + 1 bytes, or `null` bytes
     *   when it could not be opened.
     */
    fun lire(
        texte: String?,
        sujet: String?,
        fichiers: List<FichierPartage>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PartageRecu {
        if (fichiers.isNotEmpty()) {
            val pris = fichiers.take(FICHIERS_MAX)
            val notes = pris.mapNotNull { fichier ->
                val octets = fichier.octets?.takeIf { it.size <= FICHIER_MAX } ?: return@mapNotNull null
                val contenu = utf8Strict(octets) ?: return@mapNotNull null
                LecteurMarkdown.lire(contenu, fichier.nom, zone)
            }
            return PartageRecu(notes = notes, ignores = fichiers.size - notes.size)
        }
        if (texte.isNullOrBlank()) return PartageRecu(notes = emptyList(), ignores = 1)
        if (texte.length > TEXTE_MAX) return PartageRecu(notes = emptyList(), ignores = 1)
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

    private fun utf8Strict(octets: ByteArray): String? = try {
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
}

/** One file of a share: its display name, and its bytes (`null` when it could not be read). */
class FichierPartage(val nom: String?, val octets: ByteArray?)

/** The notes a share produced, and how many of its items could not be read. */
data class PartageRecu(val notes: List<NoteImportee>, val ignores: Int)
