package com.filestech.notes_tech.domain.model

/**
 * Les six ordres de tri qu'offre l'application publiée, avec leur clause `ORDER BY`.
 *
 * ## ⚠️ [orderBy] est du SQL, et n'est jamais construit à partir d'une saisie
 *
 * Ces chaînes sont concaténées dans une requête, ce qu'aucune valeur d'origine utilisateur ne doit
 * jamais être. C'est sans risque **et seulement parce que** l'ensemble est fermé : un `enum` ne
 * s'étend pas depuis l'extérieur, et chaque valeur est écrite en toutes lettres ici.
 *
 * La contrainte à tenir est donc simple à énoncer et facile à vérifier en relecture : rien de ce
 * fichier ne doit jamais devenir un paramètre. Si un jour un tri devait dépendre d'une colonne
 * choisie ailleurs, il faudrait une liste blanche, pas une interpolation.
 *
 * ## L'épinglage passe avant, sauf là où il ne passe pas
 *
 * Les six modes commencent par `pinned DESC` : une note épinglée reste en tête quel que soit le tri
 * demandé. Deux écrans font exception et n'utilisent pas cet énuméré — « notes récentes » trie sur
 * `updated_at` seul, la corbeille sur `trashed_at`. Voir `NoteDao.observeRecent`, dont le
 * commentaire explique pourquoi y ajouter l'épinglage serait une régression.
 */
enum class NoteSortMode(val orderBy: String) {
    UPDATED_DESC("pinned DESC, updated_at DESC"),
    UPDATED_ASC("pinned DESC, updated_at ASC"),
    CREATED_DESC("pinned DESC, created_at DESC"),
    CREATED_ASC("pinned DESC, created_at ASC"),
    TITLE_ASC("pinned DESC, title COLLATE NOCASE ASC"),
    TITLE_DESC("pinned DESC, title COLLATE NOCASE DESC"),
    ;

    companion object {
        /** Celui de l'application publiée quand rien n'est précisé. */
        val DEFAULT = UPDATED_DESC
    }
}
