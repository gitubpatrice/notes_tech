package com.filestech.notes_tech.domain.model

import java.time.Instant

/**
 * Un dossier — un carnet, dans le vocabulaire de l'interface.
 *
 * ## Le coffre est un état, pas six colonnes nullables
 *
 * La table porte sept colonnes `vault_*`, dont six sont des blobs qui n'ont de sens qu'ensemble.
 * Les remonter telles quelles dans le domaine offrirait des états impossibles à représenter — un
 * dossier « pas coffre » avec une clé enveloppée, un « coffre PIN » sans sel — et obligerait chaque
 * lecteur à connaître l'invariant qui les lie.
 *
 * Ici, [vault] vaut `null` ou décrit un coffre. Le matériel cryptographique lui-même n'est pas dans
 * ce modèle : il ne sert qu'au service de coffres, qui le lit par une requête dédiée. Un écran qui
 * affiche une liste de carnets n'a aucune raison de tenir des clés enveloppées en mémoire.
 */
data class Folder(
    val id: String,
    val name: String,
    /** `null` = dossier racine. */
    val parentId: String?,
    /** Couleur ARGB empaquetée, ou `null` pour la couleur du thème. */
    val color: Int?,
    val icon: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** `null` = dossier ordinaire. */
    val vault: VaultDescriptor?,
) {
    val isVault: Boolean get() = vault != null

    /**
     * `true` pour la boîte de réception, que l'interface traite à part : elle ne se supprime pas, et
     * son libellé d'origine est écrit en dur dans la base par la version Flutter — donc à traduire
     * à l'affichage, jamais à réécrire.
     */
    val isInbox: Boolean get() = id == INBOX_ID

    companion object {
        /** Identifiant du dossier racine indélébile. Cf. `FolderEntity.INBOX_ID`. */
        const val INBOX_ID = "inbox"
    }
}

/**
 * Ce qu'on sait d'un coffre sans en détenir la clé.
 *
 * @param mode comment il se déverrouille.
 * @param failedAttempts tentatives ratées consécutives. À [VaultDescriptor.MAX_ATTEMPTS], le coffre
 *   s'auto-efface. Toujours `0` pour un coffre passphrase.
 */
data class VaultDescriptor(val mode: VaultMode, val failedAttempts: Int) {
    companion object {
        /** Valeur héritée : cinq échecs consécutifs effacent le coffre. */
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * Comment un coffre se déverrouille.
 *
 * ⚠️ **Le mode ne dit PAS si un dossier est un coffre.** C'est `vault_salt` qui le dit, et cette
 * distinction a de l'importance : les coffres créés en 0.8, avant l'existence des coffres à code,
 * n'avaient pas de colonne `vault_mode`. La migration de schéma la leur a bien renseignée
 * (`database.dart:761` — `UPDATE folders SET vault_mode = 'passphrase' WHERE vault_salt IS NOT
 * NULL`), mais s'appuyer sur une colonne rétro-remplie plutôt que sur la source de vérité, pour
 * décider si une écriture doit être chiffrée, serait un pari inutile.
 */
enum class VaultMode(val stored: String?) {
    /** Argon2id sur une phrase secrète. Le mode d'origine, et celui de tous les coffres de 0.8. */
    PASSPHRASE("passphrase"),

    /** Code à 4-6 chiffres, Argon2id allégé, scellé par une clé liée à l'appareil. */
    PIN("pin"),

    /**
     * Coffre dont la colonne porte une valeur que cette version ne connaît pas — base écrite par
     * une version plus récente, ou corrompue.
     *
     * Volontairement **pas** un `TypeConverter` Room, qui ferait échouer la lecture de la ligne
     * entière. Ici le dossier s'affiche, verrouillé et non déverrouillable, plutôt que de
     * disparaître avec tout son contenu.
     */
    UNKNOWN(null),
    ;

    companion object {
        fun from(stored: String?): VaultMode = entries.firstOrNull { it.stored == stored } ?: UNKNOWN
    }
}
