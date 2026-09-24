package com.filestech.notes_tech.security.vault

/**
 * Les échecs des coffres, classés par **ce qu'il faut en conclure**, pas par ce qui les a produits.
 *
 * ## 🔴 La distinction qui compte : « mauvais secret » contre « je n'ai pas pu regarder »
 *
 * Un coffre PIN s'auto-détruit au cinquième échec. Confondre un échec système avec une tentative
 * ratée détruit donc les notes d'un utilisateur qui a saisi le bon code. La version Flutter a payé
 * cette leçon en v1.0.3 : elle traitait toute exception du Keystore comme un échec légitime, et a
 * dû passer d'une liste noire à une **liste blanche** — seule l'invalidation permanente de la clé
 * compte comme une raison d'effacer.
 *
 * C'est la même forme que le contrat à trois états des sources de KEK (`docs/03-KEK-ACQUISITION.md`) :
 * absent, présent, ou *je n'ai pas pu savoir*. Le troisième état ne se replie jamais sur le premier.
 *
 * | Ce qui est levé | Ce que l'appelant doit en faire |
 * |---|---|
 * | [WrongSecretException] | compter une tentative ratée |
 * | [MalformedVaultDataException] | **ne pas** compter — la donnée est abîmée, pas le secret faux |
 * | [KeystoreUnavailableException] | **ne pas** compter, proposer de réessayer plus tard |
 * | [KeystorePermanentlyInvalidatedException] | effacer : le coffre est légitimement irrécupérable |
 * | [VaultSessionClosedException] | redemander le secret |
 * | [VaultValidationException] | refuser la saisie, sans toucher au compteur |
 */
sealed class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * L'étiquette GCM n'a pas validé : le secret fourni n'est pas le bon.
 *
 * ⚠️ **Le message ne cite ni le secret, ni le contenu, ni l'identifiant du dossier.** Une exception
 * voyage dans les journaux et les rapports de plantage, et celle-ci naît au contact d'un secret.
 */
class WrongSecretException(cause: Throwable? = null) :
    VaultException("le secret fourni ne deverrouille pas ce coffre", cause)

/**
 * L'enveloppe lue en base n'a pas la forme attendue : trop courte, ou longueur de titre incohérente.
 *
 * ⚠️ **Ce n'est pas une tentative ratée.** Traiter une base abîmée comme un mauvais PIN ferait
 * s'auto-détruire un coffre pour une corruption de fichier, ce qui est exactement l'inverse du
 * service rendu.
 */
class MalformedVaultDataException(detail: String, cause: Throwable? = null) :
    VaultException("donnee de coffre malformee : $detail", cause)

/**
 * Le Keystore n'a pas pu être interrogé, et **on ne sait pas** si la clé est bonne.
 *
 * Causes réelles observées côté Flutter : migration StrongBox → TEE pendant une mise à jour
 * Samsung, écran verrouillé alors que la clé exige un appareil déverrouillé, mémoire insuffisante
 * dans la couche native.
 *
 * ⚠️ **N'incrémente aucun compteur et ne déclenche aucun effacement.** L'utilisateur n'est pas en
 * cause ; le seul geste correct est de proposer de réessayer.
 */
class KeystoreUnavailableException(cause: Throwable? = null) :
    VaultException("keystore momentanement indisponible", cause)

/**
 * Le système a détruit la clé Keystore du coffre — changement d'écran de verrouillage, retrait de
 * la biométrie, réinitialisation partielle.
 *
 * Le coffre est **légitimement** irrécupérable : plus aucune clé n'existe pour déballer son
 * contenu. C'est le seul cas, avec l'épuisement des tentatives, où l'effacement est la bonne
 * réponse plutôt qu'une perte de données.
 */
class KeystorePermanentlyInvalidatedException(cause: Throwable? = null) :
    VaultException("cle keystore definitivement invalidee par le systeme", cause)

/**
 * La clé Keystore créée n'est **pas** retenue par du matériel sécurisé.
 *
 * Sur un appareil sans TEE ni StrongBox — émulateur, système modifié — le générateur retombe
 * silencieusement sur une implantation logicielle. Le PIN redeviendrait alors attaquable hors de
 * l'appareil, ce qui vide le mode PIN de sa seule vraie protection : quatre chiffres ne résistent
 * à rien d'autre. La clé est supprimée et l'interface doit proposer un coffre passphrase.
 */
class KeystoreSoftwareOnlyException : VaultException("cle keystore non retenue par du materiel securise")

/**
 * A PIN vault key cannot be created because the phone has NO screen lock.
 *
 * The key requires an unlocked device (`setUnlockedDeviceRequired`, API 28+), and Android refuses to
 * create such a key when there is no PIN, pattern or password to unlock with. Before 2026-09-24 the
 * failure surfaced as [KeystoreUnavailableException], whose own documentation says "retry later" —
 * wrong advice here: retrying changes nothing until a screen lock exists. notes_tech 2.0.9 names the
 * case (`DEVICE_NOT_SECURE`); so does the port now.
 *
 * ⚠️ Raised on KEY GENERATION only. Everywhere else a Keystore failure keeps meaning "could not
 * look, retry" — which is also why this is not a subclass of [KeystoreUnavailableException].
 */
class KeystoreDeviceNotSecureException(cause: Throwable? = null) :
    VaultException("aucun verrouillage d'ecran : cle de coffre PIN impossible a creer", cause)

/**
 * Aucune session n'est ouverte pour ce coffre : sa clé n'existe nulle part en mémoire.
 *
 * ⚠️ Le portage compte **deux** exceptions pour cette condition, et c'est délibéré.
 * `domain.repository.VaultLockedException` porte en plus l'identifiant de la note et appartient au
 * contrat de `VaultSealer` — c'est celle que voit la couche d'écriture, qui n'a pas à connaître le
 * paquet `security`. Celle-ci sert aux opérations qui portent sur un dossier entier.
 */
class VaultSessionClosedException(val folderId: String) :
    VaultException("aucune session ouverte pour le coffre $folderId")

/** Une saisie refusée : passphrase trop courte, PIN hors format, dossier déjà coffre. */
class VaultValidationException(val reason: Reason) : VaultException("saisie refusee : $reason") {

    /** Ce que l'interface doit expliquer. Le détail chiffré reste ici, jamais dans le message. */
    enum class Reason {
        FOLDER_NOT_FOUND,
        PASSPHRASE_TOO_SHORT,
        PIN_LENGTH_OUT_OF_RANGE,
        PIN_NOT_DIGITS_ONLY,
        ALREADY_A_VAULT,
        NOT_A_VAULT,
        NOT_A_PIN_VAULT,
        NOT_A_PASSPHRASE_VAULT,
    }
}

/** Le PIN était faux. [attemptsRemaining] permet d'avertir avant l'effacement. */
class WrongPinException(val attemptsRemaining: Int) :
    VaultException("pin incorrect, $attemptsRemaining tentative(s) restante(s)")

/**
 * Le coffre PIN a épuisé ses tentatives et a été détruit.
 *
 * La clé Keystore est supprimée, les notes verrouillées effacées, le dossier redevenu ordinaire.
 * C'est le contrat annoncé — cinq échecs, perte définitive — et non un incident.
 */
class VaultPinWipedException(val folderId: String, cause: Throwable? = null) :
    VaultException("coffre $folderId auto-detruit apres epuisement des tentatives", cause)

/**
 * Une tentative est arrivée avant la fin du délai imposé par l'échec précédent.
 *
 * Le délai croît à chaque échec, ce qui coûte cher à qui essaie des codes à la chaîne et ne se
 * remarque pas quand on se trompe une fois.
 */
class VaultLockoutInProgressException(val remainingMillis: Long) :
    VaultException("nouvelle tentative dans $remainingMillis ms")
