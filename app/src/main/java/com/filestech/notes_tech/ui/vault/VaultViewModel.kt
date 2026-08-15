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
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
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

    /**
     * Une saisie refusée avant tout calcul : trop courte, mauvais format, dossier déjà coffre.
     *
     * 🔴 **Porte la RAISON, pas un message.** `VaultValidationException.message` vaut
     * « saisie refusee : PASSPHRASE_TOO_SHORT » — du texte interne, non traduit, jamais destiné à
     * quelqu'un. Il remontait tel quel jusqu'à l'écran, **en français comme en anglais**, alors que
     * six des huit raisons ont une chaîne localisée qui existe depuis le début et n'était jamais
     * utilisée. Relevé par l'audit i18n du 2026-08-14.
     *
     * Transporter l'énumération plutôt que le texte oblige la couche d'affichage à choisir une
     * chaîne, et un `when` exhaustif fait échouer à la compilation l'ajout d'une raison sans
     * traduction.
     */
    /**
     * Le coffre est cree, mais le chiffrement de son contenu n'a pas pu commencer.
     *
     * 🔴 **Distinct de [Failed], et c'est tout l'enjeu.** Un correctif precedent enchainait la
     * creation et le chiffrement dans la meme tentative : si le second levait, l'ecran annoncait
     * « creation impossible » alors que le dossier etait **deja un coffre en base**, avec ses notes
     * en clair. Un message qui ment sur l'etat reel, sur le chemin le plus sensible de
     * l'application.
     *
     * Trouve en relisant les correctifs de relecture — la regle qui dit de le faire
     * systematiquement a encore paye.
     */
    data class CreatedButNotEncrypted(val message: String?) : VaultAttempt

    data class Invalid(val reason: VaultValidationException.Reason) : VaultAttempt

    data class Failed(val message: String?) : VaultAttempt
}

/** Ce qu'une feuille de coffre affiche pendant qu'elle travaille. */
/**
 * Ce que la feuille est en train de faire.
 *
 * 🔴 **Un seul booléen `busy` mentait sur la phase.** La feuille annonçait « Dérivation en cours »
 * pendant TOUT le travail, y compris le re-chiffrement des notes déjà présentes — qui est la phase
 * longue quand le dossier en contient beaucoup. L'application publiée, elle, remplace son dialogue
 * par « Conversion du coffre… / Re-chiffrement des notes verrouillées en cours »
 * (`folders_drawer.dart:660`), et ses deux chaînes étaient traduites ici sans être lues nulle part.
 */
enum class PhaseDeCoffre {
    /** Argon2id : de l'ordre de la seconde sur un appareil ancien. */
    DERIVATION,

    /** Le contenu déjà présent passe sous la clé du coffre. Durée proportionnelle au nombre de notes. */
    CHIFFREMENT,
}

data class VaultSheetState(
    val busy: Boolean = false,
    val attempt: VaultAttempt? = null,
    val phase: PhaseDeCoffre = PhaseDeCoffre.DERIVATION,
)

@HiltViewModel
class VaultViewModel @Inject constructor(private val vaults: FolderVaultService) : ViewModel() {

    private val _state = MutableStateFlow(VaultSheetState())
    val state: StateFlow<VaultSheetState> = _state.asStateFlow()

    /** La tentative en cours, pour pouvoir l'annuler. Voir [cancelAttempt]. */
    private var tentative: Job? = null

    /**
     * Le dossier que la tentative en cours est en train de **convertir**, s'il y en a un.
     *
     * ⚠️ Renseigné pour les seules créations. Annuler un déverrouillage n'écrit rien : il n'y aurait
     * rien à constater, et lancer une lecture de base à chaque annulation de saisie de secret
     * ajouterait du travail pour ne jamais rien trouver.
     */
    private var dossierEnCreation: String? = null

    /**
     * Un constat est en cours. Voir [cancelAttempt] : il tient la place de la tentative annulée.
     *
     * ⚠️ **Un booléen posé AVANT le `launch`, et non le `Job` rendu par celui-ci.** `viewModelScope`
     * démarre sur `Main.immediate` : le corps de la coroutine commence à s'exécuter **avant** que
     * `launch` ait rendu la main. Écrire `constat = viewModelScope.launch { … }` laisse donc une
     * fenêtre où le `finally` remet le champ à zéro *puis* l'affectation le repose — et il ne
     * redescend plus jamais. [cancelAttempt] refuserait alors tout, définitivement et en silence.
     */
    private var constatEnCours = false

    /**
     * Le dossier devenu un coffre **malgré** une annulation, tant que l'écran ne l'a pas annoncé.
     *
     * L'écran l'annonce ; la feuille ne le peut pas, elle vient de se fermer. Même raison que pour
     * `onConversionIncomplete` — un message posé sur une surface qui disparaît disparaît avec elle.
     *
     * ## 🔴 Un porteur retenu, PAS un flux d'évènements
     *
     * C'était un `MutableSharedFlow` sans `replay`, alimenté par `tryEmit`. Un tel flux ne garde
     * rien pour un collecteur futur : émettre quand l'écran d'accueil n'est pas composé — parce que
     * l'utilisateur a ouvert une note dans la seconde qui a suivi son annulation — **jette le
     * message**. On retombait alors exactement sur le silence que ce constat existe pour rompre.
     * Relevé CONFIRMÉ par une relecture externe (GPT-5.2, 2026-08-15).
     *
     * Retenu, l'avertissement attend le retour sur l'accueil. Il n'est effacé que par
     * [constatAnnonce], c'est-à-dire une fois **vu**.
     */
    private val _creationEchappee = MutableStateFlow<String?>(null)
    val creationEchappee: StateFlow<String?> = _creationEchappee.asStateFlow()

    /** À appeler une fois le constat affiché, et pas avant. */
    fun constatAnnonce() {
        _creationEchappee.value = null
    }

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
    fun createPassphraseVault(folderId: String, passphrase: String) = tenter(dossierConverti = folderId) {
        vaults.createPassphraseVault(folderId, passphrase)
        chiffrerLExistant(folderId)
    }

    /** Voir [createPassphraseVault] : meme enchainement, meme raison. */
    fun createPinVault(folderId: String, pin: String) = tenter(dossierConverti = folderId) {
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
        // 🔴 **Rien à annuler une fois le coffre créé.** Pendant [PhaseDeCoffre.CHIFFREMENT], le
        // matériel du coffre est déjà en base : couper ici laisserait le dossier verrouillé avec
        // une partie de son contenu en clair, et l'écran affirmerait une annulation qui n'a pas eu
        // lieu. L'écran refuse déjà de se fermer à ce moment-là ; ce garde-ci est la seconde
        // barrière, pour le jour où un autre appelant l'oubliera.
        if (_state.value.phase == PhaseDeCoffre.CHIFFREMENT) return
        // ⚠️ Un constat en cours tient la place : le bousculer rouvrirait la fenêtre qu'il ferme.
        if (constatEnCours) return

        val tache = tentative
        val dossier = dossierEnCreation
        tentative = null
        dossierEnCreation = null

        // 🔴 **Couper D'ABORD, trier ensuite.**
        //
        // Cette ligne était sous le tri, donc après un `return` — et un déverrouillage, qui n'a pas
        // de dossier en création, sortait **sans être annulé**. La dérivation continuait, ouvrait la
        // session, et le coffre s'ouvrait une seconde après que l'utilisateur ait annulé : très
        // exactement le défaut que cette fonction existe pour empêcher, réintroduit par un correctif
        // qui ne visait même pas ce chemin. Relevé CONFIRMÉ par les DEUX relecteurs externes
        // (Gemini et GPT-5.2, 2026-08-15).
        //
        // ⚠️ Annuler est le geste demandé ; le constat n'est qu'un rattrapage. L'ordre des deux ne
        // se discute pas.
        tache?.cancel()

        if (tache == null || dossier == null) {
            // Un déverrouillage, ou rien du tout : aucune écriture possible, donc rien à constater.
            _state.value = VaultSheetState()
            return
        }

        // 🔴 **`busy` reste posé jusqu'à la fin du constat**, et c'est le correctif.
        //
        // Sans ça, l'annulation rendait la main **avant** que la tentative annulée soit dénouée.
        // Séquence : l'utilisateur annule sur le dossier A, relance aussitôt une conversion du même
        // dossier A, celle-ci écrit son matériel — et le constat de la première, qui arrive après,
        // lit `isVault(A) == true` et annonce « annulation trop tardive » alors que la première
        // annulation avait parfaitement fonctionné. Un avertissement qui crie au loup sur le chemin
        // le plus sensible apprend à ne plus le lire. Relevé CONFIRMÉ par une relecture externe
        // (Gemini, 2026-08-15).
        //
        // Le garder posé ferme aussi une seconde fenêtre, signalée par l'autre relecteur : la
        // tentative en train de mourir ne peut plus écraser le `dossierEnCreation` d'une tentative
        // suivante, puisqu'il ne peut pas y en avoir.
        _state.value = VaultSheetState(busy = true)
        constatEnCours = true
        viewModelScope.launch {
            try {
                constaterApresAnnulation(tache, dossier)
            } finally {
                // ⚠️ `finally`, jamais la fin du bloc : sans lui, une annulation de `viewModelScope`
                // ou une exception imprévue laisserait `busy` posé, et plus aucun coffre ne pourrait
                // être créé ni ouvert de toute la vie de ce ViewModel.
                constatEnCours = false
                _state.value = VaultSheetState()
            }
        }
    }

    /**
     * Regarde ce que l'annulation a **réellement** laissé en base, et le dit si ce n'est pas rien.
     *
     * ## 🔴 La course que la garde de phase ne ferme pas
     *
     * [cancelAttempt] décide sur [PhaseDeCoffre], et la phase ne bascule qu'au retour de
     * `createXVault`, dans [chiffrerLExistant]. Entre l'écriture du matériel en base et ce retour,
     * il y a une reprise de coroutine — Room rend la main sur son exécuteur puis reprend sur le
     * fil principal. Un appui sur « Annuler » posé dans la file d'événements avant cette reprise est
     * traité **avant** elle : la phase vaut encore `DERIVATION`, la garde laisse passer, le travail
     * est coupé — et le dossier est déjà un coffre.
     *
     * L'état qui en résulte est le pire des trois : le dossier porte un cadenas, **aucune session
     * n'a été ouverte** (`sessions.open` suit l'écriture et ne s'exécute pas), la clé de dossier a
     * été effacée par le `catch` du service, et **toutes** les notes sont en clair au repos. La
     * réparation existe — `reprotectPlaintextNotes` tourne au prochain déverrouillage — mais
     * « au prochain déverrouillage » peut ne jamais venir, puisque l'utilisateur croit avoir annulé.
     *
     * ## ⚠️⚠️ Pourquoi le correctif « propre » n'a PAS été appliqué
     *
     * Fermer la course demanderait de rendre l'écriture et l'ouverture de session insécables
     * (`withContext(NonCancellable)` dans le service). Or le `catch (Throwable)` qui entoure cette
     * zone efface la clé de dossier — et cette clé, `sessions.open` vient de la confier à la
     * session. Sous `NonCancellable`, la sortie du bloc lèverait la `CancellationException` du
     * parent, ce `catch` s'exécuterait, et il effacerait **la clé d'une session vivante**. Les notes
     * chiffrées ensuite le seraient sous une clé nulle. C'est exactement le défaut consigné dans
     * `docs/04-PIEGES.md` — un tableau d'octets partagé entre deux propriétaires — et il détruit des
     * données, là où la course, elle, en laisse en clair.
     *
     * Le choix est donc assumé : **constater plutôt que prévenir.** Ce qui rendait la course grave
     * n'est pas qu'elle existe, c'est qu'elle était **muette**.
     *
     * @param tache la tentative annulée. On attend sa fin : lire la base pendant qu'elle se dénoue
     *   répondrait sur un état qui n'est pas encore le dernier.
     */
    private suspend fun constaterApresAnnulation(tache: Job, folderId: String) {
        // ⚠️⚠️ **L'attente est BORNÉE, et ici le sens de la borne est l'inverse de celui du
        // presse-papiers.**
        //
        // `busy` reste posé tant que ce constat n'a pas rendu la main. Une tentative qui ne se
        // dénouerait jamais — travail non coopératif, entrée-sortie bloquée — figerait donc **toutes
        // les feuilles de coffre pour la vie de ce ViewModel**, sans un mot : plus aucun coffre ne
        // s'ouvrirait ni ne se créerait. Relevé PROBABLE par une relecture externe (GPT-5.2).
        //
        // Là où borner le réarmement du presse-papiers faisait *abandonner du clair exposé*, borner
        // ici ne fait qu'accepter de lire la base un peu tôt — le pire cas est un constat manqué,
        // c'est-à-dire le silence d'avant. On ne troque pas un blocage certain contre ça.
        withTimeoutOrNull(ATTENTE_MAX_DENOUEMENT_MS) { tache.join() }
        val devenuCoffre = try {
            vaults.isVault(folderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ne rien affirmer sur ce qu'on n'a pas pu lire. Le silence est ici le seul verdict
            // honnête — annoncer une création qu'on n'a pas constatée serait l'erreur inverse.
            Timber.w(e, "constat apres annulation d'une creation de coffre")
            return
        }
        // ⚠️ Un second constat écraserait le premier — porteur à une seule place. C'est sans
        // conséquence visible : le message ne nomme pas le dossier, deux occurrences disent donc
        // exactement la même chose. Signalé PROBABLE par une relecture externe (GPT-5.2) ; assumé,
        // plutôt qu'une file d'attente pour un cas où l'utilisateur ne verrait aucune différence.
        if (devenuCoffre) _creationEchappee.value = folderId
    }

    /** Vrai tant que le contenu d'un dossier fraîchement converti passe sous la clé du coffre. */
    fun chiffrementEnCours(): Boolean = _state.value.busy && _state.value.phase == PhaseDeCoffre.CHIFFREMENT

    /**
     * Chiffre les notes deja presentes, et **ne laisse jamais l'echec de ce geste passer pour un
     * echec de la creation**.
     *
     * ⚠️ Le coffre existe deja quand cette fonction est appelee. Quoi qu'il arrive ici, le dossier
     * EST un coffre : le dire autrement serait mentir sur l'etat de la base.
     */
    private suspend fun chiffrerLExistant(folderId: String) {
        // La dérivation est finie ; ce qui suit peut durer bien plus longtemps. Le dire.
        _state.value = _state.value.copy(phase = PhaseDeCoffre.CHIFFREMENT)

        val issue = try {
            val bilan = vaults.encryptAllNotesInFolder(folderId)
            rattraper(folderId, encrypted = bilan.done, failed = bilan.failed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VaultAttempt.CreatedButNotEncrypted(e.message ?: e::class.java.simpleName)
        }
        _state.value = VaultSheetState(busy = false, attempt = issue)
    }

    /**
     * Reprend **tout de suite** les notes que le premier passage a laissées en clair.
     *
     * 🔴 **Le dossier est DÉJÀ marqué coffre.** Laisser des notes lisibles au repos, c'est afficher
     * un cadenas qui ne protège pas ce qu'il a l'air de protéger. La réparation existait déjà
     * ([FolderVaultService.reprotectPlaintextNotes]) mais ne tournait qu'à la **prochaine ouverture**
     * du coffre — donc du clair au repos entre les deux, sans que personne ne le sache.
     *
     * La session est ouverte ici, juste après la conversion : c'est le seul moment où la clé est
     * disponible et où l'utilisateur regarde. Repris de l'application publiée, où ce rattrapage a été
     * ajouté sur relevé d'une relecture externe (`folders_drawer.dart:676`).
     *
     * ⚠️ **Un rattrapage qui échoue n'aggrave rien** : on retombe sur le bilan initial, que l'écran
     * annonce tel quel. Il ne doit surtout pas transformer une conversion partielle en échec total.
     */
    private suspend fun rattraper(folderId: String, encrypted: Int, failed: Int): VaultAttempt.Created {
        if (failed <= 0) return VaultAttempt.Created(encrypted = encrypted, failed = 0)

        val reprises = try {
            vaults.reprotectPlaintextNotes(folderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "rattrapage des notes restées en clair après conversion")
            0
        }
        // `coerceAtLeast` et non une soustraction nue : `reprotectPlaintextNotes` balaie TOUT le
        // dossier et peut donc reprendre plus de notes que le premier passage n'en avait manquées.
        return VaultAttempt.Created(
            encrypted = encrypted + reprises,
            failed = (failed - reprises).coerceAtLeast(0),
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
    private fun tenter(dossierConverti: String? = null, action: suspend () -> Unit) {
        if (_state.value.busy) return
        // 🔴 **Le constat garde la porte lui-même, il ne s'en remet pas à `busy`.**
        //
        // `busy` suffisait *en théorie* — [cancelAttempt] le laisse posé. Mais une tentative annulée
        // qui atteindrait quand même la fin de ce bloc y repose `busy = false`, et rouvrirait la
        // fenêtre que le constat ferme. Faire dépendre un invariant d'un état qu'un autre chemin
        // peut réécrire, c'est le tenir par déduction et non par construction. Relevé CONFIRMÉ par
        // une relecture externe (GPT-5.2, 2026-08-15).
        if (constatEnCours) return
        _state.value = VaultSheetState(busy = true)
        // ⚠️ **Après** le refus pour cause de travail en cours : renseigner avant écraserait le
        // dossier de la tentative qui tourne toujours, et l'annulation constaterait sur le mauvais.
        dossierEnCreation = dossierConverti
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
                VaultAttempt.Invalid(e.reason)
            } catch (e: Exception) {
                // ⚠️ **Le message est conservé ici, contrairement au mode panique**, et il faut le
                // dire plutôt que de le laisser deviner.
                //
                // `PanicService.etape()` ne garde que le nom de la classe : son écran de fin peut
                // être lu par-dessus l'épaule de quelqu'un sous contrainte, et un chemin de fichier
                // y désignerait l'application. Ces feuilles-ci portent aussi `SecureWindowGuard`,
                // mais pour une autre raison — le champ de saisie du secret, pas le texte d'erreur —
                // et l'utilisateur y est en train d'ouvrir son propre coffre.
                //
                // Tous les échecs que l'utilisateur peut corriger sont déjà classés par TYPE
                // au-dessus : mauvais secret, freinage, coffre auto-détruit, refus de validation.
                // Ce `catch` résiduel est par construction le « quelque chose d'inattendu a cassé »,
                // et le message brut est alors le seul indice exploitable pour diagnostiquer.
                //
                // ⚠️ Point ouvert, à trancher : à la différence de `ExportViewModel`, où « espace
                // insuffisant » sert directement l'utilisateur, ce message-ci ne lui apprend rien
                // d'actionnable — une `SQLiteException` ou une `IOException` y déposerait un chemin
                // de bac à sable illisible. Le remplacer par un message générique serait plus
                // honnête ; ce serait aussi changer le comportement d'une couche antérieure à la
                // phase 6, ce qui ne se fait pas dans un lot de correctifs d'audit.
                //
                // Signalé comme divergence non documentée par l'audit de cohérence du 2026-08-15,
                // qui classait son exploitabilité PROBABLE et non CONFIRMÉE.
                VaultAttempt.Failed(e.message ?: e::class.java.simpleName)
            }
            // La tentative est allée à son terme : plus rien à constater après coup. Sur le chemin
            // d'annulation, ce `null` a déjà été posé — et lu — par [cancelAttempt].
            dossierEnCreation = null
            // ⚠️ Le chiffrement de l'existant a deja pose son propre bilan : ne pas l'ecraser
            // par un `Success` qui perdrait le decompte des notes restees en clair.
            if (!_state.value.attempt.coffreExiste()) {
                _state.value = VaultSheetState(busy = false, attempt = issue)
            }
        }
    }

    private companion object {
        /**
         * Deux secondes pour qu'une tentative annulee se denoue.
         *
         * Large au regard de ce qui reste a faire — une derivation Argon2id deja lancee, une
         * ecriture Room —, et court au regard de ce qu'un depassement coute : rendre la main.
         */
        const val ATTENTE_MAX_DENOUEMENT_MS = 2_000L
    }
}

/**
 * `true` quand le coffre est en base, quelle que soit la suite.
 *
 * Empeche l'ecrasement d'un bilan de conversion par un `Success` ou un `Failed` qui perdrait ce que
 * l'utilisateur doit savoir — et sert a l'ecran pour cesser de proposer « Annuler » sur une creation
 * qui a deja eu lieu.
 */
internal fun VaultAttempt?.coffreExiste(): Boolean =
    this is VaultAttempt.Created || this is VaultAttempt.CreatedButNotEncrypted
