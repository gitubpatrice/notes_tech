package com.filestech.notes_tech.ui.settings

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.sauvegarde.RestaurationDeSauvegarde
import com.filestech.notes_tech.data.sauvegarde.SauvegardeChiffree
import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde
import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde.Refus
import com.filestech.notes_tech.security.sauvegarde.SecretDeSauvegarde
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * The Settings' encrypted backup (3.2.0, `docs/13-VERSION-3.2.md` D-029 to D-031): making one, and
 * restoring one, step by step — each step a state the screen shows.
 *
 * - Making one: the backup is written BEFORE the system picker opens ([SauvegardeChiffree.preparer]) —
 *   the picker sends the app to the background, where every vault locks. Only the encrypted file waits
 *   for the picker's answer; the secret is not kept.
 * - Restoring one: the secret is held here between the trial and the restore, never in the state the
 *   screen saves — a rotation keeps it (this ViewModel survives it). A process death forgets the whole
 *   flow, the chosen file included: the user starts it again, which is the safe side.
 */
@HiltViewModel
class SauvegardeViewModel @Inject constructor(
    private val sauvegarde: SauvegardeChiffree,
    private val restauration: RestaurationDeSauvegarde,
) : ViewModel() {

    sealed interface Etape {
        data object Repos : Etape

        /** Work under way, in the flow titled [titre]: making a backup, or restoring one. */
        data class Occupe(@StringRes val titre: Int) : Etape

        /** Before a backup is made: what it will hold, and the code drawn for it. */
        data class AvantLaCreation(val apercu: SauvegardeChiffree.Apercu, val code: String) : Etape

        /**
         * The backup is written and read back, waiting: the screen opens the system picker, then says
         * so ([selecteurOuvert]) — the step it reacted to is gone before a rotation could show it again.
         */
        data object PreteADeposer : Etape

        /** The system picker is open. */
        data object EnAttenteDuDocument : Etape

        data class Creee(val creation: SauvegardeChiffree.Creation) : Etape

        /** A file is chosen: its code or passphrase is asked — again, after a wrong one ([erreur]). */
        data class SecretADemander(@StringRes val erreur: Int? = null) : Etape

        /** The trial pass's answer: what the restore will do, and the PINs it needs. */
        data class Essayee(val resultat: RestaurationDeSauvegarde.Resultat) : Etape

        data class Restauree(val resultat: RestaurationDeSauvegarde.Resultat) : Etape

        /** [argument]: what [message] names, when it names something — a vault locked since the preview. */
        data class Echec(@StringRes val titre: Int, @StringRes val message: Int, val argument: String? = null) : Etape
    }

    private val _etape = MutableStateFlow<Etape>(Etape.Repos)
    val etape: StateFlow<Etape> = _etape.asStateFlow()

    private var secret: String? = null
    private var document: Uri? = null
    private var preparee: SauvegardeChiffree.Preparee? = null

    fun preparerLaCreation() = executer(CREATION, enLecture = false) {
        Etape.AvantLaCreation(sauvegarde.apercu(), SecretDeSauvegarde.nouveauCode())
    }

    /** The secret is chosen: the backup is written now, held to the vaults the preview said open. */
    fun secretChoisi(choisi: String) {
        val ouverts = (_etape.value as? Etape.AvantLaCreation)?.apercu?.idsDesCoffresACodeOuverts ?: return fermer()
        executer(CREATION, enLecture = false) {
            preparee = sauvegarde.preparer(choisi, ouverts)
            Etape.PreteADeposer
        }
    }

    fun selecteurOuvert() {
        if (_etape.value == Etape.PreteADeposer) _etape.value = Etape.EnAttenteDuDocument
    }

    /** No app on the device can pick a file: the waiting backup goes, and the flow says why. */
    fun sansSelecteur() {
        val titre = if (_etape.value == Etape.PreteADeposer) CREATION else RESTAURATION
        fermer()
        _etape.value = Etape.Echec(titre, R.string.common_no_picker)
    }

    /** The system picker's answer — `null`: the user went back, and the waiting backup goes. */
    fun documentCree(cree: Uri?) {
        when {
            cree == null -> fermer()
            // The process died while the picker was open (the startup purge took the waiting file): the
            // document it created stays empty — deleted, and the backup said not made.
            preparee == null -> executer(CREATION, enLecture = false) {
                sauvegarde.oublier(cree)
                Etape.Echec(CREATION, R.string.backup_error_generic)
            }
            // Handed over inside the step: cancelled before it ran, the waiting file is still this
            // ViewModel's, and [onCleared] lets it go (GPT-5.6 review, 2026-10-29).
            else -> executer(CREATION, enLecture = false) {
                val enAttente = checkNotNull(preparee)
                preparee = null
                Etape.Creee(sauvegarde.deposer(enAttente, cree))
            }
        }
    }

    /** The file is examined first: one that is not a backup, or too new, is refused before any secret. */
    fun documentOuvert(ouvert: Uri?) {
        if (ouvert == null) return fermer()
        document = ouvert
        executer(RESTAURATION, enLecture = false) {
            restauration.examiner(ouvert)
            Etape.SecretADemander()
        }
    }

    /** A wrong secret — or a damaged file, they look the same — asks again, the file kept. */
    fun essayer(saisi: String) {
        val ouvert = document ?: return fermer()
        secret = saisi
        executer(RESTAURATION, siLeSecretEstFaux = Etape.SecretADemander(R.string.backup_error_wrong_secret)) {
            Etape.Essayee(restauration.essayer(ouvert, saisi))
        }
    }

    /** [codes]: the new PIN of each PIN vault the trial listed, by folder id. */
    fun restaurer(codes: Map<String, String>) {
        val ouvert = document ?: return fermer()
        val saisi = secret ?: return fermer()
        // The writing pass is held to the bytes this trial read.
        val essai = (_etape.value as? Etape.Essayee)?.resultat ?: return fermer()
        executer(RESTAURATION, oublierApres = true) {
            Etape.Restauree(restauration.restaurer(ouvert, saisi, codes, essai))
        }
    }

    fun fermer() {
        preparee?.let(sauvegarde::abandonner)
        preparee = null
        secret = null
        document = null
        _etape.value = Etape.Repos
    }

    /**
     * [titre]: the flow's, on the waiting dialog and on a failure. [enLecture]: a backup file is being
     * read — a failure there means a wrong secret or a damaged file, which [siLeSecretEstFaux] answers
     * when there is one.
     */
    private fun executer(
        @StringRes titre: Int,
        oublierApres: Boolean = false,
        enLecture: Boolean = true,
        siLeSecretEstFaux: Etape? = null,
        etape: suspend () -> Etape,
    ) {
        if (_etape.value is Etape.Occupe) return
        _etape.value = Etape.Occupe(titre)
        viewModelScope.launch {
            _etape.value = try {
                etape()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "sauvegarde chiffree")
                val message = messageDe(e, enLecture)
                siLeSecretEstFaux?.takeIf { message == R.string.backup_error_wrong_secret }
                    ?: Etape.Echec(titre, message, (e as? SauvegardeChiffree.CoffreRefermeException)?.nom)
            } finally {
                if (oublierApres) {
                    secret = null
                    document = null
                }
            }
        }
    }

    override fun onCleared() {
        preparee?.let(sauvegarde::abandonner)
        preparee = null
        secret = null
    }

    private companion object {
        /** The titles of the two flows: the rows the user tapped. */
        val CREATION = R.string.backup_create
        val RESTAURATION = R.string.backup_restore

        @StringRes
        fun messageDe(e: Exception, enLecture: Boolean): Int = when (e) {
            is FichierDeSauvegarde.SauvegardeRefuseeException -> when (e.refus) {
                Refus.PAS_UNE_SAUVEGARDE -> R.string.backup_error_not_a_backup
                Refus.VERSION_INCONNUE, Refus.PARAMETRES_INCONNUS -> R.string.backup_error_newer
                Refus.TROP_GROS -> R.string.backup_error_too_large
                Refus.TRONQUE -> R.string.backup_error_wrong_secret
            }
            is SauvegardeChiffree.CoffreRefermeException -> R.string.backup_error_vault_closed
            is RestaurationDeSauvegarde.FichierChangeException -> R.string.backup_error_changed
            is SauvegardeChiffree.SauvegardeTropGrosseException -> R.string.backup_error_too_large
            is SauvegardeChiffree.RelectureEchoueeException -> R.string.backup_error_readback
            // Reading: a wrong secret and a damaged file look the same — the tag, or the content before it.
            is IOException -> if (enLecture) R.string.backup_error_wrong_secret else R.string.backup_error_generic
            else -> R.string.backup_error_generic
        }
    }
}
