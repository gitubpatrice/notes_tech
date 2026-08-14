package com.filestech.notes_tech.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.VaultMode
import com.filestech.notes_tech.security.vault.VaultParams
import com.filestech.notes_tech.security.vault.VaultValidationException
import com.filestech.notes_tech.ui.secure.SecureWindowGuard

/**
 * La feuille qui déverrouille un coffre, **du bon mode**.
 *
 * ⚠️ Le mode vient de ce que le dossier **porte** (`VaultMode.fromMaterial`), pas de la colonne
 * `vault_mode`. Un coffre à code présenté avec un champ de phrase secrète serait refusé par le
 * service, et l'utilisateur n'aurait aucun moyen de comprendre pourquoi.
 *
 * ⚠️ **`UNKNOWN` n'ouvre aucune feuille.** Un dossier qui porte un sel mais aucun matériel de clé
 * exploitable ne se déverrouille par rien : proposer une saisie ferait consommer des tentatives sur
 * un coffre que personne ne peut ouvrir — et sur le chemin du code, en détruirait le contenu au
 * cinquième essai.
 */
@Composable
fun UnlockVaultSheet(folder: Folder, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    when (folder.vault?.mode) {
        VaultMode.PIN -> PinSheet(
            folder = folder,
            creating = false,
            onDismiss = onDismiss,
            onDone = onUnlocked,
        )

        VaultMode.PASSPHRASE -> PassphraseSheet(
            folder = folder,
            creating = false,
            onDismiss = onDismiss,
            onDone = onUnlocked,
        )

        VaultMode.UNKNOWN, null -> DamagedVaultSheet(onDismiss = onDismiss)
    }
}

/** Le choix du mode, à la création d'un coffre. */
@Composable
fun ChooseVaultModeSheet(onDismiss: () -> Unit, onChosen: (VaultMode) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp).navigationBarsPadding()) {
            TitreDeFeuille(stringResource(R.string.vault_mode_choose))
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_mode_passphrase)) },
                supportingContent = { Text(stringResource(R.string.vault_mode_passphrase_desc)) },
                leadingContent = { Icon(Icons.Outlined.Password, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickableListItem { onChosen(VaultMode.PASSPHRASE) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_mode_pin)) },
                supportingContent = { Text(stringResource(R.string.vault_mode_pin_desc)) },
                leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickableListItem { onChosen(VaultMode.PIN) },
            )
        }
    }
}

/** La feuille de création, une fois le mode choisi. */
@Composable
fun CreateVaultSheet(folder: Folder, mode: VaultMode, onDismiss: () -> Unit, onCreated: () -> Unit) {
    when (mode) {
        VaultMode.PIN -> PinSheet(folder, creating = true, onDismiss = onDismiss, onDone = onCreated)
        else -> PassphraseSheet(folder, creating = true, onDismiss = onDismiss, onDone = onCreated)
    }
}

// ── Phrase secrète ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun PassphraseSheet(folder: Folder, creating: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    // ⚠️ Le drapeau est forcé pour la durée de la feuille, **même si l'utilisateur l'a désactivé**.
    // Ce qui s'affiche ici est une phrase secrète en clair quand il choisit de la rendre visible ;
    // une capture, volontaire ou par une application de projection d'écran, la donnerait entière.
    SecureWindowGuard()

    val viewModel: VaultViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var secret by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var erreurLocale by remember { mutableStateOf<String?>(null) }

    val tropCourte = stringResource(R.string.vault_pass_min_length, VaultParams.PASSPHRASE_MIN_LENGTH)
    val discordance = stringResource(R.string.vault_pass_mismatch)

    ResultatDeTentative(state.attempt, onSuccess = onDone, onConsumed = viewModel::consumeAttempt)

    ModalBottomSheet(
        // ⚠️ Fermer la feuille ANNULE la dérivation en cours. Sans ça, le travail continue dans
        // `viewModelScope` et le coffre s'ouvre une seconde plus tard, sans que rien à l'écran
        // ne l'indique. Relevé par une relecture externe (GPT-5.2).
        onDismissRequest = {
            viewModel.cancelAttempt()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TitreDeFeuille(
                stringResource(if (creating) R.string.vault_pass_create_title else R.string.vault_pass_unlock_title),
            )
            Text(
                text = if (creating) {
                    stringResource(R.string.vault_pass_create_body)
                } else {
                    stringResource(R.string.vault_pass_unlock_body, folder.name)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (creating) BanniereDAvertissement(stringResource(R.string.vault_pass_warning_lost))

            OutlinedTextField(
                value = secret,
                onValueChange = {
                    secret = it
                    erreurLocale = null
                },
                label = { Text(stringResource(R.string.vault_pass_field)) },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (creating) ImeAction.Next else ImeAction.Done,
                ),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (visible) R.string.passphrase_hide_tooltip else R.string.passphrase_show_tooltip,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (creating) {
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = {
                        confirmation = it
                        erreurLocale = null
                    },
                    label = { Text(stringResource(R.string.vault_pass_confirm_field)) },
                    singleLine = true,
                    enabled = !state.busy,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            MessageDEtat(erreurLocale ?: messageDeTentative(state.attempt), busy = state.busy)

            if (state.attempt.coffreExiste()) {
                BoutonDeFermeture(onDone)
            } else {
                Button(
                    onClick = {
                        erreurLocale = when {
                            secret.length < VaultParams.PASSPHRASE_MIN_LENGTH -> tropCourte
                            creating && secret != confirmation -> discordance
                            else -> null
                        }
                        if (erreurLocale != null) return@Button
                        if (creating) {
                            viewModel.createPassphraseVault(folder.id, secret)
                        } else {
                            viewModel.unlockWithPassphrase(folder.id, secret)
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (creating) R.string.vault_pass_create_action else R.string.vault_pass_unlock_action,
                        ),
                    )
                }
                TextButton(
                    onClick = {
                        viewModel.cancelAttempt()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }
}

// ── Code à quatre-six chiffres ───────────────────────────────────────────────────────────────────

@Composable
private fun PinSheet(folder: Folder, creating: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    // ⚠️ Même raison que la feuille à phrase secrète, avec un motif propre au pavé numérique : la
    // position des touches enfoncées est stable d'une saisie à l'autre, donc une capture de la
    // séquence donne le code. `vault_pin_sheets.dart:185` note que ce garde manquait ici dans la
    // version publiée alors qu'il protégeait déjà la création — un jumeau asymétrique.
    SecureWindowGuard()

    val viewModel: VaultViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var saisi by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var erreurLocale by remember { mutableStateOf<String?>(null) }

    val tropCourt = stringResource(
        R.string.vault_pin_too_short,
        VaultParams.PIN_MIN_LENGTH,
        VaultParams.PIN_MAX_LENGTH,
    )
    val discordance = stringResource(R.string.vault_pin_mismatch)

    // 🔴 **Le code saisi est vidé après CHAQUE tentative, réussie OU NON.**
    //
    // ⚠️ Ce commentaire décrivait un comportement que le code n'avait pas. `ResultatDeTentative`
    // n'appelait `onConsumed` que sur `Success` : après un code faux, les chiffres restaient à
    // l'écran. Un utilisateur qui retape par-dessus obtient « 1234 » + « 5678 », valide une saisie
    // de six chiffres qui n'est pas la sienne, et **consomme une seconde tentative**. Sur un coffre
    // qui se détruit au cinquième échec, deux frappes suffisent à en perdre deux.
    //
    // Le défaut était invisible à la relecture parce que le commentaire, lui, disait le bon
    // comportement. Relevé par une relecture externe (GPT-5.2, 2026-08-14) — motif « commentaire
    // qui ment » de `docs/04-PIEGES.md`.
    //
    // ⚠️ **Afficher PUIS consommer** : sur un échec, le message doit survivre à l'effacement de
    // la saisie. Seules les issues qui ferment la feuille sont consommées.
    LaunchedEffect(state.attempt) {
        val issue = state.attempt ?: return@LaunchedEffect
        saisi = ""
        val termine = issue is VaultAttempt.Success || (issue is VaultAttempt.Created && issue.isComplete)
        if (termine) {
            onDone()
            viewModel.consumeAttempt()
        }
    }

    val enConfirmation = creating && confirmation != null

    ModalBottomSheet(
        // ⚠️ Fermer la feuille ANNULE la dérivation en cours. Sans ça, le travail continue dans
        // `viewModelScope` et le coffre s'ouvre une seconde plus tard, sans que rien à l'écran
        // ne l'indique. Relevé par une relecture externe (GPT-5.2).
        onDismissRequest = {
            viewModel.cancelAttempt()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TitreDeFeuille(
                stringResource(
                    when {
                        enConfirmation -> R.string.vault_pin_confirm_field
                        creating -> R.string.vault_pin_create_title
                        else -> R.string.vault_pin_unlock_title
                    },
                ),
            )
            if (!creating) {
                Text(
                    text = stringResource(R.string.vault_pin_unlock_body, folder.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (creating) BanniereDAvertissement(stringResource(R.string.vault_pin_warning_wipe))

            PointsDeSaisie(saisi.length)

            MessageDEtat(erreurLocale ?: messageDeTentative(state.attempt), busy = state.busy)

            ClavierNumerique(
                enabled = !state.busy,
                onDigit = { chiffre ->
                    erreurLocale = null
                    if (saisi.length < VaultParams.PIN_MAX_LENGTH) saisi += chiffre
                },
                onDelete = {
                    erreurLocale = null
                    saisi = saisi.dropLast(1)
                },
            )

            Button(
                onClick = {
                    if (saisi.length !in VaultParams.PIN_MIN_LENGTH..VaultParams.PIN_MAX_LENGTH) {
                        erreurLocale = tropCourt
                        return@Button
                    }
                    when {
                        !creating -> viewModel.unlockWithPin(folder.id, saisi)
                        // Première saisie d'une création : on retient et on redemande. Le code
                        // n'est PAS envoyé au service tant que les deux saisies ne concordent pas.
                        confirmation == null -> {
                            confirmation = saisi
                            saisi = ""
                        }
                        saisi != confirmation -> {
                            erreurLocale = discordance
                            confirmation = null
                            saisi = ""
                        }
                        else -> viewModel.createPinVault(folder.id, saisi)
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (creating) R.string.common_validate else R.string.vault_pass_unlock_action))
            }
            TextButton(
                onClick = {
                    viewModel.cancelAttempt()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

/**
 * Ce qu'on affiche quand un dossier se présente comme un coffre sans porter de quoi l'ouvrir.
 *
 * Aucune saisie, aucun bouton d'essai : il n'y a rien à essayer. Le dossier reste visible et
 * verrouillé plutôt que de disparaître — ses notes existent toujours, et une version ultérieure, ou
 * une restauration, pourra peut-être les rendre.
 */
@Composable
private fun DamagedVaultSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier.padding(20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TitreDeFeuille(stringResource(R.string.vault_pass_unlock_title))
            BanniereDAvertissement(stringResource(R.string.vault_pass_warning_lost))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_close))
            }
        }
    }
}

// ── Éléments partagés ────────────────────────────────────────────────────────────────────────────

@Composable
private fun TitreDeFeuille(texte: String) {
    Text(
        text = texte,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun BanniereDAvertissement(texte: String) {
    val couleurs = MaterialTheme.colorScheme
    Surface(
        color = couleurs.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = couleurs.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = texte,
                style = MaterialTheme.typography.bodySmall,
                color = couleurs.onErrorContainer,
            )
        }
    }
}

/**
 * L'emplacement du message d'état — occupé **en permanence**, même vide.
 *
 * ⚠️ Sans hauteur réservée, l'apparition d'un message décale le clavier numérique de quelques
 * pixels vers le bas. Le doigt est déjà en route : la touche visée à l'instant du contact n'est
 * plus celle qu'on frappe. Sur un écran qui détruit le coffre au cinquième essai, un décalage de
 * mise en page est un défaut de sécurité.
 */
@Composable
private fun MessageDEtat(message: String?, busy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            busy -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.vault_pass_deriving),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            message != null -> Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )

            // Une ligne vide, mais présente : c'est elle qui empêche le décalage.
            else -> Text(text = " ", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PointsDeSaisie(saisis: Int) {
    val couleurs = MaterialTheme.colorScheme
    val annonce = stringResource(R.string.vault_pin_digits_announce, saisis, VaultParams.PIN_MAX_LENGTH)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 8.dp).semantics { contentDescription = annonce },
    ) {
        repeat(VaultParams.PIN_MAX_LENGTH) { index ->
            Surface(
                modifier = Modifier.size(14.dp).clip(CircleShape).clearAndSetSemantics { },
                shape = CircleShape,
                color = if (index < saisis) couleurs.primary else couleurs.surfaceContainerHighest,
            ) {}
        }
    }
}

@Composable
private fun ClavierNumerique(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (ligne in TOUCHES) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (touche in ligne) {
                    when (touche) {
                        ' ' -> Surface(modifier = Modifier.size(TAILLE_TOUCHE), color = Color.Transparent) {}
                        '\b' -> TextButton(
                            onClick = onDelete,
                            enabled = enabled,
                            modifier = Modifier.size(TAILLE_TOUCHE),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = stringResource(R.string.vault_pin_key_delete),
                            )
                        }

                        else -> {
                            // ⚠️ `vault_pin_key_label` (« Touche %1$s ») existait et n'était jamais
                            // utilisée : le lecteur d'écran annonçait « 7 » tout court, indiscernable
                            // d'un texte affiché. Relevé par l'audit i18n du 2026-08-14.
                            val etiquette = stringResource(R.string.vault_pin_key_label, "$touche")
                            TextButton(
                                onClick = { onDigit(touche) },
                                enabled = enabled,
                                modifier = Modifier
                                    .size(TAILLE_TOUCHE)
                                    .semantics { contentDescription = etiquette },
                            ) {
                                Text(text = "$touche", style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Traduit l'issue d'une tentative en message, ou en rien.
 *
 * ⚠️ La réussite ne produit **aucun** message : la feuille se ferme, et c'est le retour à l'écran
 * déverrouillé qui informe. Un « déverrouillé ! » affiché sur une feuille qui disparaît est du
 * bruit, et sur un écran de saisie de secret, c'est du bruit qui reste à l'image.
 */
@Composable
private fun messageDeTentative(attempt: VaultAttempt?): String? = when (attempt) {
    null, VaultAttempt.Success -> null
    VaultAttempt.Wiped -> stringResource(R.string.vault_pin_wiped)
    is VaultAttempt.WrongSecret -> if (attempt.attemptsRemaining == null) {
        stringResource(R.string.vault_pass_wrong)
    } else {
        stringResource(R.string.vault_pin_wrong) + " " +
            stringResource(R.string.vault_pin_attempts_left, attempt.attemptsRemaining)
    }

    is VaultAttempt.Created -> if (attempt.isComplete) {
        null
    } else {
        stringResource(R.string.vault_convert_partial_fail, attempt.failed, attempt.encrypted + attempt.failed)
    }

    is VaultAttempt.LockedOut -> stringResource(
        R.string.common_error_with,
        "${(attempt.remainingMillis + MILLIS - 1) / MILLIS} s",
    )

    is VaultAttempt.CreatedButNotEncrypted ->
        stringResource(R.string.vault_convert_impossible, attempt.message.orEmpty())

    is VaultAttempt.Invalid -> stringResource(messageDeRefus(attempt.reason))

    is VaultAttempt.Failed -> attempt.message?.let { stringResource(R.string.common_error_with, it) }
        ?: stringResource(R.string.common_error)
}

/**
 * Referme la feuille quand la tentative a réussi.
 *
 * ⚠️ **Afficher PUIS consommer**, jamais l'inverse. Un `LaunchedEffect` dont la clé change par son
 * propre effet s'annule : consommer le porteur avant d'agir annulerait l'action. Ici, la
 * consommation est le dernier geste.
 */
@Composable
private fun ResultatDeTentative(attempt: VaultAttempt?, onSuccess: () -> Unit, onConsumed: () -> Unit) {
    LaunchedEffect(attempt) {
        // ⚠️ `Created` avec des échecs NE ferme PAS la feuille : l'utilisateur doit voir combien
        // de ses notes sont restées en clair dans un dossier qui affiche désormais un cadenas.
        val termine = attempt is VaultAttempt.Success || (attempt is VaultAttempt.Created && attempt.isComplete)
        if (termine) {
            onSuccess()
            onConsumed()
        }
    }
}

/**
 * 🔴 Ce que « le coffre existe deja » change a l'ecran.
 *
 * « Annuler » disparait : le proposer laisserait croire qu'on peut revenir en arriere, alors
 * qu'aucun retrait de protection n'est fait et que le dossier EST un coffre. Et le bouton principal
 * cesse de relancer une creation, qui echouerait sur `ALREADY_A_VAULT` et ecraserait le message qui
 * compte — celui qui dit combien de notes sont restees en clair.
 *
 * Releve en relisant les correctifs de relecture (GPT-5.2, 2026-08-14).
 */
@Composable
private fun BoutonDeFermeture(onDone: () -> Unit) {
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.common_close))
    }
}

/**
 * La chaine qui explique un refus de saisie.
 *
 * ⚠️ `when` **exhaustif** : ajouter une raison sans décider de ce qu'on en dit à l'utilisateur
 * doit échouer à la compilation. C'est exactement ce qui manquait — la raison existait, la chaîne
 * aussi, et rien ne reliait les deux.
 *
 * ⚠️ **Deux raisons n'ont PAS de chaîne dédiée** dans l'ARB de la version publiée, et je n'en
 * invente pas : une clé que la version Flutter n'a pas serait une divergence d'i18n à réconcilier en
 * phase 8. Elles retombent sur le message générique, ce qui reste infiniment mieux que du texte de
 * débogage.
 */
private fun messageDeRefus(reason: VaultValidationException.Reason): Int = when (reason) {
    VaultValidationException.Reason.PASSPHRASE_TOO_SHORT -> R.string.error_vault_passphrase_too_short
    VaultValidationException.Reason.PIN_LENGTH_OUT_OF_RANGE -> R.string.error_vault_pin_too_short
    VaultValidationException.Reason.PIN_NOT_DIGITS_ONLY -> R.string.error_vault_pin_not_digits
    VaultValidationException.Reason.ALREADY_A_VAULT -> R.string.error_vault_already_enabled
    VaultValidationException.Reason.NOT_A_VAULT -> R.string.error_vault_not_avault
    VaultValidationException.Reason.NOT_A_PIN_VAULT -> R.string.error_vault_not_pin_vault
    VaultValidationException.Reason.FOLDER_NOT_FOUND,
    VaultValidationException.Reason.NOT_A_PASSPHRASE_VAULT,
    -> R.string.common_error
}

private fun Modifier.clickableListItem(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

private val TOUCHES = listOf(
    charArrayOf('1', '2', '3'),
    charArrayOf('4', '5', '6'),
    charArrayOf('7', '8', '9'),
    charArrayOf(' ', '0', '\b'),
)

private val TAILLE_TOUCHE = 72.dp
private const val MILLIS = 1_000L
