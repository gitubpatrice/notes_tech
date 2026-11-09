package com.filestech.notes_tech.data.scellee

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.importation.LongueurDUneNote
import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde
import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde.Refus
import com.filestech.notes_tech.security.sauvegarde.SecretDeSauvegarde
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** A note as it travels sealed (D-044, 3.4.0): what is shown of it, nothing of where it lived. */
data class NoteScellee(
    val titre: String,
    val contenu: String,
    val couleur: Int?,
    val etiquettes: List<String>,
    /** The sender asked: erased after its first reading on the recipient's phone (D-043). */
    val lectureUnique: Boolean,
)

/**
 * A `.ntnote` file, version 1 (D-044, 3.4.0): the encrypted backup's container
 * ([FichierDeSauvegarde] — Argon2id, AES-256-GCM, the 48-byte header as AAD) under its own magic,
 * `NTSEALED`, holding one note as JSON:
 *
 * ```
 * {"format":"notes-tech-sealed-note","version":1,"title":…,"content":…,"color":…|null,"tags":[…],"readOnce":…}
 * ```
 *
 * Sealed by a generated code of 24 signs, 120 bits ([SecretDeSauvegarde]) — shown once to the sender,
 * who sends it another way than the file. Only a code opens a sealed note: a typed text that is not one
 * is refused before any derivation.
 *
 * - **In memory**: a note is at most [LongueurDUneNote.CARACTERES_MAX] characters, and the file
 *   [TAILLE_MAX] bytes, checked before anything is read.
 * - **Nothing is believed before the tag**: the whole content is read — the tag checked — before the JSON
 *   is parsed. A wrong code and a damaged file look the same, as for the backup.
 * - **The limits of a note**, checked on what is read: a sealed note is untrusted input, like an import.
 */
object FichierDeNoteScellee {

    val MAGIQUE: ByteArray = "NTSEALED".toByteArray(Charsets.US_ASCII)

    /** 500 000 characters may weigh up to 1.5 MB in UTF-8, and the JSON escapes some: room for both. */
    const val TAILLE_MAX = 4L * 1024 * 1024

    const val EXTENSION = "ntnote"
    private const val FORMAT = "notes-tech-sealed-note"
    private const val VERSION = 1L
    private const val ETIQUETTES_MAX = 100
    private const val LONGUEUR_MAX_D_UNE_ETIQUETTE = 100

    enum class Raison {
        PAS_UNE_NOTE_SCELLEE,
        VERSION_INCONNUE,
        TROP_GROSSE,
        CODE_MAL_FORME,
        CODE_OU_FICHIER,
        ILLISIBLE,
    }

    class NoteScelleeRefuseeException(val raison: Raison, cause: Throwable? = null) :
        IOException("note scellee refusee : $raison", cause)

    /** Whether [debut] starts as a sealed note does — a first sorting, before any code is asked. */
    fun aLaMagique(debut: ByteArray): Boolean =
        debut.size >= MAGIQUE.size && debut.copyOf(MAGIQUE.size).contentEquals(MAGIQUE)

    /** [note], sealed by [code] — a code of [SecretDeSauvegarde.nouveauCode]. Slow: Argon2id, off the main thread. */
    fun sceller(note: NoteScellee, code: String): ByteArray {
        val secret = SecretDeSauvegarde.octets(requireNotNull(SecretDeSauvegarde.codeCanonique(code)))
        val enTete = FichierDeSauvegarde.nouvelEnTete(MAGIQUE)
        val cle = FichierDeSauvegarde.cle(secret, enTete)
        try {
            val sortie = ByteArrayOutputStream()
            // Closing the writer closes the cipher stream once, which writes the tag.
            JsonWriter(OutputStreamWriter(FichierDeSauvegarde.ecrire(sortie, cle, enTete), Charsets.UTF_8)).use {
                ecrireLeJson(it, note)
            }
            return sortie.toByteArray().also { check(it.size <= TAILLE_MAX) { "note scellee trop grosse" } }
        } finally {
            cle.fill(0)
            secret.fill(0)
        }
    }

    /**
     * The note sealed in [octets] by [saisie], the code as the recipient typed it. Slow: Argon2id.
     *
     * @throws NoteScelleeRefuseeException with the [Raison] to say.
     */
    fun ouvrir(octets: ByteArray, saisie: String): NoteScellee {
        if (octets.size > TAILLE_MAX) throw NoteScelleeRefuseeException(Raison.TROP_GROSSE)
        val code = SecretDeSauvegarde.codeCanonique(saisie) ?: throw NoteScelleeRefuseeException(Raison.CODE_MAL_FORME)
        val entree = ByteArrayInputStream(octets)
        val (enTete, octetsDeLEnTete) = lireLEnTete(entree)
        val clair = dechiffrer(entree, code, enTete, octetsDeLEnTete)
        try {
            return lire(clair)
        } finally {
            clair.fill(0)
        }
    }

    private fun lireLEnTete(entree: ByteArrayInputStream): Pair<FichierDeSauvegarde.EnTete, ByteArray> = try {
        FichierDeSauvegarde.lireLEnTete(entree, MAGIQUE)
    } catch (e: FichierDeSauvegarde.SauvegardeRefuseeException) {
        val raison = when (e.refus) {
            Refus.VERSION_INCONNUE, Refus.PARAMETRES_INCONNUS -> Raison.VERSION_INCONNUE
            Refus.TROP_GROS -> Raison.TROP_GROSSE
            Refus.PAS_UNE_SAUVEGARDE, Refus.TRONQUE -> Raison.PAS_UNE_NOTE_SCELLEE
        }
        throw NoteScelleeRefuseeException(raison, e)
    }

    /** The content, its tag checked: read to its end, where a wrong code throws. */
    private fun dechiffrer(
        entree: ByteArrayInputStream,
        code: String,
        enTete: FichierDeSauvegarde.EnTete,
        octetsDeLEnTete: ByteArray,
    ): ByteArray {
        val secret = SecretDeSauvegarde.octets(code)
        val cle = FichierDeSauvegarde.cle(secret, enTete)
        return try {
            FichierDeSauvegarde.ouvrir(entree, cle, enTete, octetsDeLEnTete).use { it.readBytes() }
        } catch (e: IOException) {
            throw NoteScelleeRefuseeException(Raison.CODE_OU_FICHIER, e)
        } finally {
            cle.fill(0)
            secret.fill(0)
        }
    }

    private fun lire(clair: ByteArray): NoteScellee = try {
        JsonReader(InputStreamReader(ByteArrayInputStream(clair), Charsets.UTF_8)).use(::lireLeJson)
    } catch (e: NoteScelleeRefuseeException) {
        throw e
    } catch (e: IOException) {
        throw NoteScelleeRefuseeException(Raison.ILLISIBLE, e)
    } catch (e: IllegalStateException) {
        throw NoteScelleeRefuseeException(Raison.ILLISIBLE, e)
    }

    private fun ecrireLeJson(json: JsonWriter, note: NoteScellee) {
        json.beginObject()
        json.name("format").value(FORMAT)
        json.name("version").value(VERSION)
        json.name("title").value(note.titre)
        json.name("content").value(note.contenu)
        json.name("color").also { if (note.couleur == null) it.nullValue() else it.value(note.couleur.toLong()) }
        json.name("tags").beginArray()
        note.etiquettes.forEach { json.value(it) }
        json.endArray()
        json.name("readOnce").value(note.lectureUnique)
        json.endObject()
    }

    private fun lireLeJson(json: JsonReader): NoteScellee {
        var format: String? = null
        var version = 0L
        var titre: String? = null
        var contenu: String? = null
        var couleur: Int? = null
        var etiquettes = emptyList<String>()
        var lectureUnique = false
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "format" -> format = json.nextString()
                "version" -> version = json.nextLong()
                "title" -> titre = json.nextString()
                "content" -> contenu = json.nextString()
                "color" -> couleur = if (json.peek() == JsonToken.NULL) json.nextNull().let { null } else json.nextInt()
                "tags" -> etiquettes = lireLesEtiquettes(json)
                "readOnce" -> lectureUnique = json.nextBoolean()
                // A later version may add fields: its version number says whether this one can read it.
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (format != FORMAT || version != VERSION) throw NoteScelleeRefuseeException(Raison.VERSION_INCONNUE)
        val lu = NoteScellee(titre.orEmpty(), contenu.orEmpty(), couleur, etiquettes, lectureUnique)
        val dansLesLimites = lu.titre.length <= NotesRepository.TITLE_MAX_LENGTH &&
            lu.contenu.length <= LongueurDUneNote.CARACTERES_MAX
        if (titre == null || contenu == null || !dansLesLimites) throw NoteScelleeRefuseeException(Raison.ILLISIBLE)
        return lu
    }

    private fun lireLesEtiquettes(json: JsonReader): List<String> {
        val lues = mutableListOf<String>()
        json.beginArray()
        while (json.hasNext()) {
            val etiquette = json.nextString()
            if (lues.size >= ETIQUETTES_MAX || etiquette.length > LONGUEUR_MAX_D_UNE_ETIQUETTE) {
                throw NoteScelleeRefuseeException(Raison.ILLISIBLE)
            }
            lues += etiquette
        }
        json.endArray()
        return lues
    }
}
