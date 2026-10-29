package com.filestech.notes_tech.data.sauvegarde

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.filestech.notes_tech.core.crypto.wipe
import com.filestech.notes_tech.data.local.entity.FolderEntity
import com.filestech.notes_tech.data.local.entity.NoteEntity
import com.filestech.notes_tech.data.local.mapper.TagCodec
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.data.sauvegarde.PlanDeRestauration.Decision
import com.filestech.notes_tech.data.sauvegarde.PlanDeRestauration.DossierDuTelephone
import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde
import com.filestech.notes_tech.security.sauvegarde.SecretDeSauvegarde
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.VaultParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The encrypted backup's restoring side (3.2.0, `docs/13-VERSION-3.2.md` D-030, D-031). It adds; it
 * never overwrites.
 *
 * 🔴 **Two passes over the file, each decrypted and checked to its tag** — a stream's content is not
 * authenticated until its end:
 * 1. [essayer], writing nothing: everything checked and counted, for the summary — and the PIN vaults
 *    that will need a new PIN.
 * 2. [restaurer], in ONE transaction, **rolled back** when the tag does not hold at the very end — what
 *    it wrote then is undone. The Keystore keys of the PIN vaults it creates are not in any transaction
 *    (R-11): created as their folder is, deleted if the transaction does not commit.
 *
 * Both passes decide through one [PlanDeRestauration]: the summary counts what the writing does.
 */
@Singleton
class RestaurationDeSauvegarde @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notes: NotesRepository,
    private val vaults: FolderVaultService,
) {

    /** A PIN vault of the backup that the restore creates: it needs a new PIN. */
    data class CoffreACode(val id: String, val nom: String)

    /** What a restore does — or, after [essayer], would do. */
    data class Resultat(
        val dossiersCrees: Int,
        val dossiersReutilises: Int,
        val notesAjoutees: Int,
        /** In clear and different from the phone's note of the same id: added under a new id. */
        val notesACote: Int,
        val notesDejaLa: Int,
        val notesIgnorees: Map<RaisonDIgnorer, Int>,
        /** Among the notes added, those that go back to the trash, with their date. */
        val notesALaCorbeille: Int,
        val coffresACode: List<CoffreACode>,
        /** SHA-256 of the file's bytes as this pass read them: what [restaurer] holds the writing to. */
        val empreinte: String,
    )

    /**
     * The file the writing pass read is not the one the trial read: replaced in between, or served
     * differently by its provider. Rolled back — the user approved a summary of another file.
     */
    class FichierChangeException : IOException("le fichier a change depuis l'essai")

    class CodeManquantException(val dossierId: String) :
        IllegalArgumentException("aucun code pour le coffre $dossierId")

    /**
     * What can be refused before any secret is asked: a file too large by the size its provider gives,
     * and a header that is not a backup's, or one of a newer version. Nothing is decrypted.
     *
     * @throws FichierDeSauvegarde.SauvegardeRefuseeException the file is refused, and why.
     */
    suspend fun examiner(document: Uri) = withContext(Dispatchers.IO) {
        val taille = context.contentResolver.query(document, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
        // A size the provider does not give is not refused here: the reading counts what it reads.
        if (taille != null && taille > FichierDeSauvegarde.TAILLE_MAX) {
            throw FichierDeSauvegarde.SauvegardeRefuseeException(FichierDeSauvegarde.Refus.TROP_GROS)
        }
        val entree = context.contentResolver.openInputStream(document)
            ?: throw IOException("sauvegarde impossible a ouvrir")
        val pasUneSauvegarde =
            FichierDeSauvegarde.SauvegardeRefuseeException(FichierDeSauvegarde.Refus.PAS_UNE_SAUVEGARDE)
        entree.use {
            try {
                FichierDeSauvegarde.lireLEnTete(it)
            } catch (e: FichierDeSauvegarde.SauvegardeRefuseeException) {
                // Shorter than a header: not a backup — no secret could be at fault yet.
                if (e.refus != FichierDeSauvegarde.Refus.TRONQUE) throw e
                throw pasUneSauvegarde
            }
            // And at least the tag after it: a header alone would cost the user a derivation for nothing.
            // Read, not skipped: `skip` may skip less than it could on some providers' streams.
            val etiquette = ByteArray(VaultParams.TAG_BYTES)
            var lus = 0
            while (lus < etiquette.size) {
                val n = it.read(etiquette, lus, etiquette.size - lus)
                if (n < 0) throw pasUneSauvegarde
                lus += n
            }
        }
        Unit
    }

    /**
     * The trial pass. Throws an [IOException] when the file cannot be read with [secret]: a wrong
     * secret and a damaged file look the same, both failing the tag or the content before it.
     */
    suspend fun essayer(document: Uri, secret: String): Resultat =
        parcourir(document, secret, codes = null, essai = null)

    /**
     * The writing pass, [codes] the new PIN of each PIN vault [essai] listed, by folder id — on the
     * very bytes [essai] read ([Resultat.empreinte]).
     *
     * @throws CodeManquantException a PIN vault the restore creates has no PIN in [codes].
     * @throws FichierChangeException the file is not the one [essai] read: nothing is written.
     */
    suspend fun restaurer(document: Uri, secret: String, codes: Map<String, String>, essai: Resultat): Resultat =
        parcourir(document, secret, codes, essai.empreinte)

    private suspend fun parcourir(
        document: Uri,
        secret: String,
        codes: Map<String, String>?,
        essai: String?,
    ): Resultat = withContext(Dispatchers.IO) {
        val entree = context.contentResolver.openInputStream(document)
            ?: throw IOException("sauvegarde impossible a ouvrir")
        val condense = MessageDigest.getInstance("SHA-256")
        DigestInputStream(entree, condense).use { brut ->
            val borne = FichierDeSauvegarde.borne(brut)
            // Before any derivation: a header version 1 does not write is refused here (R-10).
            val (enTete, octetsDeLEnTete) = FichierDeSauvegarde.lireLEnTete(borne)
            val octetsDuSecret = SecretDeSauvegarde.octets(secret)
            val cle = try {
                FichierDeSauvegarde.cle(octetsDuSecret, enTete)
            } finally {
                octetsDuSecret.wipe()
            }
            val passage = Passage(codes)
            try {
                val empreinte = FichierDeSauvegarde.ouvrir(borne, cle, enTete, octetsDeLEnTete).use { contenu ->
                    notes.restaurerDansUneTransaction { ecriture ->
                        LectureDuContenu.lire(
                            contenu,
                            surLesDossiers = { passage.surLesDossiers(ecriture, it) },
                            surUneNote = { passage.surUneNote(ecriture, it) },
                        )
                        // The file is read to its end here: the digest covers every byte. Still in
                        // the transaction, so a file that is not the trial's commits nothing.
                        condense.digest().joinToString("") { "%02x".format(it) }.also {
                            if (essai != null && it != essai) throw FichierChangeException()
                        }
                    }
                }
                passage.resultat(empreinte)
            } catch (e: Throwable) {
                withContext(NonCancellable) { oublierLesClesSansCoffre(passage.clesCreees) }
                throw e
            } finally {
                cle.wipe()
                passage.effacerLesCles()
            }
        }
    }

    /**
     * The Keystore keys created for the PIN vaults of a pass that failed — deleted, each on its own, but
     * only where the vault is NOT in the database. The failure says nothing of the transaction: a
     * cancellation can arrive after its commit, and deleting those keys then would lock the restored
     * vaults for good (GPT-5.6 review, 2026-10-29). A key that does not exist deletes as nothing.
     */
    private suspend fun oublierLesClesSansCoffre(cles: List<String>) {
        for (id in cles) {
            try {
                if (!vaults.isVault(id)) vaults.deletePinKey(id)
            } catch (e: Exception) {
                Timber.w(e, "cle d'un coffre restaure non supprimee")
            }
        }
    }

    /** One pass over a file; [codes] `null` for the trial, which writes nothing. */
    private inner class Passage(private val codes: Map<String, String>?) {
        private lateinit var plan: PlanDeRestauration
        private val clesDesCoffres = ArrayList<ByteArray>()
        val clesCreees = ArrayList<String>()

        private var ajoutees = 0
        private var aCote = 0
        private var dejaLa = 0
        private var aLaCorbeille = 0
        private val ignorees = HashMap<RaisonDIgnorer, Int>()

        suspend fun surLesDossiers(
            ecriture: NotesRepository.EcritureDeRestauration,
            dossiers: List<DossierSauvegarde>,
        ) {
            dossiers.forEach {
                (it.coffre as? CoffreSauvegarde.ACode)?.let { coffre ->
                    clesDesCoffres +=
                        coffre.cleDuDossier
                }
            }
            val telephone = ecriture.dossiersDuTelephone().associate {
                it.id to
                    DossierDuTelephone(
                        it.id,
                        it.parentId,
                        estUnCoffre = it.vaultSalt != null,
                        verificateur = it.vaultVerifier,
                    )
            }
            plan = PlanDeRestauration(dossiers, telephone)
            val codes = codes ?: return
            plan.coffresACodeACreer.forEach { if (it.id !in codes) throw CodeManquantException(it.id) }
            for (aCreer in plan.aCreer) {
                ecriture.dossier(versLEntite(aCreer, codes))
            }
        }

        suspend fun surUneNote(ecriture: NotesRepository.EcritureDeRestauration, note: NoteSauvegardee) {
            val existante = ecriture.noteDuTelephone(note.id)?.pourLaComparaison()
            when (val decision = plan.decider(note, existante)) {
                Decision.Ajoutee -> ajouter(ecriture, note, note.id).also { ajoutees++ }
                // A new id: in clear only (the plan says so), whose sealing an id would not break.
                Decision.ACote -> ajouter(ecriture, note, UUID.randomUUID().toString()).also { aCote++ }
                Decision.DejaLa -> dejaLa++
                is Decision.Ignoree -> ignorees.merge(decision.raison, 1, Int::plus)
            }
        }

        private suspend fun ajouter(
            ecriture: NotesRepository.EcritureDeRestauration,
            note: NoteSauvegardee,
            id: String,
        ) {
            if (note.misALaCorbeilleLe != null) aLaCorbeille++
            if (codes != null) ecriture.note(note.versLEntite(id))
        }

        private suspend fun versLEntite(
            aCreer: PlanDeRestauration.DossierACreer,
            codes: Map<String, String>,
        ): FolderEntity {
            val d = aCreer.dossier
            fun entite(
                sel: ByteArray? = null,
                cleEmballee: ByteArray? = null,
                iv: ByteArray? = null,
                verificateur: ByteArray? = null,
                mode: String? = null,
                blobDuCode: ByteArray? = null,
                ivDuCode: ByteArray? = null,
            ) = FolderEntity(
                d.id, d.nom, aCreer.parentId, d.couleur, d.icone, d.creeLe, d.modifieLe,
                sel, cleEmballee, iv, verificateur, mode, blobDuCode, ivDuCode, vaultAttempts = 0,
            )
            return when (val coffre = d.coffre) {
                null -> entite()
                is CoffreSauvegarde.APhrase -> entite(
                    coffre.sel,
                    coffre.cleEmballee,
                    coffre.iv,
                    coffre.verificateur,
                    MODE_PHRASE,
                )
                is CoffreSauvegarde.ACode -> {
                    // Listed BEFORE its key exists: a sealing that throws after the key's creation
                    // still leaves it to the cleanup. A folder created here is new: its alias is no
                    // live vault's (GPT-5.6 review, 2026-10-29).
                    clesCreees += d.id
                    val materiel = vaults.materielDUnCoffreACode(d.id, coffre.cleDuDossier, codes.getValue(d.id))
                    entite(
                        sel = materiel.sel,
                        iv = materiel.iv,
                        verificateur = materiel.verificateur,
                        mode = MODE_CODE,
                        blobDuCode = materiel.blobDuCode,
                        ivDuCode = materiel.ivDuCode,
                    )
                }
            }
        }

        fun resultat(empreinte: String) = Resultat(
            dossiersCrees = plan.aCreer.size,
            dossiersReutilises = plan.reutilises,
            notesAjoutees = ajoutees,
            notesACote = aCote,
            notesDejaLa = dejaLa,
            notesIgnorees = ignorees.toMap(),
            notesALaCorbeille = aLaCorbeille,
            coffresACode = plan.coffresACodeACreer.map { CoffreACode(it.id, it.nom) },
            empreinte = empreinte,
        )

        /** The PIN vaults' folder keys read from the file: wiped once the pass is over, whatever its end. */
        fun effacerLesCles() = clesDesCoffres.forEach { it.wipe() }
    }

    private fun NoteEntity.pourLaComparaison() = NoteDuTelephone(
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

    private fun NoteSauvegardee.versLEntite(id: String) = NoteEntity(
        id = id,
        title = titre,
        content = contenu,
        encryptedContent = scelle,
        folderId = dossierId,
        tags = TagCodec.encode(etiquettes),
        pinned = epinglee,
        favorite = favorite,
        archived = archivee,
        trashedAt = misALaCorbeilleLe,
        createdAt = creeeLe,
        updatedAt = modifieeLe,
        encVersion = versionDeChiffrement,
        colorId = couleur,
    )

    private companion object {
        /** The `vault_mode` the vault service writes (`FolderDao.provisionPassphraseVault`, `provisionPinVault`). */
        const val MODE_PHRASE = "passphrase"
        const val MODE_CODE = "pin"
    }
}
