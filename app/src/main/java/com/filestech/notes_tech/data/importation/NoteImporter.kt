package com.filestech.notes_tech.data.importation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.importation.LectureDUnImport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Settings' "Import" (3.2.0, `docs/13-VERSION-3.2.md` D-032): the documents the user chose in the
 * system picker, read one by one under [LectureDUnImport]'s bounds, then written in one transaction
 * ([NotesRepository.importerDansDesDossiers]) — all the notes, or none.
 *
 * Nothing is copied to the app's storage: each document is read from its provider, and let go.
 */
@Singleton
class NoteImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notes: NotesRepository,
) {

    /** What an import did, for the summary. */
    data class Resultat(
        val importees: Int,
        val ignores: Int,
        /** A bound stopped the reading before the end: what was read is imported, the rest is not. */
        val arrete: Boolean,
        val dossiersCrees: Int,
        val versLaBoiteDeReception: Int,
    )

    /** @throws Exception when the write fails: nothing was imported then. */
    suspend fun importer(documents: List<Uri>): Resultat = withContext(Dispatchers.IO) {
        val lecture = LectureDUnImport()
        documents.forEach { document ->
            ensureActive()
            val flux = try {
                context.contentResolver.openInputStream(document)
            } catch (e: FileNotFoundException) {
                Timber.w(e, "document a importer introuvable")
                null
            } catch (e: SecurityException) {
                Timber.w(e, "document a importer refuse")
                null
            }
            val nom = nomAffiche(document)
            if (flux == null) lecture.ajouter(nom, null) else flux.use { lecture.ajouter(nom, it) }
        }
        val lu = lecture.resultat()
        val ecrit = if (lu.notes.isEmpty()) null else notes.importerDansDesDossiers(lu.notes)
        Resultat(
            importees = ecrit?.notes?.size ?: 0,
            ignores = lu.ignores,
            arrete = lu.arrete,
            dossiersCrees = ecrit?.dossiersCrees ?: 0,
            versLaBoiteDeReception = ecrit?.versLaBoiteDeReception ?: 0,
        )
    }

    private fun nomAffiche(document: Uri): String? = try {
        context.contentResolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: RuntimeException) {
        Timber.w(e, "nom du document a importer illisible")
        null
    } ?: document.lastPathSegment
}
