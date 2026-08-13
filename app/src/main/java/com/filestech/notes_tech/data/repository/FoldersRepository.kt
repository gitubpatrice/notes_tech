package com.filestech.notes_tech.data.repository

import androidx.room.withTransaction
import com.filestech.notes_tech.core.text.DartTextSemantics
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.NotesDatabase
import com.filestech.notes_tech.data.local.entity.FolderEntity
import com.filestech.notes_tech.data.local.mapper.toDomain
import com.filestech.notes_tech.domain.model.Folder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Les dossiers — les carnets, côté interface.
 *
 * ## 🔴 [isVaultFolder] est une garde de sécurité, pas une commodité
 *
 * C'est ce prédicat qui décide si le contenu d'une note a le droit de partir en clair sur le disque.
 * Il n'est **ni mémorisé, ni mis en cache**, et son repli est `true`.
 *
 * L'application publiée le mémorise, pour ne pas payer une requête par frappe. Deux relectures
 * externes successives y ont trouvé des courses — le cache répondait « pas un coffre » pour un
 * coffre qui venait d'être créé, ce qui ouvrait la garde au moment précis où elle devait fermer.
 * Le correctif final tient sur un compteur de génération, trois tours de boucle et une lecture
 * ciblée de repli.
 *
 * Ici l'appelant interroge ce prédicat **dans la transaction qui écrit** : il n'existe aucun
 * intervalle pendant lequel la réponse pourrait vieillir. Le cache n'est pas remplacé par un cache
 * plus habile, il est devenu sans objet.
 */
@Singleton
class FoldersRepository @Inject constructor(private val databases: DatabaseProvider, private val clock: Clock) {

    fun observeAll(): Flow<List<Folder>> = observing { it.folderDao().observeAll() }.map { it.toDomain() }

    fun observeChildren(parentId: String?): Flow<List<Folder>> =
        observing { it.folderDao().observeChildren(parentId) }.map { it.toDomain() }

    suspend fun find(id: String): Folder? = databases.get().folderDao().findById(id)?.toDomain()

    suspend fun listAll(): List<Folder> = databases.get().folderDao().listAll().toDomain()

    suspend fun listVaults(): List<Folder> = databases.get().folderDao().findVaults().toDomain()

    /**
     * Dit si [id] désigne un coffre.
     *
     * ⚠️ **Un dossier inconnu répond `true`**, et c'est le bon sens de l'erreur : au pire une
     * écriture légitime est refusée bruyamment, jamais un secret écrit en clair silencieusement.
     * Écrire une note dans un dossier qui n'existe pas n'est de toute façon pas une opération
     * valide — la clé étrangère la rejetterait juste après.
     */
    suspend fun isVaultFolder(id: String): Boolean = databases.get().folderDao().isVault(id) ?: true

    /**
     * Crée un dossier ordinaire. Un coffre ne se crée pas ici : il se provisionne, avec du matériel
     * cryptographique, par le service de coffres (phase 4).
     */
    suspend fun create(name: String, parentId: String? = null, color: Int? = null, icon: String? = null): Folder {
        val trimmed = requireUsableName(name)
        val now = clock.millis()
        val entity = FolderEntity(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            parentId = parentId,
            color = color,
            icon = icon,
            createdAt = now,
            updatedAt = now,
            vaultSalt = null,
            vaultKekWrapped = null,
            vaultIv = null,
            vaultVerifier = null,
            vaultMode = null,
            vaultPinBlob = null,
            vaultPinIv = null,
            vaultAttempts = 0,
        )
        databases.get().folderDao().insert(entity)
        return entity.toDomain()
    }

    suspend fun rename(id: String, name: String): Boolean =
        databases.get().folderDao().rename(id, requireUsableName(name), clock.millis()) > 0

    suspend fun updateAppearance(id: String, color: Int?, icon: String?): Boolean =
        databases.get().folderDao().updateAppearance(id, color, icon, clock.millis()) > 0

    /**
     * Déplace un dossier sous un autre parent.
     *
     * ⚠️ **Refuse les cycles.** Rien dans le schéma ne les empêche, et un dossier devenu son propre
     * ancêtre disparaîtrait de l'arborescence avec tout ce qu'il contient — un sous-arbre orphelin
     * qu'aucun écran ne sait plus atteindre, et que rien ne signale.
     *
     * La vérification remonte la chaîne des parents dans la même transaction que l'écriture, et
     * se borne à [MAX_DEPTH] tours : si la base contient **déjà** un cycle, la boucle s'arrête au
     * lieu de tourner sans fin.
     */
    suspend fun move(id: String, parentId: String?): Boolean = inTransaction { database ->
        require(id != parentId) { "un dossier ne peut pas etre son propre parent" }
        require(id != Folder.INBOX_ID) { "la boite de reception ne se deplace pas" }
        val folders = database.folderDao()
        if (parentId != null) {
            var ancestor: String? = parentId
            var depth = 0
            while (ancestor != null) {
                require(ancestor != id) { "deplacement refuse : cycle dans l'arborescence" }
                require(depth < MAX_DEPTH) { "arborescence trop profonde ou deja cyclique" }
                ancestor = folders.findById(ancestor)?.parentId
                depth++
            }
        }
        folders.move(id, parentId, clock.millis()) > 0
    }

    /**
     * Supprime un dossier **et toutes ses notes**, par la cascade du schéma.
     *
     * ⚠️ La boîte de réception est protégée dans la requête SQL elle-même, pas par un test ici :
     * une règle qu'il faut se rappeler à chaque point d'appel est un défaut en attente d'un nouvel
     * appelant.
     *
     * @return `false` si l'identifiant est inconnu **ou** s'il s'agit de la boîte de réception.
     */
    suspend fun delete(id: String): Boolean = databases.get().folderDao().delete(id) > 0

    /**
     * Vide un dossier vers un autre avant de le supprimer, pour garder ses notes.
     *
     * Sans cette réassignation, la cascade `ON DELETE CASCADE` emporterait les notes — **y compris
     * celles qui sont en corbeille**, dont la rétention de trente jours n'aurait pas été respectée.
     *
     * ## 🔴 Ni la source ni la destination ne peuvent être un coffre
     *
     * La réassignation est un `UPDATE notes SET folder_id = …` en bloc. Elle ne passe **ni** par le
     * scellement, **ni** par la garde qui interdit de déplacer une note verrouillée. C'est donc le
     * chemin par lequel les deux invariants du coffre tombent d'un coup :
     *
     * | Cas | Ce qui se passait sans le refus |
     * |---|---|
     * | destination = coffre | des notes en clair entrent dans le coffre, `encrypted_content` à `NULL` |
     * | source = coffre | ses notes chiffrées survivent à `vault_kek_wrapped`, supprimé avec le dossier |
     *
     * Le second cas est pire que la suppression qu'il prétend éviter : supprimer le coffre avec ses
     * notes est propre, les garder sans leur clé produit des blobs orphelins que rien n'ouvrira.
     *
     * L'application publiée a les deux trous — sa réassignation est le même `UPDATE` nu. Vider un
     * coffre demande de déchiffrer d'abord, ce que seul le service de coffres saura faire (phase 4,
     * équivalent de `FolderVaultService.decryptAllNotesInFolder`).
     *
     * Relevé par la relecture **des correctifs** (Gemini 3.1 Pro, 2026-08-13) — le lot précédent
     * avait été déclaré exempt de fuite de clair, et celle-ci passait par un chemin que la revue
     * initiale n'avait pas suivi jusqu'au bout.
     *
     * ## 🔴 Une suppression refusée doit tout annuler, pas seulement s'abstenir
     *
     * Les deux gestes sont dans la même transaction, et le second **lève** s'il ne supprime rien.
     * C'est cette levée qui annule le premier.
     *
     * Une première version rendait `null` au lieu de lever. La transaction se terminait alors
     * normalement et Room la validait : appliqué à la boîte de réception — protégée dans la requête
     * de suppression, qui rend donc `0` — le dossier survivait **vidé de toutes ses notes**, parties
     * définitivement ailleurs. L'opération refusée produisait quand même son effet destructeur.
     *
     * Relevé par une relecture externe (Gemini 3.1 Pro, 2026-08-13). Le motif est général :
     * *dans une transaction, un refus qui se contente de rendre une valeur ne défait rien.*
     *
     * @return le nombre de notes déplacées.
     * @throws IllegalArgumentException si la destination est la source, s'il s'agit de la boîte de
     *   réception, ou si l'un des deux dossiers est un coffre.
     * @throws IllegalStateException si le dossier n'existe pas. Rien n'est alors déplacé.
     */
    suspend fun deleteKeepingNotes(id: String, destinationId: String): Int = inTransaction { database ->
        require(id != destinationId) { "destination identique a la source" }
        require(id != Folder.INBOX_ID) { "la boite de reception ne se supprime pas" }

        // 🔴 Les deux refus ci-dessous existent parce que la réassignation est un `UPDATE` en bloc :
        // elle ne passe ni par le scellement, ni par la garde de déplacement des notes verrouillées.
        // Sans eux, cette méthode est le trou par lequel les deux invariants du coffre tombent.
        val folders = database.folderDao()
        require(folders.isVault(destinationId) != true) {
            "destination refusee : $destinationId est un coffre, les notes y entreraient EN CLAIR"
        }
        require(folders.isVault(id) != true) {
            "source refusee : $id est un coffre, ses notes chiffrees lui survivraient sans leur cle"
        }

        val moved = database.noteWriteDao().reassignFolder(
            sourceId = id,
            destinationId = destinationId,
            updatedAt = clock.millis(),
        )
        check(folders.delete(id) > 0) {
            "dossier $id introuvable : la reassignation de $moved note(s) est annulee"
        }
        moved
    }

    // -- Rouages internes -----------------------------------------------------

    /**
     * ⚠️ **Le seul contrôle est « non vide », comme dans l'application publiée**
     * (`folders_repository.dart:120`), qui ne plafonne pas la longueur.
     *
     * Une borne inventée ici aurait un effet qu'on ne voit pas en la posant : elle refuserait de
     * renommer un dossier qui porte DÉJÀ un nom plus long, écrit par la version Flutter. La
     * limitation, si elle est souhaitable, est l'affaire du champ de saisie — pas d'une couche qui
     * doit accepter tout ce que la base contient déjà.
     */
    private fun requireUsableName(name: String): String {
        // ⚠️ `DartTextSemantics.trim` et non `trim()` de Kotlin : les deux n'élaguent pas le même
        // ensemble. Un nom réduit à U+001F est refusé par Kotlin et accepté par Dart ; un nom bordé
        // de U+0085, l'inverse. L'écart est minuscule et sans conséquence de sécurité, mais ce
        // fichier n'a aucune raison d'appliquer d'autres règles que le reste du portage.
        val trimmed = DartTextSemantics.trim(name)
        require(trimmed.isNotEmpty()) { "nom de dossier vide" }
        return trimmed
    }

    private fun <T> observing(source: (NotesDatabase) -> Flow<T>): Flow<T> = flow { emitAll(source(databases.get())) }

    private suspend fun <T> inTransaction(block: suspend (NotesDatabase) -> T): T {
        val database = databases.get()
        return database.withTransaction { block(database) }
    }

    private companion object {
        /** Borne de sûreté sur la remontée des parents, au cas où la base porterait déjà un cycle. */
        const val MAX_DEPTH = 64
    }
}
