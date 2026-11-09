package com.filestech.notes_tech.ui.home

/**
 * What the selection can do (3.3.0), one lambda per gesture — no default anywhere: a menu entry wired
 * to `{}` would be a dead button that compiles.
 *
 * Each takes no argument: `HomeRoute` knows the selection and builds the gesture from it.
 */
class ActionsDeSelection(
    val onFermer: () -> Unit,
    val onToutSelectionner: () -> Unit,
    /** Into the archive — or out of it, in the archive's view. */
    val onArchiver: () -> Unit,
    val onDeplacer: () -> Unit,
    val onCorbeille: () -> Unit,
    /** One note selected: open it to be written, as the 3.1.0 sheet's "Edit" did. */
    val onEditer: () -> Unit,
    val onEpingler: (Boolean) -> Unit,
    val onFavori: (Boolean) -> Unit,
    val onCouleur: () -> Unit,
    val onSupprimerDefinitivement: () -> Unit,
)
