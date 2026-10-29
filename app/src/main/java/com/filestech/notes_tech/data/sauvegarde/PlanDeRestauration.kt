package com.filestech.notes_tech.data.sauvegarde

import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.security.vault.VaultCrypto

/** Why a note of a backup is not restored — said, counted, in the summary. */
enum class RaisonDIgnorer {
    INVALIDE,
    DOSSIER_ABSENT,
    SCELLEE_HORS_COFFRE,
    EN_CLAIR_DANS_UN_COFFRE,
    AUTRE_COFFRE,
    CONFLIT_SCELLE,
}

/**
 * What a restore does with each folder and each note of a backup (3.2.0, `docs/13-VERSION-3.2.md`
 * D-031) — decided in ONE place, for the trial pass that counts and the pass that writes: two passes
 * deciding apart would be twins that diverge.
 *
 * Pure: the phone's side comes in as [DossierDuTelephone] and a lookup of its notes by id.
 *
 * - **Folders**: an id the phone has is reused **as the phone has it**; any other is created, parents
 *   first. A parent that is neither in the backup nor on the phone, a folder its own parent, a cycle, a
 *   tree deeper than [PROFONDEUR_MAX]: the folder is created without a parent (R-6).
 * - **Each note is checked against the folder it really goes to** (R-4): the phone's when the id
 *   exists there, else the backup's. An ordinary folder takes notes in clear; a vault takes sealed
 *   notes, and only if it is the backup's vault — **the same key**, told by its verifier (R-5). A
 *   forged backup can thus neither slip a note in clear into a vault, nor a blob it cannot open.
 * - **A note whose id the phone has** (R-3): the same → nothing to do; different and in clear → added
 *   beside it under a new id, nothing overwritten; different and sealed → ignored, a new id breaking
 *   its sealing (its AAD is its id).
 */
internal class PlanDeRestauration(
    dossiersDeLaSauvegarde: List<DossierSauvegarde>,
    private val dossiersDuTelephone: Map<String, DossierDuTelephone>,
) {

    /**
     * A folder of the phone, as far as a restore needs it: its parent, whether it is a vault, and its
     * vault's verifier. 🔴 A vault without a verifier is still a vault — one that takes no note at all:
     * read as an ordinary folder, it would take notes in clear, written past the repository's sealing.
     */
    class DossierDuTelephone(
        val id: String,
        val parentId: String?,
        val estUnCoffre: Boolean,
        val verificateur: ByteArray?,
    )

    /** A folder to create, its parent settled. */
    class DossierACreer(val dossier: DossierSauvegarde, val parentId: String?)

    sealed interface Decision {
        data object Ajoutee : Decision

        /** In clear and different from the phone's note of the same id: added under a new id. */
        data object ACote : Decision

        data object DejaLa : Decision

        data class Ignoree(val raison: RaisonDIgnorer) : Decision
    }

    /** The first folder of each id: a forged backup may repeat one, and one description must win everywhere. */
    private val parId = dossiersDeLaSauvegarde.distinctBy { it.id }.associateBy { it.id }

    /** The folders to create, parents before children. */
    val aCreer: List<DossierACreer>

    /** The backup's PIN vaults that will be created: each needs a new PIN, chosen at the restore. */
    val coffresACodeACreer: List<DossierSauvegarde>

    val reutilises: Int = parId.keys.count { it in dossiersDuTelephone }

    init {
        val nouveaux = parId.values.filter { it.id !in dossiersDuTelephone }
        val parents = HashMap<String, String?>()
        for (dossier in nouveaux) parents[dossier.id] = parentAdmis(dossier, parents)
        aCreer = trierParentsDAbord(nouveaux.map { DossierACreer(it, parents[it.id]) })
        coffresACodeACreer = nouveaux.filter { it.coffre is CoffreSauvegarde.ACode }
    }

    fun decider(note: NoteSauvegardee, existante: NoteDuTelephone?): Decision {
        // What the app never writes: a title past the repository's limit would fail every later save.
        if (note.titre.length > NotesRepository.TITLE_MAX_LENGTH) return Decision.Ignoree(RaisonDIgnorer.INVALIDE)
        val destination = identiteDe(note.dossierId) ?: return Decision.Ignoree(RaisonDIgnorer.DOSSIER_ABSENT)
        val scellee = note.scelle != null
        val verifiee = when (destination) {
            Identite.Ordinaire -> if (scellee) RaisonDIgnorer.SCELLEE_HORS_COFFRE else null
            is Identite.Coffre -> when {
                !scellee -> RaisonDIgnorer.EN_CLAIR_DANS_UN_COFFRE
                !memeCleQueLaSauvegarde(note.dossierId, destination) -> RaisonDIgnorer.AUTRE_COFFRE
                else -> null
            }
        }
        if (verifiee != null) return Decision.Ignoree(verifiee)
        return when {
            existante == null -> Decision.Ajoutee
            existante.estLaMemeQue(note) -> Decision.DejaLa
            scellee -> Decision.Ignoree(RaisonDIgnorer.CONFLIT_SCELLE)
            else -> Decision.ACote
        }
    }

    private sealed interface Identite {
        data object Ordinaire : Identite

        /** [verificateur] `null`: a vault that cannot be checked, which nothing matches. */
        class Coffre(val verificateur: ByteArray?) : Identite
    }

    /** Where a note of [dossierId] goes: the phone's folder of that id, else the backup's — or nowhere. */
    private fun identiteDe(dossierId: String): Identite? {
        dossiersDuTelephone[dossierId]?.let { telephone ->
            return if (telephone.estUnCoffre) Identite.Coffre(telephone.verificateur) else Identite.Ordinaire
        }
        val sauvegarde = parId[dossierId] ?: return null
        return if (sauvegarde.coffre != null) Identite.Coffre(verificateurDe(sauvegarde)) else Identite.Ordinaire
    }

    /**
     * The destination is the backup's own vault: created from it, or the phone's vault of the same id
     * with the same key. A sealed note whose folder the backup does not describe cannot be checked.
     */
    private fun memeCleQueLaSauvegarde(dossierId: String, destination: Identite.Coffre): Boolean {
        val sauvegarde = parId[dossierId] ?: return false
        val attendu = verificateurDe(sauvegarde) ?: return false
        val trouve = destination.verificateur ?: return false
        return attendu.contentEquals(trouve)
    }

    private fun verificateurDe(dossier: DossierSauvegarde): ByteArray? = when (val coffre = dossier.coffre) {
        null -> null
        is CoffreSauvegarde.APhrase -> coffre.verificateur
        is CoffreSauvegarde.ACode -> VaultCrypto.verifierFor(coffre.cleDuDossier)
    }

    /** [dossier]'s parent if it can be one: known, not itself, and not closing a cycle or going too deep. */
    private fun parentAdmis(dossier: DossierSauvegarde, parentsDejaAdmis: Map<String, String?>): String? {
        val parent = dossier.parentId
        val admis = parent != null &&
            parent != dossier.id &&
            (parent in dossiersDuTelephone || parent in parId) &&
            sansCycleNiExces(dossier.id, parent, parentsDejaAdmis)
        return parent.takeIf { admis }
    }

    /** Walking up from [parent], [id] is never met and the tree stays within [PROFONDEUR_MAX]. */
    private fun sansCycleNiExces(id: String, parent: String, parentsDejaAdmis: Map<String, String?>): Boolean {
        var courant: String? = parent
        var profondeur = 1
        while (courant != null) {
            if (courant == id || profondeur > PROFONDEUR_MAX) return false
            courant = when (courant) {
                in dossiersDuTelephone -> dossiersDuTelephone.getValue(courant).parentId
                // A backup folder not settled yet: its own parent, as written — the walk stays bounded.
                in parentsDejaAdmis -> parentsDejaAdmis[courant]
                else -> parId[courant]?.parentId
            }
            profondeur++
        }
        return true
    }

    private fun trierParentsDAbord(dossiers: List<DossierACreer>): List<DossierACreer> {
        val restants = dossiers.associateByTo(LinkedHashMap()) { it.dossier.id }
        val tries = ArrayList<DossierACreer>(dossiers.size)
        while (restants.isNotEmpty()) {
            // A parent already on the phone, already sorted, or none: ready. One at least each round —
            // the parents admitted form no cycle.
            val prets = restants.values.filter { it.parentId == null || it.parentId !in restants }
            check(prets.isNotEmpty()) { "cycle parmi les dossiers a creer" }
            prets.forEach {
                tries += it
                restants.remove(it.dossier.id)
            }
        }
        return tries
    }

    private companion object {
        const val PROFONDEUR_MAX = 32
    }
}

/** A note of the phone, as far as a restore compares it. */
internal class NoteDuTelephone(
    val dossierId: String,
    val titre: String,
    val contenu: String,
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
) {
    fun estLaMemeQue(n: NoteSauvegardee): Boolean = dossierId == n.dossierId &&
        titre == n.titre &&
        contenu == n.contenu &&
        scelle.contentEquals(n.scelle) &&
        versionDeChiffrement == n.versionDeChiffrement &&
        etiquettes == n.etiquettes &&
        epinglee == n.epinglee &&
        favorite == n.favorite &&
        archivee == n.archivee &&
        misALaCorbeilleLe == n.misALaCorbeilleLe &&
        couleur == n.couleur &&
        creeeLe == n.creeeLe &&
        modifieeLe == n.modifieeLe
}
