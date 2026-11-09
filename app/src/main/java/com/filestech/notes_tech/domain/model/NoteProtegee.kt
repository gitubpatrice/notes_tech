package com.filestech.notes_tech.domain.model

/**
 * `true` if a gesture may touch this note only while its vault is open (3.3.0): it is sealed, or it sits
 * in a vault folder — an empty note just created in a vault is not sealed yet, and is a vault note all
 * the same. [dossier] is the note's folder, `null` if unknown: a sealed note is protected wherever it is.
 *
 * ONE definition, read by every place that asks (coherence audit, 2026-11-09: six copies, three of them
 * worded differently): the selection's courtesy (`GestesDeNoteEnCours.basculer`, `HomeRoute`'s "Select
 * all"), the questions it closes when a vault locks, and the protection itself, at execution
 * (`HomeViewModel.executer`, `NoteEditorViewModel.deletePermanently`).
 */
fun Note.estProtegee(dossier: Folder?): Boolean = isLocked || dossier?.isVault == true
