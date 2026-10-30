package com.filestech.notes_tech.ui.settings

import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.sauvegarde.RestaurationDeSauvegarde
import com.filestech.notes_tech.data.sauvegarde.SauvegardeChiffree
import com.filestech.notes_tech.security.sauvegarde.SecretDeSauvegarde
import com.filestech.notes_tech.security.vault.VaultParams
import com.filestech.notes_tech.ui.common.rememberAppResultLauncher
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.vault.ChampDePhraseSecrete
import java.time.LocalDate

/**
 * The Settings' encrypted backup (3.2.0, D-029 to D-031): its two rows, and the dialogs of each step
 * ([SauvegardeViewModel.Etape]). Both pickers go through the app's seam ([rememberAppResultLauncher]):
 * without it, the app lock would ask for the PIN on the way back, the step lost. The vaults lock all
 * the same when a picker opens: the backup is written before it ([SauvegardeViewModel.secretChoisi]).
 */
@Composable
internal fun LignesDeSauvegarde() {
    val viewModel: SauvegardeViewModel = hiltViewModel()
    val etape by viewModel.etape.collectAsStateWithLifecycle()
    val creation =
        rememberAppResultLauncher(ActivityResultContracts.CreateDocument(TYPE_DU_FICHIER), viewModel::documentCree)
    val ouverture = rememberAppResultLauncher(ActivityResultContracts.OpenDocument(), viewModel::documentOuvert)
    val auRepos = etape == SauvegardeViewModel.Etape.Repos

    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_create)) },
            supportingContent = { Text(stringResource(R.string.backup_create_subtitle)) },
            leadingContent = { Icon(Icons.Outlined.Backup, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(enabled = auRepos, onClick = viewModel::preparerLaCreation),
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_restore)) },
            supportingContent = { Text(stringResource(R.string.backup_restore_subtitle)) },
            leadingContent = { Icon(Icons.Outlined.Restore, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            // Any type: a `.ntbackup` has none of its own, most providers say `application/octet-stream`.
            modifier = Modifier.clickable(enabled = auRepos) {
                if (!ouverture.launch(arrayOf("*/*"))) viewModel.sansSelecteur()
            },
        )
    }

    // Every dialog of a flow bears the title of the row that started it: "Create a backup" from the
    // preview to the end, "Restore a backup" from the secret to the end — waiting and failure included.
    when (val e = etape) {
        SauvegardeViewModel.Etape.Repos, SauvegardeViewModel.Etape.EnAttenteDuDocument -> Unit
        is SauvegardeViewModel.Etape.Occupe -> DialogueDAttente(e.titre)
        is SauvegardeViewModel.Etape.AvantLaCreation -> DialogueDeCreation(
            apercu = e.apercu,
            code = e.code,
            onEnregistrer = viewModel::secretChoisi,
            onAnnuler = viewModel::fermer,
        )
        SauvegardeViewModel.Etape.PreteADeposer -> LaunchedEffect(Unit) {
            if (creation.launch("notes-tech-${LocalDate.now()}.ntbackup")) {
                viewModel.selecteurOuvert()
            } else {
                viewModel.sansSelecteur()
            }
        }
        is SauvegardeViewModel.Etape.Creee -> DialogueDeFin(
            titre = R.string.backup_create,
            message = texteDeLaCreation(e.creation),
            onFermer = viewModel::fermer,
        )
        is SauvegardeViewModel.Etape.SecretADemander -> DialogueDeSecret(
            erreur = e.erreur,
            onEssayer = viewModel::essayer,
            onAnnuler = viewModel::fermer,
        )
        is SauvegardeViewModel.Etape.Essayee -> DialogueDEssai(
            e.resultat,
            onRestaurer = viewModel::restaurer,
            onAnnuler = viewModel::fermer,
        )
        is SauvegardeViewModel.Etape.Restauree -> DialogueDeFin(
            titre = R.string.backup_restore,
            message = texteDuBilan(e.resultat, apres = true),
            onFermer = viewModel::fermer,
        )
        is SauvegardeViewModel.Etape.Echec -> DialogueDeFin(
            titre = e.titre,
            message = e.argument?.let { stringResource(e.message, it) } ?: stringResource(e.message),
            onFermer = viewModel::fermer,
        )
    }
}

/** Not dismissible: what runs cannot be left half done from here, and it ends on its own. */
@Composable
private fun DialogueDAttente(@StringRes titre: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(titre)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.backup_working))
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun DialogueDeFin(@StringRes titre: Int, message: String, onFermer: () -> Unit) {
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(titre)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onFermer) { Text(stringResource(R.string.common_ok)) } },
    )
}

/**
 * Before a backup is made: what it holds, what it leaves out and why, then its secret — the code drawn
 * for it, to write down and type back, or a phrase of the user's.
 *
 * 🔴 The window is secured while it is shown, whatever the setting: the code on screen opens the file.
 * The fields are `remember`, not saved: a secret typed there must not end up in a saved state.
 */
@Composable
private fun DialogueDeCreation(
    apercu: SauvegardeChiffree.Apercu,
    code: String,
    onEnregistrer: (String) -> Unit,
    onAnnuler: () -> Unit,
) {
    SecureWindowGuard(active = true)
    var avecUnePhrase by remember { mutableStateOf(false) }
    var recopie by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val refus = SecretDeSauvegarde.refusDeLaPhrase(phrase).takeIf { phrase.isNotEmpty() }
    val secret = if (avecUnePhrase) {
        phrase.takeIf { it.isNotEmpty() && refus == null && it == confirmation }
    } else {
        code.takeIf { SecretDeSauvegarde.codeCanonique(recopie) == SecretDeSauvegarde.codeCanonique(code) }
    }

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(stringResource(R.string.backup_create)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pluralStringResource(R.plurals.backup_create_notes, apercu.notes, apercu.notes))
                val fermes = apercu.coffresACodeFermes
                val ouverts = apercu.coffresACodeOuverts
                if (fermes.isNotEmpty()) {
                    Text(
                        pluralStringResource(
                            R.plurals.backup_create_closed_pin,
                            fermes.size,
                            fermes.joinToString(", "),
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (ouverts.isNotEmpty()) {
                    Text(
                        pluralStringResource(
                            R.plurals.backup_create_open_pin,
                            ouverts.size,
                            ouverts.joinToString(", "),
                        ),
                    )
                }
                Text(stringResource(R.string.backup_create_panic), style = MaterialTheme.typography.bodySmall)
                if (avecUnePhrase) {
                    ChampDePhraseSecrete(
                        valeur = phrase,
                        onValeurChange = { phrase = it },
                        label = stringResource(R.string.backup_phrase),
                        actionClavier = ImeAction.Next,
                        actif = true,
                    )
                    ChampDePhraseSecrete(
                        valeur = confirmation,
                        onValeurChange = { confirmation = it },
                        label = stringResource(R.string.backup_phrase_confirm),
                        actionClavier = ImeAction.Done,
                        actif = true,
                    )
                    val probleme = when {
                        refus == SecretDeSauvegarde.Refus.TROP_COURTE ->
                            stringResource(
                                R.string.backup_phrase_too_short,
                                SecretDeSauvegarde.LONGUEUR_MIN_DE_LA_PHRASE,
                            )
                        refus == SecretDeSauvegarde.Refus.EVIDENTE -> stringResource(R.string.backup_phrase_obvious)
                        confirmation.isNotEmpty() && confirmation != phrase -> stringResource(
                            R.string.backup_phrase_mismatch,
                        )
                        else -> null
                    }
                    probleme?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { avecUnePhrase = false }) { Text(stringResource(R.string.backup_use_code)) }
                } else {
                    Text(stringResource(R.string.backup_code_intro))
                    // Two lines of three groups: left to the text layout, a line broke inside a group.
                    Text(
                        code.split('-').chunked(GROUPES_PAR_LIGNE).joinToString("\n") { it.joinToString("-") },
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        // Spelled, group by group: read as text, TalkBack said "K7QM" as a word (audit 3
                        // axes of 3.2.0, U2). The dialog is a secured window, as the code on screen.
                        modifier = Modifier.semantics { contentDescription = codeEpele(code) },
                    )
                    ChampDePhraseSecrete(
                        valeur = recopie,
                        onValeurChange = { recopie = it },
                        label = stringResource(R.string.backup_code_retype),
                        actionClavier = ImeAction.Done,
                        actif = true,
                        afficher = R.string.backup_code_show,
                        masquer = R.string.backup_code_hide,
                    )
                    TextButton(onClick = { avecUnePhrase = true }) { Text(stringResource(R.string.backup_use_phrase)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { secret?.let(onEnregistrer) }, enabled = secret != null) {
                Text(stringResource(R.string.backup_save))
            }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The chosen file's code or passphrase — asked again, field empty, after a wrong one ([erreur]).
 * Secured window, as at the creation: the field can show what it holds (GPT-5.6 review, 2026-10-29).
 */
@Composable
private fun DialogueDeSecret(@StringRes erreur: Int?, onEssayer: (String) -> Unit, onAnnuler: () -> Unit) {
    SecureWindowGuard(active = true)
    var secret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(stringResource(R.string.backup_restore)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChampDePhraseSecrete(
                    valeur = secret,
                    onValeurChange = { secret = it },
                    label = stringResource(R.string.backup_restore_secret),
                    actionClavier = ImeAction.Done,
                    actif = true,
                    afficher = R.string.backup_secret_show,
                    masquer = R.string.backup_secret_hide,
                )
                erreur?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEssayer(secret) }, enabled = secret.isNotBlank()) {
                Text(stringResource(R.string.backup_restore_check))
            }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The trial pass's answer: what the restore will add, what it leaves and why — and a new PIN, twice,
 * for each PIN vault it brings back (D-030). Secured window: those fields can show the new PINs.
 */
@Composable
private fun DialogueDEssai(
    resultat: RestaurationDeSauvegarde.Resultat,
    onRestaurer: (Map<String, String>) -> Unit,
    onAnnuler: () -> Unit,
) {
    SecureWindowGuard(active = true)
    val codes = remember { mutableStateMapOf<String, String>() }
    val confirmations = remember { mutableStateMapOf<String, String>() }
    val rienAAjouter = resultat.notesAjoutees + resultat.notesACote + resultat.dossiersCrees == 0
    val codesValides = resultat.coffresACode.all { coffre ->
        val code = codes[coffre.id].orEmpty()
        CODE_VALIDE.matches(code) && code == confirmations[coffre.id]
    }

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(stringResource(R.string.backup_restore)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(texteDuBilan(resultat, apres = false))
                if (!rienAAjouter) {
                    Text(
                        stringResource(R.string.backup_restore_deleted_since),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                resultat.coffresACode.forEach { coffre ->
                    Text(
                        stringResource(R.string.backup_restore_pin_vault, coffre.nom),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    ChampDePhraseSecrete(
                        valeur = codes[coffre.id].orEmpty(),
                        onValeurChange = { codes[coffre.id] = it.filter(Char::isDigit) },
                        label = stringResource(R.string.backup_restore_pin),
                        actionClavier = ImeAction.Next,
                        actif = true,
                        typeDeClavier = KeyboardType.NumberPassword,
                        afficher = R.string.pin_show_tooltip,
                        masquer = R.string.pin_hide_tooltip,
                    )
                    ChampDePhraseSecrete(
                        valeur = confirmations[coffre.id].orEmpty(),
                        onValeurChange = { confirmations[coffre.id] = it.filter(Char::isDigit) },
                        label = stringResource(R.string.backup_restore_pin_confirm),
                        actionClavier = ImeAction.Done,
                        actif = true,
                        typeDeClavier = KeyboardType.NumberPassword,
                        afficher = R.string.pin_show_tooltip,
                        masquer = R.string.pin_hide_tooltip,
                    )
                }
                if (resultat.coffresACode.isNotEmpty() && !codesValides) {
                    Text(
                        stringResource(
                            R.string.backup_restore_pin_rule,
                            VaultParams.PIN_MIN_LENGTH,
                            VaultParams.PIN_MAX_LENGTH,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (rienAAjouter) {
                TextButton(onClick = onAnnuler) { Text(stringResource(R.string.common_ok)) }
            } else {
                TextButton(onClick = { onRestaurer(codes.toMap()) }, enabled = codesValides) {
                    Text(stringResource(R.string.backup_restore_do))
                }
            }
        },
        dismissButton = {
            if (!rienAAjouter) TextButton(onClick = onAnnuler) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** What the backup made holds — and what it left out, said after as it was before. */
@Composable
private fun texteDeLaCreation(c: SauvegardeChiffree.Creation): String = buildList {
    add(pluralStringResource(R.plurals.backup_created, c.notes, c.notes))
    if (c.omises > 0) add(pluralStringResource(R.plurals.backup_created_left_out, c.omises, c.omises))
}.joinToString("\n")

/**
 * What a restore did ([apres] `true`) or would do: one line per kind of outcome there is — the notes
 * and folders added said in the tense of the moment, the rest true before as after.
 */
@Composable
private fun texteDuBilan(r: RestaurationDeSauvegarde.Resultat, apres: Boolean): String = buildList {
    val ajoutees = r.notesAjoutees + r.notesACote
    val dossiers = r.dossiersCrees
    val ignorees = r.notesIgnorees.values.sum()
    when {
        apres -> add(pluralStringResource(R.plurals.backup_restored, ajoutees, ajoutees))
        ajoutees + dossiers == 0 -> add(stringResource(R.string.backup_restore_nothing))
        else -> add(pluralStringResource(R.plurals.backup_restore_add_notes, ajoutees, ajoutees))
    }
    if (dossiers > 0) {
        val pluriel = if (apres) R.plurals.backup_restored_folders else R.plurals.backup_restore_add_folders
        add(pluralStringResource(pluriel, dossiers, dossiers))
    }
    val aLaCorbeille = r.notesALaCorbeille
    if (aLaCorbeille > 0) add(pluralStringResource(R.plurals.backup_restore_trash, aLaCorbeille, aLaCorbeille))
    if (r.notesACote > 0) add(pluralStringResource(R.plurals.backup_restore_beside, r.notesACote, r.notesACote))
    if (r.notesDejaLa > 0) add(pluralStringResource(R.plurals.backup_restore_already, r.notesDejaLa, r.notesDejaLa))
    if (ignorees > 0) add(pluralStringResource(R.plurals.backup_restore_ignored, ignorees, ignorees))
}.joinToString("\n")

/** A vault's PIN, as the vaults take it — their own bounds, not a copy of them. */
private val CODE_VALIDE = Regex("[0-9]{${VaultParams.PIN_MIN_LENGTH},${VaultParams.PIN_MAX_LENGTH}}")

/** How many of the code's groups a line shows. */
private const val GROUPES_PAR_LIGNE = 3

/** [code] for TalkBack: its signs one by one, a pause between groups — "K, 7, Q, M. T, 2, X, B…". */
internal fun codeEpele(code: String): String =
    code.split('-').joinToString(". ") { groupe -> groupe.toList().joinToString(", ") }

/** What the system picker is told the file is: it has no type of its own. */
private const val TYPE_DU_FICHIER = "application/octet-stream"
