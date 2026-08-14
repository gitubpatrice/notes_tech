package com.filestech.notes_tech.data.local.dao

import androidx.room.ColumnInfo
import com.filestech.notes_tech.domain.model.VaultMode

/**
 * Le matériel cryptographique d'un coffre, tel qu'il est en base.
 *
 * ## Pourquoi une projection, et pas le `FolderEntity` complet
 *
 * Ces colonnes ne servent qu'au déverrouillage. Les faire voyager dans un modèle de domaine, un
 * état d'écran ou un journal reviendrait à promener la clé enveloppée d'un coffre partout dans
 * l'application, pour un usage qui tient en quelques lignes.
 *
 * ## ⚠️ Aucun `equals` généré ici — et c'est volontaire
 *
 * Ce n'est **pas** une `data class`. L'`equals` qu'elle produirait comparerait les `ByteArray` par
 * référence, donc dirait « différents » pour deux lectures de la même ligne. Un tel prédicat n'a
 * aucun usage correct, et son existence invite à s'en servir.
 */
class VaultMaterial(
    @ColumnInfo(name = "id") val folderId: String,
    /** `null` = ce dossier n'est pas un coffre. **C'est le critère**, pas `vault_mode`. */
    @ColumnInfo(name = "vault_salt", typeAffinity = ColumnInfo.BLOB) val salt: ByteArray?,
    /** Clé du coffre scellée par la clé dérivée de la phrase secrète. `null` en mode PIN. */
    @ColumnInfo(name = "vault_kek_wrapped", typeAffinity = ColumnInfo.BLOB) val kekWrapped: ByteArray?,
    /** Nonce du scellement ci-dessus — et du scellement interne, en mode PIN. */
    @ColumnInfo(name = "vault_iv", typeAffinity = ColumnInfo.BLOB) val iv: ByteArray?,
    /** HMAC qui atteste qu'une clé déballée est la bonne. */
    @ColumnInfo(name = "vault_verifier", typeAffinity = ColumnInfo.BLOB) val verifier: ByteArray?,
    @ColumnInfo(name = "vault_mode") val mode: String?,
    /** Scellement Keystore du scellement interne. Mode PIN seulement. */
    @ColumnInfo(name = "vault_pin_blob", typeAffinity = ColumnInfo.BLOB) val pinBlob: ByteArray?,
    /** Nonce produit par le Keystore lors du scellement ci-dessus. */
    @ColumnInfo(name = "vault_pin_iv", typeAffinity = ColumnInfo.BLOB) val pinIv: ByteArray?,
    @ColumnInfo(name = "vault_attempts") val failedAttempts: Int,
) {
    /** Le critère hérité : un dossier est un coffre s'il a un sel. Cf. `VaultMode`. */
    val isVault: Boolean get() = salt != null

    /**
     * Comment ce coffre s'ouvre, **déduit de ce qu'il porte** et non de son étiquette.
     *
     * ## ⚠️ `vault_mode` décrit, les colonnes prouvent
     *
     * C'est le même raisonnement que pour `vault_salt`, et il vaut ici pour la même raison. La
     * colonne `vault_mode` n'existe que depuis la 0.9 et a été rétro-remplie par une migration ;
     * `vault_pin_blob`, lui, n'est écrit que par la création d'un coffre à code. Se fier à
     * l'étiquette, c'est faire dépendre l'ouverture d'un coffre du bon déroulement passé d'une
     * migration — alors qu'un `vault_mode` perdu ou abîmé rendrait le coffre **inouvrable par les
     * deux chemins à la fois** : refusé côté code parce que l'étiquette ne dit pas « pin », refusé
     * côté phrase secrète parce que `vault_kek_wrapped` est vide.
     *
     * Relevé par une relecture externe (GPT-5.2, 2026-08-14). L'écart avec l'application publiée est
     * assumé : il ne peut qu'ouvrir des coffres qui seraient restés fermés.
     */
    val effectiveMode: VaultMode
        get() = if (pinBlob != null && pinIv != null) VaultMode.PIN else VaultMode.PASSPHRASE
}
