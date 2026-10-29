package com.filestech.notes_tech.data.sauvegarde

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * What a `.ntbackup` file holds once decrypted (3.2.0, `docs/13-VERSION-3.2.md` "Format, version 1"):
 * JSON, written and read **as a stream** — `android.util.JsonWriter` / `JsonReader`, never a tree in
 * memory (R-8). The folders first, then the notes.
 *
 * A folder carries its vault as it is: a passphrase vault's columns, which only its phrase opens, on
 * any phone; or, for a PIN vault saved while open, its folder key — the Keystore that seals it on this
 * phone does not travel (D-030). A sealed note carries its blob untouched.
 */
internal object ContenuDeSauvegarde {

    const val FORMAT = "notes-tech-backup"
    const val VERSION = 1
}

internal data class DossierSauvegarde(
    val id: String,
    val nom: String,
    val parentId: String?,
    val couleur: Int?,
    val icone: String?,
    val creeLe: Long,
    val modifieLe: Long,
    val coffre: CoffreSauvegarde?,
)

internal sealed interface CoffreSauvegarde {
    /** A passphrase vault's columns, copied as they are. */
    class APhrase(val sel: ByteArray, val cleEmballee: ByteArray, val iv: ByteArray, val verificateur: ByteArray) :
        CoffreSauvegarde

    /** A PIN vault, saved open: its folder key. To be wiped once used. */
    class ACode(val cleDuDossier: ByteArray) : CoffreSauvegarde
}

internal data class NoteSauvegardee(
    val id: String,
    val dossierId: String,
    val titre: String,
    val contenu: String,
    /** The sealed blob of a vault note, untouched; `null` for a note in clear. */
    val scelle: ByteArray?,
    val versionDeChiffrement: Int,
    val etiquettes: List<String>,
    val epinglee: Boolean,
    val favorite: Boolean,
    val archivee: Boolean,
    val misALaCorbeilleLe: Long?,
    val couleur: Int?,
    val creeeLe: Long,
    val modifieeLe: Long,
)

/** Writes the content of a backup into [sortie] — the encrypting stream — which [close] flushes, not closes. */
internal class EcritureDuContenu(sortie: OutputStream, creeLe: Long) : Closeable {

    private val json = JsonWriter(OutputStreamWriter(sortie, Charsets.UTF_8))
    private var dansLesNotes = false

    init {
        json.beginObject()
        json.name("format").value(ContenuDeSauvegarde.FORMAT)
        json.name("version").value(ContenuDeSauvegarde.VERSION.toLong())
        json.name("createdAt").value(creeLe)
        json.name("folders").beginArray()
    }

    fun dossier(d: DossierSauvegarde) {
        check(!dansLesNotes) { "les dossiers avant les notes" }
        json.beginObject()
        json.name("id").value(d.id)
        json.name("name").value(d.nom)
        json.name("parentId").valeurOuNull(d.parentId)
        json.name("color").valeurOuNull(d.couleur?.toLong())
        json.name("icon").valeurOuNull(d.icone)
        json.name("createdAt").value(d.creeLe)
        json.name("updatedAt").value(d.modifieLe)
        json.name("vault")
        when (val coffre = d.coffre) {
            null -> json.nullValue()
            is CoffreSauvegarde.APhrase -> {
                json.beginObject()
                json.name("mode").value(MODE_PHRASE)
                json.name("salt").value(base64(coffre.sel))
                json.name("wrappedKey").value(base64(coffre.cleEmballee))
                json.name("iv").value(base64(coffre.iv))
                json.name("verifier").value(base64(coffre.verificateur))
                json.endObject()
            }
            is CoffreSauvegarde.ACode -> {
                json.beginObject()
                json.name("mode").value(MODE_CODE)
                json.name("folderKey").value(base64(coffre.cleDuDossier))
                json.endObject()
            }
        }
        json.endObject()
    }

    fun note(n: NoteSauvegardee) {
        if (!dansLesNotes) {
            json.endArray()
            json.name("notes").beginArray()
            dansLesNotes = true
        }
        json.beginObject()
        json.name("id").value(n.id)
        json.name("folderId").value(n.dossierId)
        json.name("title").value(n.titre)
        json.name("content").value(n.contenu)
        json.name("sealed").valeurOuNull(n.scelle?.let(::base64))
        json.name("encV").value(n.versionDeChiffrement.toLong())
        json.name("tags").beginArray()
        n.etiquettes.forEach { json.value(it) }
        json.endArray()
        json.name("pinned").value(n.epinglee)
        json.name("favorite").value(n.favorite)
        json.name("archived").value(n.archivee)
        json.name("trashedAt").valeurOuNull(n.misALaCorbeilleLe)
        json.name("colorId").valeurOuNull(n.couleur?.toLong())
        json.name("createdAt").value(n.creeeLe)
        json.name("updatedAt").value(n.modifieeLe)
        json.endObject()
    }

    /** Ends the JSON and flushes it; the caller closes the stream, which writes the GCM tag. */
    override fun close() {
        if (!dansLesNotes) {
            json.endArray()
            json.name("notes").beginArray()
        }
        json.endArray()
        json.endObject()
        json.flush()
    }

    private fun JsonWriter.valeurOuNull(valeur: String?): JsonWriter =
        if (valeur == null) nullValue() else value(valeur)

    private fun JsonWriter.valeurOuNull(valeur: Long?): JsonWriter = if (valeur == null) nullValue() else value(valeur)
}

/**
 * Reads the content of a backup from [entree] — the decrypting stream — handing each folder, then each
 * note, to the caller as it comes.
 *
 * 🔴 **Nothing read here is authenticated** until [lire] has returned: it drains [entree] to its very
 * end, which is where the GCM tag is checked, and refuses any byte after the JSON. Stopping at the
 * JSON's last brace would leave the tag unchecked — a file with its end changed would then pass.
 *
 * A malformed content — the usual face of a wrong secret, whose decryption is noise from the first
 * byte — throws [ContenuIllisibleException] or the reader's own [IOException]; both mean "this secret
 * does not open this file, or the file is damaged".
 */
internal object LectureDuContenu {

    class ContenuIllisibleException(raison: String) : IOException(raison)

    /**
     * @param surLesDossiers called once, with every folder, before the first note: deciding where a
     *   note goes needs the whole tree. Folders are few; notes are streamed.
     */
    suspend fun lire(
        entree: InputStream,
        surLesDossiers: suspend (List<DossierSauvegarde>) -> Unit,
        surUneNote: suspend (NoteSauvegardee) -> Unit,
    ) {
        val json = JsonReader(InputStreamReader(entree, Charsets.UTF_8))
        var format: String? = null
        var version = -1L
        var dossiersVus = false
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "format" -> format = json.nextString()
                "version" -> version = json.nextLong()
                "folders" -> {
                    exigerLEnTete(format, version)
                    val dossiers = ArrayList<DossierSauvegarde>()
                    json.beginArray()
                    while (json.hasNext()) dossiers += lireUnDossier(json)
                    json.endArray()
                    surLesDossiers(dossiers)
                    dossiersVus = true
                }
                "notes" -> {
                    if (!dossiersVus) throw ContenuIllisibleException("notes avant les dossiers")
                    json.beginArray()
                    while (json.hasNext()) surUneNote(lireUneNote(json))
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (!dossiersVus) throw ContenuIllisibleException("aucun dossier")
        // The JSON is over: whatever follows must be nothing — and reaching the end checks the tag.
        if (json.peek() != JsonToken.END_DOCUMENT) throw ContenuIllisibleException("octets apres le contenu")
        // END_DOCUMENT means the reader met the end of [entree]; read it once more all the same: the
        // check that the tag was reached must not depend on how a JSON reader buffers.
        if (entree.read() >= 0) throw ContenuIllisibleException("octets apres le contenu")
    }

    private fun exigerLEnTete(format: String?, version: Long) {
        if (format != ContenuDeSauvegarde.FORMAT || version != ContenuDeSauvegarde.VERSION.toLong()) {
            throw ContenuIllisibleException("format $format, version $version")
        }
    }

    private fun lireUnDossier(json: JsonReader): DossierSauvegarde {
        val champs = lireUnObjet(json, mapOf("vault" to { lireUnCoffre(json) }))
        return DossierSauvegarde(
            id = champs.texte("id"),
            nom = champs.texte("name"),
            parentId = champs.texteOuNull("parentId"),
            couleur = champs.entierOuNull("color"),
            icone = champs.texteOuNull("icon"),
            creeLe = champs.long("createdAt"),
            modifieLe = champs.long("updatedAt"),
            coffre = champs["vault"] as CoffreSauvegarde?,
        )
    }

    private fun lireUnCoffre(json: JsonReader): CoffreSauvegarde? {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull()
            return null
        }
        val champs = lireUnObjet(json, emptyMap())
        return when (champs.texte("mode")) {
            MODE_PHRASE -> CoffreSauvegarde.APhrase(
                sel = champs.octets("salt"),
                cleEmballee = champs.octets("wrappedKey"),
                iv = champs.octets("iv"),
                verificateur = champs.octets("verifier"),
            )
            MODE_CODE -> CoffreSauvegarde.ACode(champs.octets("folderKey"))
            else -> throw ContenuIllisibleException("mode de coffre inconnu")
        }
    }

    private fun lireUneNote(json: JsonReader): NoteSauvegardee {
        val etiquettes = ArrayList<String>()
        val champs = lireUnObjet(
            json,
            mapOf(
                "tags" to {
                    json.beginArray()
                    while (json.hasNext()) etiquettes += json.nextString()
                    json.endArray()
                    etiquettes
                },
            ),
        )
        return NoteSauvegardee(
            id = champs.texte("id"),
            dossierId = champs.texte("folderId"),
            titre = champs.texte("title"),
            contenu = champs.texte("content"),
            scelle = champs.texteOuNull("sealed")?.let(::deBase64),
            versionDeChiffrement = champs.entier("encV"),
            etiquettes = etiquettes,
            epinglee = champs.booleen("pinned"),
            favorite = champs.booleen("favorite"),
            archivee = champs.booleen("archived"),
            misALaCorbeilleLe = champs.longOuNull("trashedAt"),
            couleur = champs.entierOuNull("colorId"),
            creeeLe = champs.long("createdAt"),
            modifieeLe = champs.long("updatedAt"),
        )
    }

    /**
     * One JSON object as a map of its plain values — strings, numbers as written, booleans, nulls —
     * the fields of [lecteurs] read by their own reader, whatever value it returns (`null` included).
     * An unknown field is skipped: a later version may add some. A field twice is refused.
     */
    private fun lireUnObjet(json: JsonReader, lecteurs: Map<String, () -> Any?>): Map<String, Any?> {
        val champs = HashMap<String, Any?>()
        json.beginObject()
        while (json.hasNext()) {
            val nom = json.nextName()
            if (nom in champs) throw ContenuIllisibleException("champ $nom en double")
            val lecteur = lecteurs[nom]
            champs[nom] = if (lecteur != null) {
                lecteur()
            } else {
                when (json.peek()) {
                    JsonToken.STRING -> json.nextString()
                    JsonToken.NUMBER -> Nombre(json.nextString())
                    JsonToken.BOOLEAN -> json.nextBoolean()
                    JsonToken.NULL -> json.nextNull().let { null }
                    else -> json.skipValue().let { null }
                }
            }
        }
        json.endObject()
        return champs
    }

    /** A number kept as written, read as the type its field needs — never through a `double`. */
    private class Nombre(val texte: String)

    private fun Map<String, Any?>.texte(nom: String): String =
        this[nom] as? String ?: throw ContenuIllisibleException("champ $nom manquant")

    private fun Map<String, Any?>.texteOuNull(nom: String): String? = when (val v = this[nom]) {
        null -> null
        is String -> v
        else -> throw ContenuIllisibleException("champ $nom")
    }

    private fun Map<String, Any?>.long(nom: String): Long =
        longOuNull(nom) ?: throw ContenuIllisibleException("champ $nom manquant")

    private fun Map<String, Any?>.longOuNull(nom: String): Long? = when (val v = this[nom]) {
        null -> null
        is Nombre -> v.texte.toLongOrNull() ?: throw ContenuIllisibleException("champ $nom")
        else -> throw ContenuIllisibleException("champ $nom")
    }

    private fun Map<String, Any?>.entier(nom: String): Int =
        entierOuNull(nom) ?: throw ContenuIllisibleException("champ $nom manquant")

    private fun Map<String, Any?>.entierOuNull(nom: String): Int? = longOuNull(nom)?.let {
        if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw ContenuIllisibleException("champ $nom")
    }

    private fun Map<String, Any?>.booleen(nom: String): Boolean =
        this[nom] as? Boolean ?: throw ContenuIllisibleException("champ $nom manquant")

    private fun Map<String, Any?>.octets(nom: String): ByteArray = deBase64(texte(nom))

    private fun deBase64(texte: String): ByteArray = try {
        Base64.decode(texte, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        throw ContenuIllisibleException("base64 invalide").apply { initCause(e) }
    }
}

private fun base64(octets: ByteArray): String = Base64.encodeToString(octets, Base64.NO_WRAP)

private const val MODE_PHRASE = "passphrase"
private const val MODE_CODE = "pin"
