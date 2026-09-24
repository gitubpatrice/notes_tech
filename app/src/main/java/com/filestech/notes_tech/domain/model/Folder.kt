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

    /**
     * `true` while the inbox still carries a DEFAULT name — the one its database was seeded with,
     * not one the user typed. The interface then shows the name in the app's language.
     *
     * See [isDefaultInboxName], the one place the rule lives.
     */
    val hasDefaultInboxName: Boolean get() = isDefaultInboxName(id, name)

    companion object {
        /** Identifiant du dossier racine indélébile. Cf. `FolderEntity.INBOX_ID`. */
        const val INBOX_ID = "inbox"

        /**
         * The name a NEW database seeds the inbox with — `AppConstants.inboxDefaultName` of
         * notes_tech 2.0.9. Never shown as such: see [isDefaultInboxName].
         */
        const val INBOX_DEFAULT_NAME = "Inbox"

        /**
         * The names the inbox has carried by default: the English seed, and the French one that
         * every database created before 2.0.9 received, whatever the phone's language — which is
         * how an English-speaking reviewer met "Boîte de réception" (fdroiddata!37885).
         */
        private val INBOX_DEFAULT_NAMES = setOf(INBOX_DEFAULT_NAME, "Boîte de réception")

        /**
         * Whether [name] is a default name of the inbox, as opposed to a name the user chose.
         *
         * The rule of notes_tech 2.0.9 (`constants.dart:39-59`), kept identical so that both apps
         * show the same thing for the same row: the inbox id AND one of the default names. No
         * migration rewrites the row — a base seeded in French keeps "Boîte de réception" on disk,
         * and a folder the user renamed keeps its name.
         *
         * ⚠️ Known limit, accepted by the published app and kept: renaming the inbox to EXACTLY one
         * of these names makes it a default name again, shown in the app language. Telling the two
         * apart would need a "renamed" flag and a migration, for a case this rare.
         */
        fun isDefaultInboxName(folderId: String, name: String): Boolean =
            folderId == INBOX_ID && name in INBOX_DEFAULT_NAMES
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
 * NULL`), mais s'appuyer sur une colonne rétro-remplie plutôt que sur la source de vérité serait un
 * pari inutile.
 *
 * ## 🔴 Et le mode LUI-MÊME ne se lit pas dans `vault_mode`
 *
 * Le même raisonnement s'applique une marche plus loin, et il a fallu deux relectures pour s'en
 * apercevoir. `vault_mode` est une **étiquette** ; `vault_pin_blob` et `vault_kek_wrapped` sont ce
 * que le coffre **porte**. Un `vault_mode` perdu ou incohérent rendrait un coffre à code inouvrable
 * **par les deux chemins à la fois** : refusé côté code faute d'étiquette, refusé côté phrase
 * secrète faute de clé enveloppée.
 *
 * [fromMaterial] est donc le **seul** point où cette question se tranche. Elle l'a d'abord été à
 * deux endroits — une fois corrigé, une fois pas — ce qui est précisément le jumeau asymétrique que
 * `docs/04-PIEGES.md` décrit comme le motif le plus tenace de ce portage.
 */
enum class VaultMode {
    /** Argon2id sur une phrase secrète. Le mode d'origine, et celui de tous les coffres de 0.8. */
    PASSPHRASE,

    /** Code à 4-6 chiffres, Argon2id allégé, scellé par une clé liée à l'appareil. */
    PIN,

    /**
     * Coffre qui porte un sel mais **aucun matériel de clé exploitable** — ni scellé de code, ni
     * clé enveloppée. Base abîmée, restauration partielle, ou format d'une version plus récente.
     *
     * Le dossier s'affiche, verrouillé et non déverrouillable, plutôt que de disparaître avec tout
     * son contenu. Un `TypeConverter` Room qui échouerait ferait perdre la ligne entière.
     */
    UNKNOWN,
    ;

    companion object {
        /**
         * Le mode d'un coffre, déduit de ce qu'il porte.
         *
         * L'ordre des cas compte : un coffre à code n'a pas de `vault_kek_wrapped` — son emballage
         * vit dans `vault_pin_blob`, après un tour de plus par le Keystore.
         */
        fun fromMaterial(kekWrapped: ByteArray?, pinBlob: ByteArray?, pinIv: ByteArray?): VaultMode = when {
            pinBlob != null && pinIv != null -> PIN
            kekWrapped != null -> PASSPHRASE
            else -> UNKNOWN
        }
    }
}
