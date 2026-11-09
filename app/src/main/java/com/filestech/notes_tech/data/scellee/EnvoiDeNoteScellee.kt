package com.filestech.notes_tech.data.scellee

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.security.sauvegarde.SecretDeSauvegarde
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/**
 * Seals a note into a file ready to be shared (D-044, 3.4.0), and draws its code.
 *
 * - The file goes where the exports go ([NoteExporter.repertoireDExport]): purged at the next start and
 *   by the panic, and served by the same `FileProvider`. Encrypted, it would not need it; one directory
 *   is one thing to wipe.
 * - 🔴 **Its name says nothing of the note**: `note-AAAAMMJJ.ntnote`. A title in a file name travels in
 *   clear through the messaging app, its previews and its notifications.
 * - The code exists only in what this returns: kept nowhere, shown once by the sheet.
 */
class EnvoiDeNoteScellee @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {

    /** A sealed note ready to share: the file's [uri] and [nomDuFichier], and the [code] that opens it. */
    class Envoi(val uri: Uri, val nomDuFichier: String, val code: String)

    /** Slow — Argon2id —, and done off the main thread. */
    suspend fun preparer(note: NoteScellee): Envoi = withContext(Dispatchers.Default) {
        val code = SecretDeSauvegarde.nouveauCode()
        val octets = FichierDeNoteScellee.sceller(note, code)
        val dossier = File(NoteExporter.repertoireDExport(context), "scellee-${clock.millis()}-${UUID.randomUUID()}")
        check(dossier.mkdirs()) { "repertoire de la note scellee non cree" }
        val date = DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(clock))
        val nom = "note-$date.${FichierDeNoteScellee.EXTENSION}"
        val fichier = File(dossier, nom).apply { writeBytes(octets) }
        Envoi(FileProvider.getUriForFile(context, "${context.packageName}.exports", fichier), nom, code)
    }
}
