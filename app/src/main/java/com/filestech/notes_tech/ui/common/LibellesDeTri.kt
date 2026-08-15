package com.filestech.notes_tech.ui.common

import androidx.annotation.StringRes
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.NoteSortMode

/**
 * Le libellé d'un mode de tri.
 *
 * ## Partagé entre l'accueil et les réglages, et c'est le but
 *
 * L'application publiée ne propose le tri qu'aux réglages ; le portage l'avait déplacé dans la
 * barre d'accueil. Depuis le 2026-08-15 il est aux **deux** endroits, sur le même réglage — et deux
 * tables de libellés pour un même ensemble d'options auraient divergé au premier mode ajouté, un
 * écran nommant « Plus récentes d'abord » ce que l'autre appelle autrement.
 */
@StringRes
fun libelleDeTri(mode: NoteSortMode): Int = when (mode) {
    NoteSortMode.UPDATED_DESC -> R.string.home_sort_recent_first
    NoteSortMode.UPDATED_ASC -> R.string.home_sort_old_first
    NoteSortMode.CREATED_DESC -> R.string.home_sort_created_recent_first
    NoteSortMode.CREATED_ASC -> R.string.home_sort_created_old_first
    NoteSortMode.TITLE_ASC -> R.string.home_sort_alpha_asc
    NoteSortMode.TITLE_DESC -> R.string.home_sort_alpha_desc
}
