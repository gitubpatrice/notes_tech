package com.filestech.notes_tech.data.local.mapper

import com.filestech.notes_tech.data.local.entity.FolderEntity
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.VaultDescriptor
import com.filestech.notes_tech.domain.model.VaultMode
import java.time.Instant

/**
 * Conversion de la ligne `folders` vers le domaine.
 *
 * ## ⚠️ Le critère de coffre est `vault_salt`, jamais `vault_mode`
 *
 * `vault_salt IS NOT NULL` est la source de vérité — c'est celui que retient l'application publiée
 * (`notes_tech/lib/data/models/folder.dart:100`).
 *
 * `vault_mode` est une colonne **rétro-remplie** : elle n'existait pas avant la 0.9, et la migration
 * l'a renseignée après coup pour les coffres déjà créés. S'en servir pour décider si une écriture
 * doit être chiffrée reviendrait à faire dépendre une garde de sécurité du bon déroulement d'une
 * migration passée. Le sel, lui, est là depuis le premier coffre.
 *
 * ⚠️ Aucun octet de coffre ne traverse cette fonction. Le sel, la clé enveloppée, le vérificateur
 * et les blobs de code restent dans la couche données ; le domaine n'en apprend que l'existence et
 * le mode. Un écran qui liste les carnets n'a pas à tenir de matériel cryptographique en mémoire —
 * ce qu'on ne charge pas ne peut pas fuiter par une trace ou un instantané de tas.
 */
internal fun FolderEntity.toDomain(): Folder = Folder(
    id = id,
    name = name,
    parentId = parentId,
    color = color,
    icon = icon,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    vault = if (vaultSalt != null) {
        VaultDescriptor(mode = VaultMode.from(vaultMode), failedAttempts = vaultAttempts)
    } else {
        null
    },
)

internal fun List<FolderEntity>.toDomain(): List<Folder> = map(FolderEntity::toDomain)
