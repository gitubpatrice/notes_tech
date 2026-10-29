package com.filestech.notes_tech.data.sauvegarde

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.filestech.notes_tech.core.crypto.wipe
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.NotesDatabase
import com.filestech.notes_tech.data.local.entity.FolderEntity
import com.filestech.notes_tech.data.local.entity.NoteEntity
import com.filestech.notes_tech.data.local.mapper.TagCodec
import com.filestech.notes_tech.domain.model.VaultMode
import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde
import com.filestech.notes_tech.security.sauvegarde.SecretDeSauvegarde
import com.filestech.notes_tech.security.vault.VaultSessions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The encrypted backup's writing side (3.2.0, `docs/13-VERSION-3.2.md` D-029, D-030): every folder and
 * every note, the trash included, into a `.ntbackup` document the user created where they wanted.
 *
 * - **One read transaction** for the whole content, notes read a page at a time and written as they
 *   come: a coherent picture, never all of the text in memory at once.
 * - **Vaults travel unopened**: a passphrase vault's columns and its sealed blobs, as they are. A PIN
 *   vault only when open — its folder key enters the encrypted content, copied for the backup and
 *   wiped after (R-7); a closed one, and its notes, are left out, and [apercu] names it first.
 * - 🔴 **Success is said only after the file is read back whole**, tag checked and content counted.
 *   On any failure the document is deleted when its provider allows it.
 */
@Singleton
class SauvegardeChiffree @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databases: DatabaseProvider,
    private val sessions: VaultSessions,
    private val clock: Clock,
) {

    /** What a backup made now would hold — said before the user chooses a code. */
    data class Apercu(
        val dossiers: Int,
        val notes: Int,
        /** Left out, with their notes: a PIN vault's key is sealed by this phone's Keystore. */
        val coffresACodeFermes: List<String>,
        /** Saved with their key: protected, in the file, by the backup's secret only. */
        val coffresACodeOuverts: List<String>,
    )

    /** What was written, and read back. */
    data class Creation(val dossiers: Int, val notes: Int, val omises: Int)

    class SauvegardeTropGrosseException : IOException("sauvegarde au-dela de ${FichierDeSauvegarde.TAILLE_MAX} octets")

    class RelectureEchoueeException(cause: Throwable?) : IOException("la sauvegarde ecrite ne se relit pas", cause)

    suspend fun apercu(): Apercu = withContext(Dispatchers.IO) {
        val database = databases.get()
        database.withTransaction {
            val dossiers = database.folderDao().listAll()
            val (ouverts, fermes) = dossiers
                .filter { it.mode() == VaultMode.PIN }
                .partition { sessions.hasLiveSession(it.id) }
            // The same rule as the writing's ([coffreDe]): what cannot travel is left out, here counted.
            val omis = dossiers.filter { it.vaultSalt != null && !peutVoyager(it) }.map { it.id }.toSet()
            var notes = 0
            parPages(database) { page -> notes += page.count { it.folderId !in omis } }
            Apercu(
                dossiers = dossiers.size - omis.size,
                notes = notes,
                coffresACodeFermes = fermes.map { it.name },
                coffresACodeOuverts = ouverts.map { it.name },
            )
        }
    }

    /**
     * Writes the backup into [document] — a NEW document (R-12) — under [secret], then reads it back.
     *
     * @throws SauvegardeTropGrosseException past [FichierDeSauvegarde.TAILLE_MAX]: nothing is kept.
     * @throws RelectureEchoueeException the file written does not read back: nothing is kept.
     */
    suspend fun creer(document: Uri, secret: String): Creation = withContext(Dispatchers.IO) {
        val enTete = FichierDeSauvegarde.nouvelEnTete()
        val octetsDuSecret = SecretDeSauvegarde.octets(secret)
        val cle = try {
            FichierDeSauvegarde.cle(octetsDuSecret, enTete)
        } finally {
            octetsDuSecret.wipe()
        }
        val clesDesCoffres = HashMap<String, ByteArray>()
        try {
            val ecrite = ecrire(document, cle, enTete, clesDesCoffres)
            relire(document, cle, ecrite)
            ecrite
        } catch (e: Throwable) {
            supprimer(document)
            throw e
        } finally {
            cle.wipe()
            clesDesCoffres.values.forEach { it.wipe() }
        }
    }

    private suspend fun ecrire(
        document: Uri,
        cle: ByteArray,
        enTete: FichierDeSauvegarde.EnTete,
        clesDesCoffres: MutableMap<String, ByteArray>,
    ): Creation {
        val database = databases.get()
        val sortie = context.contentResolver.openOutputStream(document, "w")
            ?: throw IOException("document de sauvegarde impossible a ouvrir")
        var dossiers = 0
        var notes = 0
        var omises = 0
        bornee(sortie).use { fichier ->
            FichierDeSauvegarde.ecrire(fichier, cle, enTete).use { chiffre ->
                EcritureDuContenu(chiffre, clock.millis()).use { contenu ->
                    database.withTransaction {
                        val sauvegardes = HashSet<String>()
                        for (dossier in database.folderDao().listAll()) {
                            val coffre =
                                coffreDe(dossier, clesDesCoffres) ?: if (dossier.vaultSalt != null) continue else null
                            contenu.dossier(dossier.versLaSauvegarde(coffre))
                            sauvegardes += dossier.id
                            dossiers++
                        }
                        parPages(database) { page ->
                            for (note in page) {
                                if (note.folderId in sauvegardes) {
                                    contenu.note(note.versLaSauvegarde())
                                    notes++
                                } else {
                                    omises++
                                }
                            }
                        }
                    }
                }
            }
        }
        return Creation(dossiers, notes, omises)
    }

    /**
     * A vault as it travels — or `null` for an ordinary folder, and for a vault that cannot travel: a
     * PIN vault closed now, a vault whose columns make no known mode. The caller tells them apart.
     */
    private fun coffreDe(dossier: FolderEntity, clesDesCoffres: MutableMap<String, ByteArray>): CoffreSauvegarde? {
        if (dossier.vaultSalt == null) return null
        return when (dossier.mode()) {
            VaultMode.PASSPHRASE -> phraseDe(dossier)
            VaultMode.PIN -> sessions.sessionKey(dossier.id)?.let { cle ->
                clesDesCoffres[dossier.id] = cle
                CoffreSauvegarde.ACode(cle)
            }
            VaultMode.UNKNOWN -> null
        }
    }

    /** The file read back whole with the key it was written with: tag checked, folders and notes counted. */
    private suspend fun relire(document: Uri, cle: ByteArray, ecrite: Creation) {
        val (dossiers, notes) = try {
            val entree = context.contentResolver.openInputStream(document)
                ?: throw IOException("sauvegarde ecrite impossible a rouvrir")
            entree.use {
                val borne = FichierDeSauvegarde.borne(it)
                val (enTete, octetsDeLEnTete) = FichierDeSauvegarde.lireLEnTete(borne)
                var dossiers = 0
                var notes = 0
                FichierDeSauvegarde.ouvrir(borne, cle, enTete, octetsDeLEnTete).use { contenu ->
                    LectureDuContenu.lire(contenu, surLesDossiers = { dossiers = it.size }, surUneNote = { notes++ })
                }
                dossiers to notes
            }
        } catch (e: IOException) {
            throw RelectureEchoueeException(e)
        }
        coroutineContext.ensureActive()
        if (dossiers != ecrite.dossiers || notes != ecrite.notes) throw RelectureEchoueeException(null)
    }

    private fun supprimer(document: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, document)
        } catch (e: Exception) {
            // Not every provider deletes; the screen says the backup failed either way.
            Timber.w(e, "sauvegarde ratee non supprimee")
        }
    }

    /** [sortie], refused past [FichierDeSauvegarde.TAILLE_MAX] bytes written. */
    private fun bornee(sortie: OutputStream): OutputStream = object : FilterOutputStream(sortie) {
        private var ecrits = 0L

        override fun write(b: Int) {
            compter(1)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            compter(len)
            out.write(b, off, len)
        }

        private fun compter(n: Int) {
            ecrits += n
            if (ecrits > FichierDeSauvegarde.TAILLE_MAX) throw SauvegardeTropGrosseException()
        }
    }

    private suspend inline fun parPages(database: NotesDatabase, action: (List<NoteEntity>) -> Unit) {
        var decalage = 0
        while (true) {
            coroutineContext.ensureActive()
            val page = database.sauvegardeDao().pageDeNotes(PAGE, decalage)
            if (page.isEmpty()) return
            action(page)
            decalage += page.size
        }
    }

    private fun FolderEntity.mode(): VaultMode = VaultMode.fromMaterial(vaultKekWrapped, vaultPinBlob, vaultPinIv)

    /** A vault the backup can carry now — what [coffreDe] answers, without copying a key. */
    private fun peutVoyager(dossier: FolderEntity): Boolean = when (dossier.mode()) {
        VaultMode.PASSPHRASE -> phraseDe(dossier) != null
        VaultMode.PIN -> sessions.hasLiveSession(dossier.id)
        VaultMode.UNKNOWN -> false
    }

    /**
     * A passphrase vault's columns, all of them — or `null`: a column missing is a vault no phrase
     * opens anywhere, left out rather than the backup failed.
     */
    private fun phraseDe(dossier: FolderEntity): CoffreSauvegarde.APhrase? {
        val sel = dossier.vaultSalt ?: return null
        val cle = dossier.vaultKekWrapped ?: return null
        return dossier.vaultIv?.let { iv ->
            dossier.vaultVerifier?.let { verificateur -> CoffreSauvegarde.APhrase(sel, cle, iv, verificateur) }
        }
    }

    private fun FolderEntity.versLaSauvegarde(coffre: CoffreSauvegarde?) = DossierSauvegarde(
        id = id,
        nom = name,
        parentId = parentId,
        couleur = color,
        icone = icon,
        creeLe = createdAt,
        modifieLe = updatedAt,
        coffre = coffre,
    )

    private fun NoteEntity.versLaSauvegarde() = NoteSauvegardee(
        id = id,
        dossierId = folderId,
        titre = title,
        contenu = content,
        scelle = encryptedContent,
        versionDeChiffrement = encVersion,
        etiquettes = TagCodec.decode(tags),
        epinglee = pinned,
        favorite = favorite,
        archivee = archived,
        misALaCorbeilleLe = trashedAt,
        couleur = colorId,
        creeeLe = createdAt,
        modifieeLe = updatedAt,
    )

    private companion object {
        const val PAGE = 200
    }
}
