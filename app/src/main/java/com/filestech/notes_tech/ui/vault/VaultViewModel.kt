package com.filestech.notes_tech.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.VaultLockoutInProgressException
import com.filestech.notes_tech.security.vault.VaultPinWipedException
import com.filestech.notes_tech.security.vault.VaultValidationException
import com.filestech.notes_tech.security.vault.WrongPinException
import com.filestech.notes_tech.security.vault.WrongSecretException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * L'issue d'une tentative, telle que l'écran doit la présenter.
 *
 * ## 🔴 Les cas ne sont pas interchangeables
 *
 * `WrongSecret` dit « recommencez ». `Wiped` dit « c'est fini, définitivement ». Les confondre dans
 * un message générique — « échec du déverrouillage » — priverait l'utilisateur de la seule
 * information qui compte au moment où elle compte : que ses notes viennent d'être détruites.
 *
 * `Failed` est **tout le reste**, et il ne consomme aucune tentative. C'est la contrepartie de la
 * liste blanche du service : ici comme là-bas, un échec qui ne prouve rien ne doit rien coûter.
 */
sealed interface VaultAttempt {
    data object Success : VaultAttempt

    /**
     * Un coffre vient d'etre cree, et ses notes deja presentes ont ete chiffrees.
     *
     * ✅ **Distinct de [Success] parce qu'il porte un BILAN.** Une conversion qui laisse des notes
     * en clair n'est pas une reussite : l'utilisateur croirait son dossier protege alors qu'une
     * partie de son contenu est lisible au repos. C'est le seul moment ou on peut le lui dire.
     */
    data class Created(val encrypted: Int, val failed: Int) : VaultAttempt {
        val isComplete: Boolean get() = failed == 0
    }
    data class WrongSecret(val attemptsRemaining: Int?) : VaultAttempt
    data object Wiped : VaultAttempt
    data class LockedOut(val remainingMillis: Long) : VaultAttempt
    data class Failed(val message: String?) : VaultAttempt
}

/** Ce qu'une feuille de coffre affiche pendant qu'elle travaille. */
data class VaultSheetState(val busy: Boolean = false, val attempt: VaultAttempt? = null)

@HiltViewModel
class VaultViewModel @Inject constructor(private val vaults: FolderVaultService) : ViewModel() {

    private val _state = MutableStateFlow(VaultSheetState())
    val state: StateFlow<VaultSheetState> = _state.asStateFlow()

    /** La tentative en cours, pour pouvoir l'annuler. Voir [cancelAttempt]. */
    private var tentative: Job? = null

    fun unlockWithPassphrase(folderId: String, passphrase: String) =
        tenter { vaults.unlockWithPassphrase(folderId, passphrase) }

    fun unlockWithPin(folderId: String, pin: String) = tenter { vaults.unlockWithPin(folderId, pin) }

    /**
     * Cree un coffre a phrase secrete **et chiffre les notes deja presentes**.
     *
     * ## 🔴 Le second geste n'est pas optionnel
     *
     * `createPassphraseVault` pose le materiel du coffre et ouvre la session ; il ne touche pas au
     * contenu. Sans le chiffrement qui suit, un dossier converti affiche son cadenas pendant que
     * ses notes restent lisibles au repos dans la base.
     *
     * ⚠️ **`encryptAllNotesInFolder` n'avait AUCUN appelant** jusqu'au 2026-08-14, alors que
     * `docs/11-COFFRES.md` §8 affirmait le contraire. Chemin mort classique, troisieme occurrence de
     * ce motif dans ce portage apres `sweep()`. Trouve par l'audit de coherence, apres que le gate
     * complet soit passe au vert. Cf. `docs/04-PIEGES.md` §29.
     *
     * La reprotection au prochain deverrouillage l'aurait rattrape — mais « au prochain
     * deverrouillage » n'est pas « maintenant », et personne n'aurait su ce qui s'est passe entre
     * les deux.
     */
    fun createPassphraseVault(folderId: String, passphrase: String) = tenter {
        vaults.createPassphraseVault(folderId, passphrase)
        chiffrerLExistant(folderId)
    }

    /** Voir [createPassphraseVault] : meme enchainement, meme raison. */
    fun createPinVault(folderId: String, pin: String) = tenter {
        vaults.createPinVault(folderId, pin)
        chiffrerLExistant(folderId)
    }

    /**
     * Annule la tentative en cours.
     *
     * ## ⚠️ Pourquoi fermer la feuille ne suffisait pas
     *
     * Le travail tourne dans `viewModelScope`, qui survit a la fermeture de la feuille. Un
     * utilisateur qui saisit son secret puis annule pendant la derivation Argon2id — de l'ordre de
     * la seconde sur un appareil ancien — voyait le coffre s'ouvrir quand meme, une seconde plus
     * tard, sans que rien a l'ecran ne l'indique. Releve par une relecture externe (GPT-5.2).
     */
    fun cancelAttempt() {
        tentative?.cancel()
        tentative = null
        _state.value = VaultSheetState()
    }

    private suspend fun chiffrerLExistant(folderId: String) {
        val bilan = vaults.encryptAllNotesInFolder(folderId)
        _state.value = VaultSheetState(
            busy = false,
            attempt = VaultAttempt.Created(encrypted = bilan.done, failed = bilan.failed),
        )
    }

    /** Le temps restant avant qu'une nouvelle tentative soit acceptée. `0` s'il n'y en a pas. */
    fun lockoutRemainingMillis(folderId: String): Long = vaults.lockoutRemainingMillis(folderId)

    /** Efface le résultat affiché, une fois qu'il a été montré. */
    fun consumeAttempt() {
        _state.value = _state.value.copy(attempt = null)
    }

    /**
     * Exécute une tentative et **classe** son issue.
     *
     * ⚠️ Le `when` porte sur des types d'exception précis, jamais sur le message. Un classement par
     * message se casse à la première traduction, et ce classement-ci décide de ce qu'on annonce à
     * quelqu'un dont les notes viennent peut-être d'être effacées.
     */
    private fun tenter(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = VaultSheetState(busy = true)
        tentative = viewModelScope.launch {
            val issue = try {
                action()
                VaultAttempt.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: WrongPinException) {
                VaultAttempt.WrongSecret(e.attemptsRemaining)
            } catch (_: WrongSecretException) {
                // Coffre à phrase secrète : aucun compteur, donc aucun nombre d'essais à annoncer.
                VaultAttempt.WrongSecret(attemptsRemaining = null)
            } catch (_: VaultPinWipedException) {
                VaultAttempt.Wiped
            } catch (e: VaultLockoutInProgressException) {
                // Le freinage exponentiel a parlé : ce n'est ni une réussite ni un mauvais secret,
                // et surtout ça ne consomme rien. L'annoncer comme un échec ferait croire à
                // l'utilisateur qu'il vient de perdre un essai.
                VaultAttempt.LockedOut(e.remainingMillis)
            } catch (e: VaultValidationException) {
                VaultAttempt.Failed(e.message)
            } catch (e: Exception) {
                VaultAttempt.Failed(e.message ?: e::class.java.simpleName)
            }
            // ⚠️ Le chiffrement de l'existant a deja pose son propre bilan : ne pas l'ecraser
            // par un `Success` qui perdrait le decompte des notes restees en clair.
            if (_state.value.attempt !is VaultAttempt.Created) {
                _state.value = VaultSheetState(busy = false, attempt = issue)
            }
        }
    }
}
