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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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
 * - 🔴 **Written before the system picker opens** ([preparer]), into the app's cache, then copied into
 *   the document ([deposer]). The picker sends the app to the background, and every vault locks
 *   there (`NotesTechApplication`): written after it, the open PIN vault the preview had named was a
 *   closed one, left out with its notes — a backup of nothing (seen on a S9, 2026-10-29).
 * - 🔴 **Success is said only after the file is read back whole**, tag checked and content counted,
 *   then the document read back against it. On any failure the document is deleted when its provider
 *   allows it.
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
        /** Their ids: what [preparer] holds the writing to ([CoffreRefermeException]). */
        val idsDesCoffresACodeOuverts: Set<String>,
    )

    /** What was written, and read back. */
    data class Creation(val dossiers: Int, val notes: Int, val omises: Int)

    /**
     * A backup written and read back in [repertoireDAttente], waiting for the document the user picks.
     * Encrypted: while the picker is open, neither the secret nor a vault key is held anywhere.
     */
    class Preparee internal constructor(internal val fichier: File, val creation: Creation)

    class SauvegardeTropGrosseException : IOException("sauvegarde au-dela de ${FichierDeSauvegarde.TAILLE_MAX} octets")

    class RelectureEchoueeException(cause: Throwable?) : IOException("la sauvegarde ecrite ne se relit pas", cause)

    /**
     * A PIN vault the preview said open has locked since — the user left the app to note the code,
     * and every vault locks in the background. Written anyway, the backup would not be the one
     * announced: nothing is written, and the screen names the vault.
     */
    class CoffreRefermeException(val nom: String) : IllegalStateException("coffre a code referme depuis l'apercu")

    suspend fun apercu(): Apercu = withContext(Dispatchers.IO) {
        val database = databases.get()
        database.withTransaction {
            val dossiers = database.folderDao().listAll()
            val (ouverts, fermes) = dossiers
                .filter { it.mode() == VaultMode.PIN }
                .partition { sessions.hasLiveSession(it.id) }
            // The same rule as the writing's ([coffreDe]): what cannot travel is left out, here counted.
            val omis = dossiers.filter { it.vaultSalt != null && !peutVoyager(it) }.map { it.id }.toSet()
            Apercu(
                dossiers = dossiers.size - omis.size,
                // Counted from each note's folder alone: no title, no content read to count them.
                notes = database.sauvegardeDao().poidsDesNotes().count { it.dossierId !in omis },
                coffresACodeFermes = fermes.map { it.name },
                coffresACodeOuverts = ouverts.map { it.name },
                idsDesCoffresACodeOuverts = ouverts.map { it.id }.toSet(),
            )
        }
    }

    /**
     * Writes the backup under [secret] into the app's cache, while the vaults are as the preview saw
     * them — [coffresOuverts], the PIN vaults it said open — then reads it back whole. A [Preparee]
     * lost to a cancellation on the way back is purged at the next startup.
     *
     * @throws CoffreRefermeException one of [coffresOuverts] has locked since: nothing is kept.
     * @throws SauvegardeTropGrosseException past [FichierDeSauvegarde.TAILLE_MAX]: nothing is kept.
     * @throws RelectureEchoueeException the file written does not read back: nothing is kept.
     */
    suspend fun preparer(secret: String, coffresOuverts: Set<String>): Preparee = withContext(Dispatchers.IO) {
        val repertoire = repertoireDAttente(context)
        val pret = repertoire.isDirectory || repertoire.mkdirs()
        if (!pret) {
            throw IOException("attente de sauvegarde impossible a creer")
        }
        val fichier = File.createTempFile("sauvegarde", ".ntbackup", repertoire)
        // From here every exit deletes the file and wipes what was derived — the derivation itself
        // (64 MiB) included, which is the step most likely to fail.
        var cle: ByteArray? = null
        val clesDesCoffres = HashMap<String, ByteArray>()
        try {
            val enTete = FichierDeSauvegarde.nouvelEnTete()
            val octetsDuSecret = SecretDeSauvegarde.octets(secret)
            val derivee = try {
                FichierDeSauvegarde.cle(octetsDuSecret, enTete)
            } finally {
                octetsDuSecret.wipe()
            }
            cle = derivee
            val ecrite = ecrire(FileOutputStream(fichier), derivee, enTete, clesDesCoffres, coffresOuverts)
            relire(derivee, ecrite) { FileInputStream(fichier) }
            Preparee(fichier, ecrite)
        } catch (e: Throwable) {
            fichier.delete()
            throw e
        } finally {
            cle?.wipe()
            clesDesCoffres.values.forEach { it.wipe() }
        }
    }

    /**
     * Copies [preparee] into [document] — a NEW document (R-12) — then reads the document back against
     * it: same SHA-256, so the document holds what [preparer] read back. The waiting file is gone
     * after, whatever happens.
     *
     * @throws RelectureEchoueeException the document does not hold those bytes: it is deleted.
     */
    suspend fun deposer(preparee: Preparee, document: Uri): Creation = try {
        withContext(Dispatchers.IO) {
            try {
                val sortie = context.contentResolver.openOutputStream(document, "w")
                    ?: throw IOException("document de sauvegarde impossible a ouvrir")
                val ecrite = sortie.use { FileInputStream(preparee.fichier).use { entree -> empreinte(entree, it) } }
                val relue = try {
                    val entree = context.contentResolver.openInputStream(document)
                        ?: throw IOException("sauvegarde ecrite impossible a rouvrir")
                    entree.use { empreinte(it, sortie = null) }
                } catch (e: IOException) {
                    throw RelectureEchoueeException(e)
                }
                if (!MessageDigest.isEqual(ecrite, relue)) throw RelectureEchoueeException(null)
                preparee.creation
            } catch (e: Throwable) {
                supprimer(document)
                throw e
            }
        }
    } finally {
        // Outside the dispatcher's block: a call cancelled before that block ever ran still lets the
        // waiting file go (GPT-5.6 review, 2026-10-29).
        abandonner(preparee)
    }

    /** The waiting file, deleted: the user went back from the picker, or the screen is gone. */
    fun abandonner(preparee: Preparee) {
        if (!preparee.fichier.delete() && preparee.fichier.exists()) Timber.w("sauvegarde en attente non supprimee")
    }

    /**
     * A document the picker created for a backup that is no longer there to fill it — the process
     * died while the picker was open: deleted rather than left empty under a backup's name.
     */
    suspend fun oublier(document: Uri) = withContext(Dispatchers.IO) { supprimer(document) }

    private suspend fun ecrire(
        sortie: OutputStream,
        cle: ByteArray,
        enTete: FichierDeSauvegarde.EnTete,
        clesDesCoffres: MutableMap<String, ByteArray>,
        coffresOuverts: Set<String>,
    ): Creation {
        var dossiers = 0
        var notes = 0
        var omises = 0
        bornee(sortie).use { fichier ->
            val database = databases.get()
            FichierDeSauvegarde.ecrire(fichier, cle, enTete).use { chiffre ->
                EcritureDuContenu(chiffre, clock.millis()).use { contenu ->
                    database.withTransaction {
                        val sauvegardes = HashSet<String>()
                        for (dossier in database.folderDao().listAll()) {
                            val coffre = coffreDe(dossier, clesDesCoffres)
                            if (coffre == null && dossier.vaultSalt != null) {
                                if (dossier.id in coffresOuverts) throw CoffreRefermeException(dossier.name)
                                continue
                            }
                            contenu.dossier(dossier.versLaSauvegarde(coffre))
                            sauvegardes += dossier.id
                            dossiers++
                        }
                        parLots(database) { page ->
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
    private suspend fun relire(cle: ByteArray, ecrite: Creation, ouvrir: () -> InputStream) {
        val (dossiers, notes) = try {
            ouvrir().use {
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

    /**
     * The SHA-256 of [entree], read to its end — copied into [sortie] on the way, when there is one.
     * Never past [FichierDeSauvegarde.TAILLE_MAX]: a document longer than any backup is not this one.
     */
    private suspend fun empreinte(entree: InputStream, sortie: OutputStream?): ByteArray {
        val condense = MessageDigest.getInstance("SHA-256")
        val tampon = ByteArray(TAMPON)
        var lus = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = entree.read(tampon)
            if (n < 0) return condense.digest()
            lus += n
            if (lus > FichierDeSauvegarde.TAILLE_MAX) throw IOException("au-dela de la taille d'une sauvegarde")
            condense.update(tampon, 0, n)
            sortie?.write(tampon, 0, n)
        }
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

    /**
     * Every note, in lots of at most [LOT_MAX] notes and [POIDS_MAX] characters — a note heavier than
     * that alone in its lot. Counted by notes alone, a lot of imported notes (up to 5 MiB each) could
     * hold more text than the heap (GPT-5.6 review, 2026-10-29). In the caller's read transaction.
     */
    private suspend inline fun parLots(database: NotesDatabase, action: (List<NoteEntity>) -> Unit) {
        val poids = database.sauvegardeDao().poidsDesNotes()
        var debut = 0
        while (debut < poids.size) {
            coroutineContext.ensureActive()
            var fin = debut + 1
            var cumul = poids[debut].poids
            while (fin < poids.size && fin - debut < LOT_MAX && cumul + poids[fin].poids <= POIDS_MAX) {
                cumul += poids[fin].poids
                fin++
            }
            action(database.sauvegardeDao().notes(poids.subList(debut, fin).map { it.id }))
            debut = fin
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

    companion object {
        private const val LOT_MAX = 200
        private const val POIDS_MAX = 1L shl 20
        private const val TAMPON = 64 * 1024

        /**
         * Where [preparer] writes, in the cache. Encrypted, but every note is in it: purged at startup
         * and by the panic, by the same definition as the exports (`ClairDuCache`).
         */
        fun repertoireDAttente(context: Context): File = File(context.cacheDir, "sauvegarde")
    }
}
