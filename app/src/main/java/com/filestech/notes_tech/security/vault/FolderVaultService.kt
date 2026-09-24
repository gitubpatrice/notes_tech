package com.filestech.notes_tech.security.vault

import com.filestech.notes_tech.core.crypto.wipe
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.dao.FolderDao
import com.filestech.notes_tech.data.local.dao.VaultMaterial
import com.filestech.notes_tech.data.local.entity.NoteEntity
import com.filestech.notes_tech.data.local.mapper.toDomain
import com.filestech.notes_tech.domain.model.EncryptedBody
import com.filestech.notes_tech.domain.model.EncryptedFormat
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.VaultMode
import com.filestech.notes_tech.domain.repository.VaultOpener
import com.filestech.notes_tech.domain.repository.VaultSealer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import com.filestech.notes_tech.domain.repository.VaultLockedException as SealRefusedException

/**
 * Les coffres : création, déverrouillage, scellement des notes, effacement après cinq échecs.
 *
 * Portage de `services/security/folder_vault_service.dart` (1 582 lignes), dont la logique a été
 * reprise geste par geste — y compris ses correctifs, qui sont l'essentiel de sa valeur. Chaque
 * précaution non évidente porte ici la raison pour laquelle elle existe, parce qu'aucune n'a été
 * écrite par anticipation : toutes viennent d'un incident.
 *
 * ## Les trois couches d'un coffre à code
 *
 * ```
 *   code ─Argon2id(t=2, m=32 Mo)→ clé du code
 *   clé du coffre (32 octets tirés au sort) ─AES-GCM(clé du code, AAD = id du dossier)→ scellé interne
 *   scellé interne ─AES-GCM(clé du Keystore, liée à l'appareil)→ vault_pin_blob
 * ```
 *
 * Le coffre à phrase secrète n'a que les deux premières : sa clé ne dépend d'aucun matériel, ce qui
 * le rend transférable — et c'est pourquoi il reste le mode à proposer quand le Keystore refuse.
 *
 * ## 🔴 Ce que ce service ne fait jamais
 *
 * Il n'écrit pas de ligne entière. Ni dans `folders`, ni dans `notes`. Effacer par inadvertance
 * `vault_kek_wrapped` emporterait **toutes** les notes du coffre, sans qu'aucune saisie ne les
 * rende — le matériel qui permettait de les déchiffrer n'existerait plus. Cf. `docs/04-PIEGES.md`.
 */
@Singleton
class FolderVaultService @Inject constructor(
    private val databases: DatabaseProvider,
    private val keystore: VaultKeystore,
    private val sessions: VaultSessions,
    private val wipeJournal: VaultWipeJournal,
    private val clock: Clock,
) : VaultSealer,
    VaultOpener {

    /** Les coffres ouverts. Ne porte aucune clé — seulement des identifiants, pour l'interface. */
    val unlockedFolderIds = sessions.unlockedFolderIds

    fun isUnlocked(folderId: String): Boolean = sessions.isUnlocked(folderId)

    fun lock(folderId: String) = sessions.lock(folderId)

    /** À la mise en pause de l'application, et par le mode panique. */
    fun lockAll() = sessions.lockAll()

    /** Le temps à attendre avant une nouvelle tentative sur ce coffre. `0` s'il n'y en a pas. */
    fun lockoutRemainingMillis(folderId: String): Long = sessions.lockoutRemainingMillis(folderId)

    /**
     * Le dossier porte-t-il du matériel de coffre **dans la base** ?
     *
     * ⚠️ **Ne demande rien à la session, et c'est le point.** [isUnlocked] répond sur ce que
     * l'application a en mémoire ; celle-ci répond sur ce qui est écrit sur le disque. Les deux
     * divergent exactement dans le cas qui justifie cette fonction : une création annulée trop tard,
     * où le matériel est en base sans qu'aucune session n'ait été ouverte.
     *
     * @see com.filestech.notes_tech.ui.vault.VaultViewModel.cancelAttempt
     */
    suspend fun isVault(folderId: String): Boolean =
        databases.get().folderDao().vaultMaterial(folderId)?.isVault == true

    // ── Création ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Fait d'un dossier ordinaire un coffre à phrase secrète, et **ouvre sa session**.
     *
     * La session reste ouverte à dessein : l'appelant enchaîne en général avec le chiffrement des
     * notes déjà présentes, ce qui demande la clé.
     *
     * ⚠️ **Les notes existantes ne sont PAS chiffrées ici.** Le dossier devient un coffre, son
     * contenu reste en clair jusqu'à ce que l'appelant le chiffre. L'application publiée fait pareil
     * et l'assume ; l'écart, c'est qu'ici la garde d'écriture de `NotesRepository` protège au moins
     * toute écriture **nouvelle** dès l'instant où le dossier porte un sel.
     */
    suspend fun createPassphraseVault(folderId: String, passphrase: String) {
        requireValidPassphrase(passphrase)
        val material = requireMaterial(folderId)
        if (material.isVault) throw VaultValidationException(VaultValidationException.Reason.ALREADY_A_VAULT)

        val salt = VaultCrypto.newSalt()
        val nonce = VaultCrypto.newNonce()
        val derived = VaultCrypto.derivePassphraseKey(passphrase.toByteArray(Charsets.UTF_8), salt)
        // ⚠️ The folder key is drawn AFTER the derivation, and inside the `try` that wipes it.
        // It used to be drawn first, outside: a derivation that throws — Argon2id asks for 64 MiB,
        // which an old phone can refuse — left the key in memory with nobody to wipe it. notes_tech
        // 2.0.9 fixed the same order (`folder_vault_service.dart:279-282`).
        val folderKey = VaultCrypto.newFolderKey()
        try {
            val wrapped = VaultCrypto.seal(derived, nonce, folderKey, folderId.toByteArray(Charsets.UTF_8))
            val provisioned = databases.get().folderDao().provisionPassphraseVault(
                id = folderId,
                salt = salt,
                kekWrapped = wrapped,
                iv = nonce,
                verifier = VaultCrypto.verifierFor(folderKey),
                updatedAt = clock.millis(),
            )
            // La requête ne convertit que si le dossier n'était pas déjà un coffre. Zéro ligne
            // signifie qu'il l'est devenu entre la lecture et l'écriture : ne pas ouvrir de session
            // sur une clé que la base n'a pas retenue.
            check(provisioned > 0) { "conversion en coffre refusee pour $folderId" }
            sessions.open(folderId, folderKey)
        } catch (e: Throwable) {
            // La clé n'a pas trouvé de propriétaire : elle ne doit pas rester en mémoire.
            folderKey.wipe()
            throw e
        } finally {
            derived.wipe()
        }
    }

    /**
     * Fait d'un dossier ordinaire un coffre à code, et ouvre sa session.
     *
     * ⚠️ **La clé Keystore est supprimée puis recréée**, comme dans l'application publiée. Un alias
     * déjà pris ne devrait pas exister, mais s'il existe — effacement interrompu, réinstallation
     * partielle — le réutiliser scellerait le nouveau coffre avec une clé dont on ne sait rien.
     */
    suspend fun createPinVault(folderId: String, pin: String) {
        requireValidPin(pin)
        val material = requireMaterial(folderId)
        if (material.isVault) throw VaultValidationException(VaultValidationException.Reason.ALREADY_A_VAULT)

        val alias = VaultParams.pinKeystoreAlias(folderId)
        val salt = VaultCrypto.newSalt()
        val nonce = VaultCrypto.newNonce()
        val pinKey = VaultCrypto.derivePinKey(pin.toByteArray(Charsets.UTF_8), salt)
        // ⚠️ After the derivation — same reason as in [createPassphraseVault].
        val folderKey = VaultCrypto.newFolderKey()
        try {
            val inner = VaultCrypto.seal(pinKey, nonce, folderKey, folderId.toByteArray(Charsets.UTF_8))
            keystore.deleteKey(alias)
            keystore.createKey(alias)
            val sealed = keystore.seal(alias, inner)
            inner.wipe()

            val provisioned = databases.get().folderDao().provisionPinVault(
                id = folderId,
                salt = salt,
                iv = nonce,
                verifier = VaultCrypto.verifierFor(folderKey),
                pinBlob = sealed.ciphertext,
                pinIv = sealed.nonce,
                updatedAt = clock.millis(),
            )
            if (provisioned <= 0) {
                // 🔴 La clé Keystore vient d'être créée et ne protège plus rien. La laisser en
                // ferait un orphelin dans le matériel sécurisé, et surtout un alias occupé qui
                // gênerait une nouvelle tentative de conversion.
                echoue { keystore.deleteKey(alias) }
                error("conversion en coffre a code refusee pour $folderId")
            }
            sessions.open(folderId, folderKey)
        } catch (e: Throwable) {
            folderKey.wipe()
            throw e
        } finally {
            pinKey.wipe()
        }
    }

    // ── Déverrouillage ───────────────────────────────────────────────────────────────────────────

    /**
     * Ouvre un coffre à phrase secrète.
     *
     * @throws WrongSecretException si la phrase est fausse. Le freinage exponentiel est armé au
     *   passage : essayer un dictionnaire coûte alors 1, 2, 4, 8, 16 puis 30 secondes par essai.
     * @throws VaultLockoutInProgressException si le freinage précédent court encore.
     */
    suspend fun unlockWithPassphrase(folderId: String, passphrase: String) {
        val material = requireVaultMaterial(folderId)
        if (material.effectiveMode == VaultMode.PIN) {
            throw VaultValidationException(VaultValidationException.Reason.NOT_A_PASSPHRASE_VAULT)
        }
        requireNoLockout(folderId)

        val wrapped = requireColumn(material.kekWrapped, "vault_kek_wrapped")
        val nonce = requireColumn(material.iv, "vault_iv")
        val verifier = requireColumn(material.verifier, "vault_verifier")
        val salt = requireColumn(material.salt, "vault_salt")

        sessions.whileUnlocking(folderId) {
            val derived = VaultCrypto.derivePassphraseKey(passphrase.toByteArray(Charsets.UTF_8), salt)
            val folderKey = try {
                VaultCrypto.open(derived, nonce, wrapped, folderId.toByteArray(Charsets.UTF_8))
            } catch (e: WrongSecretException) {
                sessions.recordFailure(folderId)
                throw e
            } finally {
                derived.wipe()
            }
            openVerifiedSession(folderId, folderKey, verifier)
        }
    }

    /**
     * Ouvre un coffre à code.
     *
     * ## L'ordre des gestes est un dispositif de sécurité, pas une commodité
     *
     * Le compteur est incrémenté **avant** la tentative. Sinon, il suffirait de tuer l'application
     * juste après un échec, avant l'écriture du compteur, pour disposer d'essais illimités.
     *
     * En contrepartie, tout échec qui **ne prouve rien** doit reprendre l'incrément : Keystore
     * momentanément indisponible, scellé abîmé. C'est la liste blanche héritée de la v1.0.3 —
     * seules l'invalidation permanente de la clé et un code effectivement faux comptent.
     *
     * @throws WrongPinException code faux, avec le nombre d'essais restants.
     * @throws VaultPinWipedException le coffre vient d'être détruit, définitivement.
     */
    @Suppress("ThrowsCount")
    suspend fun unlockWithPin(folderId: String, pin: String) {
        val material = requireVaultMaterial(folderId)
        if (material.effectiveMode != VaultMode.PIN) {
            throw VaultValidationException(VaultValidationException.Reason.NOT_A_PIN_VAULT)
        }
        if (material.failedAttempts >= VaultParams.PIN_MAX_ATTEMPTS) {
            // Un coffre déjà au maximum ne devrait pas exister — l'effacement précédent l'aurait
            // démoli. Le cas se présente sur une base restaurée depuis une sauvegarde prise pendant
            // un effacement interrompu.
            autoWipePinVault(folderId)
            throw VaultPinWipedException(folderId)
        }
        requireNoLockout(folderId)
        requireValidPin(pin)

        val folderDao = databases.get().folderDao()
        countingOneAttempt(folderDao, folderId, material.failedAttempts + 1) { attemptsAfter ->
            attemptWithPin(folderDao, folderId, pin, material, attemptsAfter)
        }
    }

    /**
     * 🔴 Reprend l'incrément de tentative pour **toute** sortie qui ne prouve pas un code faux.
     *
     * Le compteur est posé avant la tentative — sans quoi tuer l'application entre l'échec et
     * l'écriture donnerait des essais illimités. Le prix de cette précaution est celui-ci : chaque
     * sortie doit dire si elle a prouvé quelque chose, sinon le compteur avance pour rien et cinq
     * incidents détruisent un coffre dont le code était bon à chaque fois.
     *
     * | Sortie | Consomme une tentative ? | Pourquoi |
     * |---|---|---|
     * | [WrongPinException] | **oui** | le code était faux, c'est exactement ce qu'on compte |
     * | [VaultPinWipedException] | sans objet | le coffre n'existe plus, son compteur non plus |
     * | tout le reste | **non** | Keystore muet, base abîmée, colonne de mauvaise longueur, annulation… |
     *
     * ⚠️ **La reprise porte sur `Throwable`, pas sur `VaultException`.** Une première version ne
     * rattrapait que les erreurs du Keystore. Un `vault_iv` de mauvaise longueur lève une
     * `IllegalArgumentException`, une enveloppe trop courte une `MalformedVaultDataException`, un
     * fournisseur cryptographique récalcitrant une `ProviderException` : aucune de ces trois ne
     * passait, et chacune faisait monter le compteur jusqu'à l'effacement. Relevé indépendamment
     * par les deux relectures externes du 2026-08-14.
     *
     * ⚠️ **`NonCancellable` n'est pas une commodité.** L'annulation est le cas le plus probable de
     * la dernière ligne : la dérivation Argon2id dure de l'ordre de la seconde, et il suffit que
     * l'utilisateur revienne en arrière pendant ce temps. Sans cette protection, la reprise serait
     * elle-même annulée — et cinq hésitations coûteraient un coffre.
     *
     * ## 🔴 L'incrément est DANS cette fonction, et il a fallu un test pour s'en apercevoir
     *
     * Il vivait d'abord chez l'appelant, deux lignes au-dessus du `try`. Entre les deux se glissait
     * une relecture du compteur — donc un point de suspension, donc une fenêtre où une annulation
     * emportait tout **sans passer par la reprise**. La fenêtre fait quelques microsecondes et le
     * test `une_tentative_annulee_ne_consomme_pas_de_tentative` tombait dedans à tous les coups,
     * parce qu'il attend précisément que le compteur bouge pour annuler.
     *
     * La leçon vaut au-delà d'ici : **un geste et sa reprise doivent être dans la même portée**.
     * Séparés, il existe toujours un instant où l'un a eu lieu et l'autre est devenu inatteignable.
     *
     * L'incrément lui-même passe donc aussi par [NonCancellable] : sans quoi il resterait un doute
     * sur son exécution, et donc sur la légitimité de le reprendre.
     */
    private suspend fun countingOneAttempt(
        folderDao: FolderDao,
        folderId: String,
        fallbackAttempts: Int,
        block: suspend (attemptsAfter: Int) -> Unit,
    ) {
        var incremented = false
        try {
            withContext(NonCancellable) {
                folderDao.incrementVaultAttempts(folderId)
                incremented = true
            }
            // Relu plutôt que déduit : l'incrément se fait en base, et deux tentatives concurrentes
            // ne doivent pas croire chacune être la première.
            block(folderDao.vaultAttempts(folderId) ?: fallbackAttempts)
        } catch (e: WrongPinException) {
            // Le seul échec que l'on compte : le code était faux.
            throw e
        } catch (e: VaultPinWipedException) {
            // Le coffre n'existe plus ; reprendre son compteur n'aurait pas d'objet.
            throw e
        } catch (e: Throwable) {
            if (incremented) {
                withContext(NonCancellable) { folderDao.decrementVaultAttempts(folderId) }
            }
            throw e
        }
    }

    private suspend fun attemptWithPin(
        folderDao: FolderDao,
        folderId: String,
        pin: String,
        material: VaultMaterial,
        attemptsAfter: Int,
    ) {
        val salt = requireColumn(material.salt, "vault_salt")
        val nonce = requireColumn(material.iv, "vault_iv")
        val verifier = requireColumn(material.verifier, "vault_verifier")
        val pinBlob = requireColumn(material.pinBlob, "vault_pin_blob")
        val pinIv = requireColumn(material.pinIv, "vault_pin_iv")

        sessions.whileUnlocking(folderId) {
            val inner = try {
                keystore.open(VaultParams.pinKeystoreAlias(folderId), SealedByKeystore(pinBlob, pinIv))
            } catch (e: KeystorePermanentlyInvalidatedException) {
                // Le système a détruit la clé : le coffre est légitimement irrécupérable. La cause
                // est portée jusqu'à l'interface — « votre écran de verrouillage a changé » et
                // « cinq codes faux » sont deux histoires très différentes pour l'utilisateur.
                autoWipePinVault(folderId)
                throw VaultPinWipedException(folderId, e)
            }

            val pinKey = VaultCrypto.derivePinKey(pin.toByteArray(Charsets.UTF_8), salt)
            val folderKey = try {
                VaultCrypto.open(pinKey, nonce, inner, folderId.toByteArray(Charsets.UTF_8))
            } catch (_: WrongSecretException) {
                // Rien à conserver : à cette couche, une étiquette qui ne valide pas veut dire
                // « code faux », et c'est déjà ce que porte l'exception levée par `pinFailure`.
                throw pinFailure(folderId, attemptsAfter)
            } finally {
                pinKey.wipe()
                inner.wipe()
            }

            // ⚠️ Le compteur est remis à zéro **avant** que la session s'ouvre, et non après.
            //
            // Dans l'autre ordre, une écriture qui échoue — base occupée, disque plein — laissait
            // une session ouverte et un compteur à cinq. Le déverrouillage suivant effaçait alors
            // le coffre, alors que le précédent avait RÉUSSI. Un succès ne doit pas pouvoir
            // préparer une destruction. Relevé par une relecture externe (GPT-5.2, 2026-08-14).
            openVerifiedSession(folderId, folderKey, verifier) { folderDao.resetVaultAttempts(folderId) }
        }
    }

    /**
     * Le seul endroit du service où une clé déballée devient une session.
     *
     * ## 🔴 Un vérificateur qui ne concorde pas n'est PAS un mauvais secret
     *
     * AES-GCM est un chiffrement **authentifié**. Que [VaultCrypto.open] ait rendu une clé prouve
     * déjà que le secret saisi était le bon — sans quoi l'étiquette n'aurait pas validé. Si le
     * vérificateur échoue **après** ça, la seule explication est que `vault_verifier` ne correspond
     * pas à la clé du coffre : colonnes recombinées, restauration partielle, base abîmée. Jamais
     * quelqu'un qui s'est trompé de code.
     *
     * ⚠️ **Écart délibéré avec l'application publiée**, qui compte ici un échec. Cinq lectures d'une
     * base abîmée y détruiraient un coffre dont le code était bon à chaque fois. Son propre
     * commentaire reconnaît que la branche est « en pratique jamais atteinte si le tag GCM a
     * passé » — ce qui est exactement l'argument pour ne pas la rendre destructrice.
     *
     * ⚠️ Une première version de ce portage faisait pire : elle exécutait un rappel puis un
     * `error(...)` inconditionnel. En mode phrase secrète, le rappel ne levait pas — le
     * déverrouillage se terminait donc par un plantage. Relevé par les deux relectures externes du
     * 2026-08-14, l'une sur le mode à code, l'autre sur les deux.
     *
     * @param beforeOpening ce qu'il faut réussir **avant** d'ouvrir la session — la remise à zéro du
     *   compteur, en mode à code. Si cela échoue, aucune session ne s'ouvre et la clé est effacée :
     *   un demi-succès ne doit rien laisser derrière lui.
     */
    private suspend fun openVerifiedSession(
        folderId: String,
        folderKey: ByteArray,
        expectedVerifier: ByteArray,
        beforeOpening: suspend () -> Unit = {},
    ) {
        if (!VaultCrypto.matchesVerifier(folderKey, expectedVerifier)) {
            folderKey.wipe()
            throw MalformedVaultDataException("verificateur du coffre $folderId incoherent avec sa cle")
        }
        try {
            beforeOpening()
        } catch (e: Throwable) {
            folderKey.wipe()
            throw e
        }
        sessions.clearFailures(folderId)
        // La propriété du tableau passe à la session : ne plus l'effacer ici.
        sessions.open(folderId, folderKey)

        // 🔴 **Le seul point du code où les réparations sont déclenchées**, et c'est délibéré.
        //
        // Cette méthode est appelée par les deux chemins de déverrouillage, et par eux seuls — la
        // création de coffre, elle, ouvre sa session directement. La distinction n'est pas
        // cosmétique : à la création, les notes déjà présentes doivent être chiffrées par un geste
        // que l'utilisateur voit, avec son décompte et ses échecs. Les faire chiffrer ici, en
        // silence et en meilleur effort, ferait annoncer « 0 note chiffrée » à l'écran suivant et
        // avalerait les échecs.
        //
        // Accrocher les réparations aux deux appelants plutôt qu'ici marcherait aujourd'hui et
        // produirait le jumeau asymétrique au premier chemin ajouté. Cf. `docs/04-PIEGES.md` §25.
        onSessionOpened(folderId)
    }

    private suspend fun pinFailure(folderId: String, attemptsAfter: Int): Nothing {
        val remaining = VaultParams.PIN_MAX_ATTEMPTS - attemptsAfter
        if (remaining <= 0) {
            autoWipePinVault(folderId)
            throw VaultPinWipedException(folderId)
        }
        sessions.recordFailure(folderId)
        throw WrongPinException(attemptsRemaining = remaining)
    }

    // ── Scellement et ouverture des notes ────────────────────────────────────────────────────────

    /**
     * Chiffre une note qui part dans un coffre. Contrat de [VaultSealer], appelé par
     * `NotesRepository` au moment de l'écriture.
     *
     * Produit toujours le **format 2** : le titre rejoint le contenu dans le chiffré et la colonne
     * `title` est vidée. Le format 1 n'est plus produit par personne ; il n'est que lu, pour les
     * notes scellées avant la 2.0.0.
     */
    override suspend fun seal(note: Note): Note {
        // La clé rendue est une COPIE, à effacer ici — voir `VaultSessions.sessionKey`. Sans cette
        // copie, un verrouillage concurrent remplirait de zéros le tableau utilisé par le
        // chiffrement, et la note partirait en base scellée sous une clé nulle.
        val folderKey = sessions.sessionKey(note.folderId)
            ?: throw SealRefusedException(noteId = note.id, folderId = note.folderId)
        val nonce = VaultCrypto.newNonce()
        val blob = try {
            NoteEnvelope.composeBlob(
                nonce = nonce,
                sealed = VaultCrypto.seal(
                    key = folderKey,
                    nonce = nonce,
                    plaintext = NoteEnvelope.packTitleAndContent(note.title, note.content),
                    aad = note.id.toByteArray(Charsets.UTF_8),
                ),
            )
        } finally {
            folderKey.wipe()
        }
        return note.copy(
            title = "",
            content = "",
            encrypted = EncryptedBody(blob),
            encVersion = EncryptedFormat.TITLE_AND_CONTENT,
        )
    }

    /**
     * Rend une note lisible, **sans la persister**. Contrat de [VaultOpener], appelé par
     * `NotesRepository.relocateLockedNote` — et directement par l'éditeur, qui affiche.
     *
     * L'objet rendu est éphémère : il sert à afficher, et l'écriture repasse par le scellement. Le
     * persister tel quel remettrait le clair en base, ce que la garde d'écriture refuse — mais il
     * vaut mieux ne pas compter dessus.
     */
    override suspend fun decrypt(note: Note): Note {
        val encrypted = note.encrypted ?: return note
        val folderKey = sessions.sessionKey(note.folderId) ?: throw VaultSessionClosedException(note.folderId)
        val blob = encrypted.toByteArray()
        val clear = try {
            VaultCrypto.open(
                key = folderKey,
                nonce = NoteEnvelope.nonceOf(blob),
                sealed = NoteEnvelope.sealedOf(blob),
                aad = note.id.toByteArray(Charsets.UTF_8),
            )
        } finally {
            folderKey.wipe()
        }
        return if (note.encVersion == EncryptedFormat.TITLE_AND_CONTENT) {
            val split = NoteEnvelope.unpackTitleAndContent(clear)
            note.copy(title = split.title, content = split.content, encrypted = null)
        } else {
            // Format 1 : le chiffré ne porte que le contenu, le titre est resté dans sa colonne.
            note.copy(content = String(clear, Charsets.UTF_8), encrypted = null)
        }
    }

    // ── Les gestes de masse : convertir, déprotéger, réparer ─────────────────────────────────────
    //
    // 🔴 Ces quatre méthodes étaient hors périmètre de la phase 4, et le fait qu'elles n'étaient
    // pas encore écrites était consigné dans `docs/11-COFFRES.md` §7 — pas pour mémoire, mais parce
    // que ce sont des **gestes de sécurité** et qu'un geste de sécurité qui n'appartient à aucune
    // phase finit dans les oublis d'une bascule. Elles entrent ici avec l'interface qui les appelle.

    /**
     * Le bilan d'un traitement par lot. `failed > 0` veut dire **dossier dans un état mixte**.
     *
     * ⚠️ L'appelant DOIT le montrer. Sans ça, l'utilisateur croit son dossier entièrement protégé
     * alors qu'une partie de ses notes est restée en clair — et c'est le genre de croyance sur
     * laquelle on fonde une décision de confidentialité.
     */
    data class BatchOutcome(val done: Int, val failed: Int) {
        val isComplete: Boolean get() = failed == 0
    }

    /**
     * Chiffre les notes déjà présentes quand un dossier devient un coffre.
     *
     * Aucune transaction globale, et ce n'est pas un renoncement : elle engloberait la réindexation
     * des liens, qui n'appartient pas à ce service. À la place, le bilan est honnête et l'appelant
     * décide — un échec partiel laisse un dossier utilisable, une transaction avortée laisserait un
     * dossier converti sans qu'aucune note le soit.
     *
     * ⚠️ **Archives comprises.** Les exclure laisserait en clair, dans un dossier annoncé protégé,
     * exactement les notes qu'on ne regarde plus — donc celles dont on ne remarquerait jamais
     * qu'elles n'ont pas été chiffrées.
     */
    suspend fun encryptAllNotesInFolder(folderId: String): BatchOutcome {
        requireOpenSession(folderId)
        val database = databases.get()
        return surChaqueNote(database.noteDao().findPlaintextInFolder(folderId)) { note ->
            val scellee = runOrNull { seal(note) } ?: return@surChaqueNote false
            val blob = scellee.encrypted?.toByteArray() ?: return@surChaqueNote false
            !echoue {
                database.noteWriteDao().lockNote(
                    id = note.id,
                    encryptedContent = blob,
                    encVersion = scellee.encVersion,
                    plainTitle = "",
                    tags = null,
                    // ⚠️ `null` : convertir un dossier en coffre ne modifie pas les notes du point
                    // de vue de l'utilisateur. Écrire l'instant courant ferait remonter tout le
                    // dossier en tête de la liste « modifiées récemment », d'un coup.
                    updatedAt = null,
                )
            }
        }
    }

    /**
     * Déchiffre toutes les notes verrouillées d'un dossier. Exige la session ouverte.
     *
     * 🔴 **C'est le seul endroit du code qui remet volontairement du clair au repos dans un
     * coffre.** Il existe parce que l'alternative est pire : supprimer le coffre en laissant ses
     * notes chiffrées les rendrait illisibles pour toujours, la clé partant avec le dossier.
     */
    suspend fun decryptAllNotesInFolder(folderId: String): BatchOutcome {
        requireOpenSession(folderId)
        val database = databases.get()
        return surChaqueNote(database.noteDao().findLockedInFolder(folderId)) { note ->
            val claire = runOrNull { decrypt(note) } ?: return@surChaqueNote false
            !echoue {
                database.noteWriteDao().unlockNote(
                    id = note.id,
                    content = claire.content,
                    plainTitle = claire.title,
                )
            }
        }
    }

    /**
     * Retire la protection d'un dossier : déchiffre tout, puis efface son matériel de coffre.
     *
     * ## 🔴 L'ordre, et le refus d'aller plus loin en cas d'échec partiel
     *
     * Si une seule note résiste au déchiffrement, **rien n'est effacé** : le dossier reste un
     * coffre, ses notes restent lisibles, l'utilisateur peut réessayer. Effacer le matériel malgré
     * un échec transformerait un incident réparable en perte définitive — les notes restées
     * chiffrées n'auraient plus de clé.
     *
     * La clé Keystore d'un coffre à code est supprimée **après** l'effacement en base, en meilleur
     * effort : sans son scellé côté base, elle ne protège plus rien, et la laisser ne ferait qu'un
     * orphelin dans le TEE.
     */
    suspend fun removeVaultProtection(folderId: String): BatchOutcome {
        requireOpenSession(folderId)
        val folderDao = databases.get().folderDao()
        val material = folderDao.vaultMaterial(folderId)
            ?: throw VaultValidationException(VaultValidationException.Reason.NOT_A_VAULT)

        // 🔴 **TOUTE sortie qui n'efface pas le coffre doit RESCELLER ce qui vient d'être
        // déchiffré**, et il y en a trois : l'échec rapporté, l'exception, l'annulation.
        //
        // `decryptAllNotesInFolder` écrit en clair note par note. S'arrêter en chemin laisse la
        // première moitié du coffre lisible au repos, dans un dossier qui arbore toujours son
        // cadenas — l'utilisateur croit l'opération annulée proprement.
        //
        // La session est encore ouverte à cet instant : c'est le seul moment où la réparation est
        // possible sans redemander le secret. Attendre le prochain déverrouillage laisserait du
        // clair au repos pour une durée que personne ne contrôle.
        //
        // ⚠️ **La réparation est `NonCancellable`.** Rattraper une annulation avec du code
        // annulable ne rattrape rien : la première suspension de la réparation relèverait
        // aussitôt, et le clair resterait. Leçon de la phase 4, `docs/04-PIEGES.md`.
        //
        // Le défaut a existé dans l'application publiée, relevé par deux relectures externes
        // successives — sur l'échec rapporté d'abord, sur l'exception ensuite. Le troisième
        // chemin, l'annulation, n'y était pas couvert du tout.
        // Le drapeau bascule **exactement** au moment où le dossier cesse d'être un coffre. Tant
        // qu'il est faux, du clair peut traîner et la réparation doit passer ; une fois vrai, il
        // n'y a plus rien à resceller — et plus de clé pour le faire.
        var protectionRetiree = false
        try {
            val bilan = decryptAllNotesInFolder(folderId)
            if (!bilan.isComplete) return bilan

            folderDao.clearVault(folderId, clock.millis())
            protectionRetiree = true

            if (material.effectiveMode == VaultMode.PIN) {
                // La clé Keystore n'a plus d'objet : sans son scellé en base, elle ne protège
                // rien. Après l'effacement en base, et en meilleur effort — un échec ici laisse un
                // orphelin dans le TEE, pas une perte de données.
                echoue { keystore.deleteKey(VaultParams.pinKeystoreAlias(folderId)) }
            }
            sessions.lock(folderId)
            sessions.clearFailures(folderId)
            return bilan
        } finally {
            if (!protectionRetiree) {
                withContext(NonCancellable) { echoue { reprotectPlaintextNotes(folderId) } }
            }
        }
    }

    /**
     * Les réparations qui n'ont lieu **qu'une session ouverte**, seul moment où la clé existe.
     *
     * ⚠️ **Point d'entrée unique, appelé par les DEUX chemins de déverrouillage.** C'est
     * précisément le genre d'endroit où naît un jumeau divergent : brancher un nouveau traitement
     * sur le seul chemin qu'on avait sous les yeux, et laisser l'autre en arrière. Tout geste
     * ajouté ici l'est pour le coffre à code comme pour celui à phrase secrète.
     *
     * L'ordre compte : on reprotège d'abord ce qui est en clair — ces notes ressortent directement
     * au format 2 — puis on migre ce qui était déjà chiffré au format 1.
     *
     * **Jamais bloquant.** Un déverrouillage légitime ne doit pas échouer parce qu'une réparation
     * d'arrière-plan a raté ; elle sera retentée à la prochaine ouverture.
     */
    suspend fun onSessionOpened(folderId: String) {
        echoue { reprotectPlaintextNotes(folderId) }
        echoue { migrateLegacyEncryptedNotes(folderId) }
    }

    /**
     * Rechiffre les notes d'un coffre restées en clair dans la colonne `content`.
     *
     * La requête est **étroite** : dans le cas normal elle ne ramène rien, et l'ouverture d'un
     * coffre ne paie qu'un `SELECT`. Charger tout le dossier ferait payer à chaque déverrouillage
     * la lecture de toutes les notes.
     *
     * ⚠️ **État mixte — un blob ET du clair : le blob fait foi.** On efface la colonne claire au
     * lieu de la rechiffrer. Rechiffrer le clair écraserait un blob potentiellement plus récent,
     * c'est-à-dire ferait perdre la dernière modification pour réparer une incohérence.
     *
     * ⚠️ **`updatedAt = null`.** Une réparation silencieuse ne doit pas faire remonter les notes en
     * tête de la liste « modifiées récemment » à chaque ouverture du coffre.
     */
    suspend fun reprotectPlaintextNotes(folderId: String): Int {
        val database = databases.get()
        return surChaqueNote(database.noteDao().findPlaintextInFolder(folderId)) { note ->
            val scellee = if (note.encrypted != null) note else runOrNull { seal(note) }
            val blob = scellee?.encrypted?.toByteArray() ?: return@surChaqueNote false
            !echoue {
                database.noteWriteDao().lockNote(
                    id = note.id,
                    encryptedContent = blob,
                    encVersion = scellee.encVersion,
                    plainTitle = if (scellee.encVersion == EncryptedFormat.TITLE_AND_CONTENT) "" else scellee.title,
                    tags = null,
                    updatedAt = null,
                )
            }
        }.done
    }

    /**
     * Fait passer les notes chiffrées du format 1 au format 2 : le titre quitte sa colonne pour
     * rejoindre le chiffré.
     *
     * Ne peut se faire qu'ici. Déplacer le titre exige de déchiffrer puis de rechiffrer, donc la
     * clé — dont une migration de schéma ne dispose pas. Une note dont le coffre n'est jamais
     * rouvert garde donc son titre en clair : c'est le prix d'une migration qui ne peut pas perdre
     * de données, et il est payé en connaissance de cause.
     *
     * ⚠️ Le déchiffrement est fait **avant** toute écriture, et l'écriture est un `UPDATE` unique
     * qui pose ensemble le nouveau blob, le titre vidé et la nouvelle version. Une interruption
     * laisse la note intacte au format 1, jamais dans un état hybride où le titre serait perdu des
     * deux côtés.
     */
    suspend fun migrateLegacyEncryptedNotes(folderId: String): Int {
        val database = databases.get()
        return surChaqueNote(database.noteDao().findLegacyEncryptedInFolder(folderId)) { note ->
            val blob = runOrNull { seal(decrypt(note)) }?.encrypted?.toByteArray() ?: return@surChaqueNote false
            !echoue {
                database.noteWriteDao().lockNote(
                    id = note.id,
                    encryptedContent = blob,
                    encVersion = EncryptedFormat.TITLE_AND_CONTENT,
                    plainTitle = "",
                    tags = null,
                    updatedAt = null,
                )
            }
        }.done
    }

    /**
     * Supprime la clé Keystore d'un coffre à code, en meilleur effort.
     *
     * ⚠️ **À appeler APRÈS la suppression du dossier en base, jamais avant.** L'ordre inverse a
     * existé dans l'application publiée et a été relevé en critique : si la suppression en base
     * échouait ensuite — base verrouillée, stockage plein —, le dossier et ses notes chiffrées
     * restaient, mais la clé qui permettait de les ouvrir avait disparu. Le coffre devenait
     * définitivement inaccessible, sur une opération qui n'était même pas censée échouer.
     *
     * Dans l'ordre correct, un échec de la suppression en base laisse le coffre intact et ouvrable,
     * et un échec ici laisse un alias orphelin dans le TEE — inexploitable sans son scellé.
     */
    suspend fun deletePinKey(folderId: String) {
        echoue { keystore.deleteKey(VaultParams.pinKeystoreAlias(folderId)) }
    }

    /**
     * Applique [action] à chaque note et compte les succès et les échecs.
     *
     * Extrait pour une raison qui n'est pas cosmétique : les quatre traitements par lot avaient
     * chacun leur boucle, leurs deux compteurs et leurs `continue`. Quatre copies d'une même
     * mécanique, c'est quatre endroits où corriger le jour où la règle change — et trois qu'on
     * oublie. Le comptage vit ici, une fois ; chaque traitement ne dit plus que ce qu'il fait d'une
     * note, et s'il a réussi.
     *
     * ⚠️ **[action] rend `false` sur échec, elle ne lève pas.** Un lot est un meilleur effort par
     * construction : une note qui résiste ne doit pas empêcher les suivantes. L'annulation, elle,
     * traverse — elle passe par [runOrNull] et [echoue], qui la laissent remonter.
     */
    private suspend fun surChaqueNote(entities: List<NoteEntity>, action: suspend (Note) -> Boolean): BatchOutcome {
        var done = 0
        var failed = 0
        for (entity in entities) {
            if (action(entity.toDomain())) done++ else failed++
        }
        return BatchOutcome(done = done, failed = failed)
    }

    /** Exécute [block], ou rend `null` s'il échoue. **L'annulation remonte** — cf. [echoue]. */
    private suspend inline fun <T> runOrNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun requireOpenSession(folderId: String) {
        if (!sessions.isUnlocked(folderId)) throw VaultSessionClosedException(folderId)
    }

    // ── Auto-effacement ──────────────────────────────────────────────────────────────────────────

    /**
     * Détruit un coffre à code dont les cinq tentatives sont épuisées.
     *
     * ```
     *   drapeau posé → clé Keystore supprimée → notes verrouillées supprimées → dossier démoté
     *                → drapeau retiré
     * ```
     *
     * Le dossier survit, vide : l'utilisateur voit que les données ont disparu, ce qui est le
     * contrat annoncé — cinq échecs, perte définitive, à l'image d'un écran de verrouillage Android.
     *
     * ⚠️ **Les notes sont supprimées, pas mises à la corbeille.** La corbeille les garderait trente
     * jours sans que rien ne puisse les déchiffrer : des déchets chiffrés, occupant de la place et
     * donnant l'illusion d'une récupération possible.
     *
     * ⚠️ **Réentrance interdite.** Un appui répété sur la feuille de saisie pouvait relancer
     * l'effacement pendant qu'il courait, doubler les opérations Keystore et abîmer le drapeau.
     */
    suspend fun autoWipePinVault(folderId: String) {
        if (!wiping.add(folderId)) return
        try {
            wipeJournal.markPending(folderId)
            echoue { keystore.deleteKey(VaultParams.pinKeystoreAlias(folderId)) }

            val database = databases.get()
            // ⚠️ Les échecs de suppression sont COMPTÉS, pas ignorés.
            //
            // Ils étaient absorbés en silence, puis le dossier était démoté et le drapeau retiré.
            // Une base momentanément occupée suffisait alors à laisser derrière soi des notes
            // chiffrées dans un dossier qui n'est plus un coffre — donc sans le matériel qui
            // permettait de les lire, et sans rien pour déclencher une reprise. Relevé par une
            // relecture externe (GPT-5.2, 2026-08-14).
            val restantes = database.noteDao().findLockedInFolder(folderId).count { note ->
                echoue { database.noteWriteDao().deletePermanently(note.id) }
            }
            database.folderDao().clearVault(folderId, clock.millis())
            sessions.lock(folderId)
            sessions.clearFailures(folderId)

            // Le drapeau ne tombe que si l'effacement est allé au bout. Sinon il reste, et la
            // reprise du prochain démarrage finira le travail — c'est précisément ce pour quoi il
            // existe.
            if (restantes == 0) wipeJournal.clearPending(folderId)
        } finally {
            wiping.remove(folderId)
        }
    }

    /**
     * Reprend les effacements interrompus. À appeler une fois au démarrage.
     *
     * ⚠️ **Le drapeau est CONSERVÉ quand la reprise échoue**, et c'est le geste inverse de
     * l'intuition. L'application publiée le retirait « pour ne pas boucler indéfiniment » : une
     * exception passagère au démarrage — base verrouillée, Keystore pas encore prêt — suffisait
     * alors à **annuler définitivement** un effacement déclenché par cinq codes faux. Il suffisait
     * de provoquer un plantage au lancement pour sauver le coffre qu'on venait de faire condamner.
     *
     * Le coût de le garder est une tentative par démarrage. Le coût de le retirer est la garantie.
     * Relevé indépendamment par deux relectures externes sur le code Flutter.
     *
     * Le seul retrait légitime est celui d'un dossier qui n'existe plus : là, il n'y a effectivement
     * plus rien à effacer.
     */
    suspend fun resumePendingWipes() {
        val folderDao = databases.get().folderDao()
        for (folderId in wipeJournal.pendingFolderIds()) {
            echoue {
                if (folderDao.vaultMaterial(folderId) == null) {
                    wipeJournal.clearPending(folderId)
                } else {
                    autoWipePinVault(folderId)
                }
            }
        }
    }

    /**
     * Exécute [block] et rend `true` s'il a échoué. **L'annulation, elle, remonte.**
     *
     * ## ⚠️ Pourquoi ce n'est pas un `runCatching`
     *
     * `runCatching` attrape `Throwable`, donc `CancellationException`. Sur un chemin annulable, il
     * transforme « cette coroutine doit s'arrêter » en « cette opération a raté », et la boucle
     * continue de tourner dans une coroutine qui n'existe plus. `docs/04-PIEGES.md` §8 l'interdit
     * explicitement, et ces trois sites l'enfreignaient — sur le chemin le plus destructeur du
     * fichier, l'effacement d'un coffre. Relevé par l'audit de cohérence du 2026-08-14.
     *
     * Le « meilleur effort » reste entier pour ce qu'il vise : une note qui refuse de se supprimer
     * n'empêche pas les autres, et le drapeau de reprise garde la trace de l'inachèvement.
     */
    private suspend inline fun echoue(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        true
    }

    // ── Internes ─────────────────────────────────────────────────────────────────────────────────

    private val wiping = Collections.synchronizedSet(mutableSetOf<String>())

    private suspend fun requireMaterial(folderId: String): VaultMaterial =
        databases.get().folderDao().vaultMaterial(folderId)
            ?: throw VaultValidationException(VaultValidationException.Reason.FOLDER_NOT_FOUND)

    private suspend fun requireVaultMaterial(folderId: String): VaultMaterial {
        val material = requireMaterial(folderId)
        if (!material.isVault) throw VaultValidationException(VaultValidationException.Reason.NOT_A_VAULT)
        return material
    }

    /**
     * Une colonne de coffre obligatoire, ou un refus.
     *
     * Ce refus n'est **pas** une tentative ratée : une colonne manquante décrit une base abîmée, et
     * la compter ferait s'auto-détruire un coffre à code pour une corruption de fichier. Le nom de
     * la colonne est dans le message ; sa valeur, jamais.
     */
    private fun <T : Any> requireColumn(value: T?, name: String): T =
        value ?: throw MalformedVaultDataException("colonne $name absente")

    private fun requireNoLockout(folderId: String) {
        val remaining = sessions.lockoutRemainingMillis(folderId)
        if (remaining > 0) throw VaultLockoutInProgressException(remaining)
    }

    private fun requireValidPassphrase(passphrase: String) {
        if (passphrase.length < VaultParams.PASSPHRASE_MIN_LENGTH) {
            throw VaultValidationException(VaultValidationException.Reason.PASSPHRASE_TOO_SHORT)
        }
    }

    /**
     * Un code de quatre à six **chiffres**.
     *
     * Des lettres seraient acceptables cryptographiquement ; le refus est un choix d'interface,
     * hérité, et il doit être appliqué à la création comme au déverrouillage. Ne le poser qu'à la
     * création laisserait une divergence de comportement entre les deux.
     */
    private fun requireValidPin(pin: String) {
        if (pin.length !in VaultParams.PIN_MIN_LENGTH..VaultParams.PIN_MAX_LENGTH) {
            throw VaultValidationException(VaultValidationException.Reason.PIN_LENGTH_OUT_OF_RANGE)
        }
        // Intervalle explicite, et non `Char.isDigit()` : celui-ci accepte les chiffres
        // arabo-indiens et une douzaine d'autres jeux, la ou le `\d` d'une RegExp Dart s'en tient a
        // `[0-9]`. Un code saisi en chiffres devanagari serait accepte a la creation et refuse au
        // deverrouillage, ou l'inverse selon le clavier.
        if (!pin.all { it in '0'..'9' }) {
            throw VaultValidationException(VaultValidationException.Reason.PIN_NOT_DIGITS_ONLY)
        }
    }
}
