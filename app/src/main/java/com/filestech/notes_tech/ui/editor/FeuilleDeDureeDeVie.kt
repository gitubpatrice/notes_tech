package com.filestech.notes_tech.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.DureeDeVie
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.common.phrase
import com.filestech.notes_tech.ui.common.rememberMaintenant
import com.filestech.notes_tech.ui.common.resteDeVie
import java.time.Instant

/**
 * "Lifetime…" of the editor's ⋮ menu (D-043, 3.4.0): none, 1 hour, 24 hours, 7 days, 30 days, after
 * reading. A tap applies it and closes, as the colour sheet does.
 *
 * The radio shows what is known: "None" or "After reading". A dated lifetime says what is left above the
 * list — which of the durations set it is not stored, and a radio would claim it.
 */
@Composable
internal fun FeuilleDeDureeDeVie(
    expireLe: Instant?,
    lectureUnique: Boolean,
    onChoisir: (DureeDeVie?) -> Unit,
    onDismiss: () -> Unit,
) {
    val reste = resteDeVie(rememberMaintenant(), expireLe, lectureUnique)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lifetime_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                reste?.let { Text(it.phrase(), style = MaterialTheme.typography.bodyMedium) }
                Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                    Choix(R.string.lifetime_none, choisi = reste == null) { onChoisir(null) }
                    DureeDeVie.entries.forEach { duree ->
                        Choix(
                            libelle = duree.libelle(),
                            detail = if (duree ==
                                DureeDeVie.APRES_LECTURE
                            ) {
                                R.string.lifetime_after_reading_detail
                            } else {
                                null
                            },
                            choisi = duree == DureeDeVie.APRES_LECTURE && lectureUnique,
                        ) { onChoisir(duree) }
                    }
                }
                Text(
                    text = stringResource(R.string.lifetime_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun Choix(@StringRes libelle: Int, choisi: Boolean, @StringRes detail: Int? = null, onClick: () -> Unit) {
    // One node per choice, the row selectable — the radio itself takes no tap: one target, one stop.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = choisi, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = choisi, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(libelle), style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What opens the sheets about the note's card from the ⋮ menu — its colour (3.1.0), its lifetime (3.4.0).
 * One parameter of `NoteEditorScreen` for both: it holds as many as detekt allows. No default, for the
 * screen's reason: a menu entry wired to nothing would be a dead button that compiles.
 */
data class FeuillesDeLaFiche(val couleur: () -> Unit, val dureeDeVie: () -> Unit)

/** The sheet as the editor shows it: while [ouverte], for a [note] that is readable. */
@Composable
internal fun DureeDeVieDeLEditeur(
    ouverte: Boolean,
    note: Note?,
    onChoisir: (DureeDeVie?) -> Unit,
    onFermer: () -> Unit,
) {
    if (!ouverte || note == null) return
    FeuilleDeDureeDeVie(
        expireLe = note.expiresAt,
        lectureUnique = note.readOnce,
        onChoisir = { duree ->
            onFermer()
            onChoisir(duree)
        },
        onDismiss = onFermer,
    )
}

/**
 * Said once the note is open (D-043): closing it erases it. A note sent "read once" (D-044) must not go
 * without its reader being told. Once per opening — saved, a rotation does not say it again.
 */
@Composable
internal fun AnnonceDeLectureUnique(noteChargee: Boolean, effaceeALaFermeture: Boolean, messages: SnackbarHostState) {
    var annoncee by rememberSaveable { mutableStateOf(false) }
    val phrase = stringResource(R.string.lifetime_read_once_notice)
    LaunchedEffect(noteChargee) {
        if (noteChargee && effaceeALaFermeture && !annoncee) {
            annoncee = true
            messages.showSnackbar(phrase, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
}

@StringRes
internal fun DureeDeVie.libelle(): Int = when (this) {
    DureeDeVie.UNE_HEURE -> R.string.lifetime_1h
    DureeDeVie.UN_JOUR -> R.string.lifetime_24h
    DureeDeVie.SEPT_JOURS -> R.string.lifetime_7d
    DureeDeVie.TRENTE_JOURS -> R.string.lifetime_30d
    DureeDeVie.APRES_LECTURE -> R.string.lifetime_after_reading
}
