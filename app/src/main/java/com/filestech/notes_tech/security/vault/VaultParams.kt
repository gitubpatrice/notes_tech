package com.filestech.notes_tech.security.vault

/**
 * Les constantes des coffres, et rien d'autre.
 *
 * ## 🔴 Une seule valeur fausse ici rend inouvrables les coffres déjà créés
 *
 * Ce fichier est un miroir de `docs/02-SCHEMA-HERITE.md` §4, lui-même relevé dans le code Flutter
 * publié en 2.0.3. Chaque valeur porte la ligne d'où elle vient. Une relecture doit pouvoir
 * confronter les deux sans rien deviner.
 *
 * Ces valeurs sont **figées par les données des utilisateurs**, pas par un choix qu'on pourrait
 * refaire. Les durcir — plus d'itérations, plus de mémoire — casserait la dérivation de tous les
 * coffres existants. Un tel changement demanderait une migration qui rechiffre, donc la passphrase,
 * donc l'utilisateur : ce n'est pas un réglage.
 */
internal object VaultParams {

    // ── Argon2id, mode passphrase ────────────────────────────────────────────────────────────────
    // `core/constants.dart:62-65`

    /** `vaultArgon2Iterations` — paramètre `t` de la RFC 9106. */
    const val PASSPHRASE_ITERATIONS = 3

    /** `vaultArgon2MemoryKb` — paramètre `m`, en **kibioctets**, soit 64 Mo. */
    const val PASSPHRASE_MEMORY_KIB = 64 * 1024

    // ── Argon2id, mode PIN ───────────────────────────────────────────────────────────────────────
    // `core/constants.dart:95-96`
    //
    // Allégé volontairement : la sécurité réelle du mode PIN vient du scellement Keystore lié à
    // l'appareil, pas de l'entropie de quatre à six chiffres. Argon2id n'y est qu'une seconde
    // couche, et imposer une seconde d'attente à chaque déverrouillage légitime n'achèterait rien.

    /** `vaultPinArgon2Iterations`. */
    const val PIN_ITERATIONS = 2

    /** `vaultPinArgon2MemoryKb` — 32 Mo. */
    const val PIN_MEMORY_KIB = 32 * 1024

    // ── Communs aux deux modes ───────────────────────────────────────────────────────────────────

    /** `vaultArgon2Parallelism` — `core/constants.dart:64`. */
    const val PARALLELISM = 1

    /** `vaultArgon2HashBytes` — `core/constants.dart:65`. Longueur de la clé dérivée. */
    const val DERIVED_KEY_BYTES = 32

    /** `vaultSaltBytes` — `core/constants.dart:68`. Persisté dans `folders.vault_salt`. */
    const val SALT_BYTES = 16

    /** Longueur de la clé de coffre tirée au sort à la création. AES-256. */
    const val FOLDER_KEY_BYTES = 32

    /** Nonce AES-GCM. 12 octets : la taille pour laquelle GCM est spécifié sans dérivation. */
    const val NONCE_BYTES = 12

    /** Étiquette d'authentification GCM, en **bits** — 16 octets une fois concaténée. */
    const val TAG_BITS = 128

    /** Idem, en octets. Utile partout où l'on découpe une enveloppe. */
    const val TAG_BYTES = TAG_BITS / 8

    // ── Vérificateur ─────────────────────────────────────────────────────────────────────────────

    /**
     * Message du HMAC qui atteste qu'une clé de coffre est la bonne.
     *
     * ⚠️ **Séparé par domaine, et immuable.** `folder_vault_service.dart:166` le dit déjà :
     * « si on change ce string, les vaults existants deviennent incompatibles → ne JAMAIS modifier
     * après livraison ». C'est livré depuis la v0.7.
     */
    const val VERIFIER_MESSAGE = "files-tech.notes_tech.vault.v1"

    // ── Politique de déverrouillage ──────────────────────────────────────────────────────────────

    /** `vaultPassphraseMinLength` — `core/constants.dart:51`. Validation d'entrée, pas une contrainte crypto. */
    const val PASSPHRASE_MIN_LENGTH = 8

    /** `vaultDefaultAutoLock` — `core/constants.dart:55`. Surchargeable par les réglages. */
    const val DEFAULT_AUTO_LOCK_MINUTES = 15

    /** `vaultPinMinLength` / `vaultPinMaxLength` — `core/constants.dart:76-81`. */
    const val PIN_MIN_LENGTH = 4

    /** Voir [PIN_MIN_LENGTH]. */
    const val PIN_MAX_LENGTH = 6

    /** `vaultPinMaxAttempts` — `core/constants.dart:88`. Au-delà, le coffre s'auto-détruit. */
    const val PIN_MAX_ATTEMPTS = 5

    /** `vaultPinKeystoreAliasPrefix` — `core/constants.dart:100`. Concaténé avec l'identifiant. */
    const val PIN_KEYSTORE_ALIAS_PREFIX = "vault_pin_"

    /** `prefKeyVaultWipePendingPrefix` — `core/constants.dart:106`. Cf. `02-SCHEMA-HERITE.md` §5. */
    const val WIPE_PENDING_PREF_PREFIX = "vault_wipe_pending_"

    /** L'alias Keystore d'un coffre PIN. Un par coffre — jamais partagé. */
    fun pinKeystoreAlias(folderId: String): String = "$PIN_KEYSTORE_ALIAS_PREFIX$folderId"
}
