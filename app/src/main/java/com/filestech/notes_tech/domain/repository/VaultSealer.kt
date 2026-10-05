package com.filestech.notes_tech.domain.repository

import com.filestech.notes_tech.domain.model.Note

/**
 * Scelle une note qui appartient à un coffre, avant qu'elle n'atteigne la base.
 *
 * ## Pourquoi une interface, et pourquoi maintenant
 *
 * Le chiffrement des coffres est la phase 4 ; les écritures de notes sont la phase 3. Sans ce
 * contrat, la couche qui écrit n'aurait aucun moyen d'exprimer « cette note doit être chiffrée » —
 * et la tentation serait d'écrire d'abord, de chiffrer plus tard. C'est la fenêtre par laquelle le
 * clair part sur le disque.
 *
 * ## 🔴 L'implémentation par défaut REFUSE, elle ne laisse pas passer
 *
 * [UnavailableVaultSealer] lève. Tant que la phase 4 n'a pas livré, toute tentative d'écrire une
 * note dans un dossier coffre échoue **bruyamment**.
 *
 * C'est le contraire de ce qu'un bouchon fait d'habitude, et c'est délibéré : un bouchon permissif
 * aurait exactement le comportement qu'on cherche à rendre impossible — écrire en clair les notes
 * d'un coffre — et il ne se verrait pas, puisque rien n'échouerait. Une écriture refusée se remarque
 * à la première tentative ; une écriture en clair ne se remarque jamais.
 *
 * La version Flutter câble ce scellement après construction, par un passeur nullable, parce que le
 * service de coffres dépend déjà du repository et que l'injecter en retour créerait un cycle. Ici,
 * l'interface casse le cycle sans câblage tardif : le repository dépend d'un contrat, pas d'un
 * service.
 */
interface VaultSealer {

    /**
     * Rend [note] chiffrée : contenu dans le blob, colonne `content` vidée, et titre vidé aussi à
     * partir du format 2.
     *
     * @throws VaultLockedException si la session du coffre n'est pas ouverte — aucune clé n'existe
     *   alors en mémoire, et il n'y a rien à faire d'autre que refuser. C'est le cas d'un
     *   verrouillage automatique qui tombe pendant l'édition ; persister en clair y serait
     *   exactement la fuite qu'on cherche à empêcher.
     */
    suspend fun seal(note: Note): Note
}

/**
 * Le scelleur tant que la phase 4 n'a rien livré : il refuse tout.
 *
 * Un chemin qui lève n'est pas un chemin mort. Il documente une capacité absente à l'endroit exact
 * où elle manquera, et il rend impossible d'oublier de le remplacer — la première note écrite dans
 * un coffre le signalera.
 */
class UnavailableVaultSealer : VaultSealer {
    override suspend fun seal(note: Note): Note = throw VaultLockedException(noteId = note.id, folderId = note.folderId)
}

/**
 * Une note appartenant à un coffre allait être écrite en clair, et l'écriture a été refusée.
 *
 * ⚠️ Le message ne contient **ni titre ni contenu** : une exception voyage dans les journaux et les
 * rapports de plantage, et celle-ci naît précisément au contact d'un secret.
 */
class VaultLockedException(val noteId: String, val folderId: String) :
    IllegalStateException(
        "ecriture en clair refusee : la note $noteId appartient au coffre $folderId, dont la session " +
            "n'est pas ouverte",
    )
