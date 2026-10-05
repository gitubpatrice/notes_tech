package com.filestech.notes_tech.domain.repository

import com.filestech.notes_tech.domain.model.Note

/**
 * Rend lisible une note scellée, pour la couche qui écrit.
 *
 * ## Pourquoi un contrat séparé de [VaultSealer]
 *
 * Les deux gestes ne sont pas symétriques, et les confondre serait une erreur de conception plutôt
 * qu'une économie : **sceller est une protection qu'on gagne, ouvrir est une protection qu'on
 * retire**. Un appelant qui dispose d'un `VaultSealer` ne doit pas se retrouver, par la même
 * dépendance, capable de déchiffrer.
 *
 * C'est la même règle que le DAO applique une couche plus bas, où `lockNote` et `unlockNote` sont
 * séparés pour que le déverrouillage exige d'appeler une méthode qui porte ce nom.
 *
 * ## ⚠️ Ce que cette séparation garantit — et ce qu'elle ne garantit PAS
 *
 * Le seul appelant à passer par **ce type** est `NotesRepository.relocateLockedNote` : sortir une
 * note d'un coffre, ou la faire passer d'un coffre à un autre. Dans les deux cas il faut la clé
 * d'origine, le blob ne se transportant pas tel quel.
 *
 * 🔴 **Cela ne veut pas dire que c'est le seul code capable de déchiffrer**, et une première version
 * de ce commentaire l'affirmait — à tort. Six classes injectent `FolderVaultService` **en entier**,
 * dont l'éditeur et l'exporteur, qui appellent `decrypt` pour afficher et pour exporter. Ces usages
 * sont légitimes et antérieurs : lire une note de coffre demande de la déchiffrer.
 *
 * Ce que la séparation garantit est plus étroit, et c'est déjà utile : **à la couture qui compte** —
 * celle où la couche d'écriture rencontre les coffres — sceller et ouvrir sont deux dépendances
 * distinctes. `NotesRepository` ne peut pas ouvrir par la dépendance qui scelle, ni l'inverse.
 *
 * ⚠️ Le corollaire, pour qui ajoutera un geste : `FolderVaultService.seal` est déjà à portée de six
 * classes. L'appeler directement contournerait `NotesRepository.sealIfVault`, donc la post-condition
 * qui vérifie que le scelleur a bien vidé le clair. **Sceller passe par le dépôt, jamais par le
 * service.**
 *
 * L'interface existe pour la même raison que [VaultSealer] : casser le cycle entre le service de
 * coffres, qui dépend de la base, et la couche d'écriture, qui a besoin de lui.
 */
interface VaultOpener {

    /**
     * Rend [note] lisible **sans la persister** : contenu et titre reviennent dans leurs champs, le
     * blob disparaît de l'objet rendu.
     *
     * Une note qui ne porte pas de blob est rendue telle quelle — il n'y a rien à ouvrir, et ce
     * n'est pas une erreur.
     *
     * ## ⚠️ Le type levé quand la session est fermée appartient à l'implantation
     *
     * Le domaine ne peut pas le nommer : le service réel lève
     * `security.vault.VaultSessionClosedException`, que le paquet `domain` ne connaît pas — et c'est
     * exactement l'écart que `VaultErrors` documente déjà pour les deux exceptions de session. Ne
     * pas rattraper un type précis ici : l'appelant doit traiter **tout** échec comme un refus, ce
     * qui est de toute façon la seule conduite sûre. [UnavailableVaultOpener] lève
     * [VaultLockedException], qui porte l'identifiant de la note.
     */
    suspend fun decrypt(note: Note): Note
}

/**
 * L'ouvreur quand aucun service de coffres n'est câblé : il refuse.
 *
 * Même raison d'être que [UnavailableVaultSealer], et le même refus de bouchon permissif — sauf que
 * le danger est ici inversé. Un scelleur permissif écrirait du clair là où on attend du chiffré ; un
 * ouvreur permissif rendrait la note **inchangée**, donc encore scellée, et l'appelant écrirait un
 * blob illisible dans un dossier ordinaire. Une note perdue, sans le moindre message.
 */
class UnavailableVaultOpener : VaultOpener {
    override suspend fun decrypt(note: Note): Note =
        throw VaultLockedException(noteId = note.id, folderId = note.folderId)
}
