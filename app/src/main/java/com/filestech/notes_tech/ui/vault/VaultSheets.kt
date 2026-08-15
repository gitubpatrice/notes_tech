package com.filestech.notes_tech.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.text.style.TextAlign
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
            // Un déverrouillage ne chiffre rien : le décompte ne le concerne pas.
            onDone = { onUnlocked() },
        )

        VaultMode.PASSPHRASE -> PassphraseSheet(
            folder = folder,
            creating = false,
            onDismiss = onDismiss,
            // Un déverrouillage ne chiffre rien : le décompte ne le concerne pas.
            onDone = { onUnlocked() },
        )

        VaultMode.UNKNOWN, null -> DamagedVaultSheet(onDismiss = onDismiss)
    }
}

/**
 * La mise en page commune aux feuilles de coffre à saisie : **elle défile, et elle laisse la place
 * au clavier**.
 *
 * ## 🔴 Ce que ce modificateur répare, mesuré sur le S9 le 2026-08-15
 *
 * La feuille de création de coffre à phrase secrète ne tenait pas au-dessus du clavier : titre,
 * texte d'explication, bannière d'avertissement, deux champs, deux boutons. Sans défilement, la
 * colonne était **coupée** — le second champ ne mesurait plus que 66 px de haut au lieu de 192, et
 * « Créer le coffre » comme « Annuler » se trouvaient hors écran, donc **inatteignables** tant que
 * le clavier restait ouvert.
 *
 * ⚠️ Ce n'est pas le défaut que l'audit de cohérence signalait la veille. Celui-là disait que le
 * champ serait *recouvert* faute d'`imePadding()` ; vérifié sur l'appareil, il ne l'est pas — la
 * feuille remonte d'elle-même. La question qui manquait n'était pas « le champ est-il visible ? »
 * mais « **la feuille entière reste-t-elle utilisable ?** ». Constater qu'un élément est visible ne
 * dit rien de ceux qui ont été poussés dehors.
 *
 * ⚠️ `imePadding()` **avant** le défilement dans la chaîne : la zone visible du défilement doit être
 * réduite par le clavier, sinon on peut défiler sous lui. Les insets consommés ne s'additionnent
 * pas — `navigationBarsPadding()` n'applique ensuite que ce que le clavier n'a pas déjà pris.
 *
 * ⚠️ Un `verticalScroll` sous une contrainte de hauteur **non bornée** plante. Ici la contrainte
 * vient de `ModalBottomSheet`, qui la borne — c'est la même règle que le panneau de liens de
 * l'éditeur, dans l'autre sens.
 */
@Composable
private fun Modifier.contenuDeFeuilleDeCoffre(): Modifier = this
    .imePadding()
    .navigationBarsPadding()
    .verticalScroll(rememberScrollState())

/**
 * L'état d'une feuille de coffre : **ouverte entière, jamais à mi-hauteur**.
 *
 * ## 🔴 Ce que `skipPartiallyExpanded` répare, vu par Patrice sur le S9 le 2026-08-15
 *
 * Par défaut, `ModalBottomSheet` s'ouvre **à demi** et attend qu'on la tire vers le haut. Sur la
 * feuille de code, le résultat était sans appel : **trois touches sur dix** étaient posées à
 * l'écran, les six autres et le bouton de validation en dehors. Le pavé n'est pas un clavier
 * système — il est dessiné dans l'application, donc rien ne pousse la feuille vers le haut comme le
 * fait le clavier sur un champ texte. Elle restait là où elle s'était ouverte.
 *
 * Le défilement ajouté juste au-dessus rend le contenu **atteignable** ; il ne le rend pas
 * **visible**. Ce sont deux questions différentes, et il fallait les deux : un pavé numérique dont
 * il faut deviner qu'on peut le faire défiler pour voir le chiffre 7 n'est pas utilisable.
 *
 * Les deux feuilles à saisie ouvrent donc en pleine hauteur. Le défilement reste utile pour les
 * petits écrans, où même la pleine hauteur ne suffit pas.
 */
@Composable
private fun etatDeFeuilleDeCoffre() = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
fun CreateVaultSheet(
    folder: Folder,
    mode: VaultMode,
    onDismiss: () -> Unit,
    /**
     * Appelé quand le coffre est en place **et son contenu chiffré**, avec le nombre de notes qui
     * viennent de l'être. C'est l'appelant qui l'annonce : la feuille disparaît, un message posé
     * dessus disparaîtrait avec elle.
     */
    onCreated: (chiffrees: Int) -> Unit,
    /**
     * 🔴 Appelé quand on quitte la feuille sur une conversion **incomplète**, partielle ou pas
     * commencée du tout.
     *
     * L'avertissement n'existait que dans la feuille : la refermer, par le bouton ou par un geste,
     * l'effaçait sans laisser de trace. Le dossier affiche alors un cadenas, tout ou partie de son
     * contenu est lisible au repos, et **plus rien nulle part ne le dit**. Relevé CONFIRMÉ par une
     * relecture externe (GPT-5.2, 2026-08-15).
     *
     * L'issue est passée telle quelle : c'est l'appelant qui choisit la phrase, là où il choisit
     * déjà celle de la réussite.
     */
    onConversionIncomplete: (VaultAttempt) -> Unit,
) {
    when (mode) {
        VaultMode.PIN -> PinSheet(
            folder = folder,
            creating = true,
            onDismiss = onDismiss,
            onDone = onCreated,
            onConversionIncomplete = onConversionIncomplete,
        )

        else -> PassphraseSheet(
            folder = folder,
            creating = true,
            onDismiss = onDismiss,
            onDone = onCreated,
            onConversionIncomplete = onConversionIncomplete,
        )
    }
}

// ── Phrase secrète ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun PassphraseSheet(
    folder: Folder,
    creating: Boolean,
    onDismiss: () -> Unit,
    onDone: (chiffrees: Int) -> Unit,
    onConversionIncomplete: (VaultAttempt) -> Unit = {},
) {
    // ⚠️ Le drapeau est forcé pour la durée de la feuille, **même si l'utilisateur l'a désactivé**.
    // Ce qui s'affiche ici est une phrase secrète en clair quand il choisit de la rendre visible ;
    // une capture, volontaire ou par une application de projection d'écran, la donnerait entière.
    SecureWindowGuard()

    val viewModel: VaultViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var secret by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var erreurLocale by remember { mutableStateOf<String?>(null) }

    val tropCourte = stringResource(R.string.vault_pass_min_length, VaultParams.PASSPHRASE_MIN_LENGTH)
    val discordance = stringResource(R.string.vault_pass_mismatch)

    ResultatDeTentative(state.attempt, onSuccess = onDone, onConsumed = viewModel::consumeAttempt)

    ModalBottomSheet(
        // ⚠️ Fermer la feuille ANNULE la dérivation en cours. Sans ça, le travail continue dans
        // `viewModelScope` et le coffre s'ouvre une seconde plus tard, sans que rien à l'écran
        // ne l'indique. Relevé par une relecture externe (GPT-5.2).
        onDismissRequest = {
            // 🔴 **On ne ferme PAS pendant le chiffrement du contenu.**
            //
            // Le coffre est déjà créé à ce stade. Fermer annulait la coroutine : le dossier restait
            // un coffre, ses notes restaient en clair, et la feuille disparaissait **sans rien
            // dire** — l'utilisateur croyant avoir annulé une création qui avait eu lieu. Relevé
            // CONFIRMÉ par une relecture externe (Gemini, 2026-08-15).
            //
            // L'application publiée répond pareil, avec un dialogue `barrierDismissible: false`
            // (`folders_drawer.dart:654`). Pendant la dérivation, en revanche, fermer annule
            // vraiment : rien n'a encore été écrit.
            if (viewModel.chiffrementEnCours()) return@ModalBottomSheet
            rapporterUneConversionIncomplete(state.attempt, onConversionIncomplete)
            viewModel.cancelAttempt()
            onDismiss()
        },
        sheetState = etatDeFeuilleDeCoffre(),
    ) {
        Column(
            modifier = Modifier
                .contenuDeFeuilleDeCoffre()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
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

            ChampDePhraseSecrete(
                valeur = secret,
                onValeurChange = {
                    secret = it
                    erreurLocale = null
                },
                label = stringResource(R.string.vault_pass_field),
                actionClavier = if (creating) ImeAction.Next else ImeAction.Done,
                actif = !state.busy,
            )
            if (creating) {
                ChampDePhraseSecrete(
                    valeur = confirmation,
                    onValeurChange = {
                        confirmation = it
                        erreurLocale = null
                    },
                    label = stringResource(R.string.vault_pass_confirm_field),
                    actionClavier = ImeAction.Done,
                    actif = !state.busy,
                )
            }

            MessageDEtat(
                message = erreurLocale ?: messageDeTentative(state.attempt),
                busy = state.busy,
                phase = state.phase,
            )

            if (state.attempt.coffreExiste()) {
                // 🔴 **`onDismiss`, PAS `onDone`.** On n'arrive ici qu'après une conversion
                // PARTIELLE — des notes sont restées en clair, et le message au-dessus vient de
                // le dire. Passer par le chemin de réussite ferait afficher « Coffre activé »
                // par-dessus, c'est-à-dire contredire l'avertissement qu'on vient de lire.
                BoutonDeFermeture {
                    rapporterUneConversionIncomplete(state.attempt, onConversionIncomplete)
                    viewModel.consumeAttempt()
                    onDismiss()
                }
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

/**
 * Un champ de phrase secrète, avec **son** œil.
 *
 * ## 🔴 Un seul composable pour les deux champs, et c'est le correctif
 *
 * La feuille de création portait deux `OutlinedTextField` écrits à la main : le premier avec son
 * `trailingIcon` de visibilité, **le second sans**. Saisir une phrase longue puis la confirmer à
 * l'aveugle, sans aucun moyen de relire ce qu'on vient de taper, sur un écran dont l'erreur coûte
 * un coffre irrécupérable. Relevé par Patrice sur le S9 le 2026-08-15.
 *
 * L'application publiée n'a jamais eu ce défaut : ses deux champs sont le **même** widget
 * (`PassphraseTextField`, `vault_passphrase_sheets.dart:145` et `:155`). La divergence est née en
 * portant deux fois à la main ce qui était factorisé une fois.
 *
 * D'où ce composable : un troisième champ ne peut plus naître sans son œil, parce qu'il n'y a plus
 * de chemin pour en écrire un à côté. C'est le motif du **jumeau asymétrique**, et la seule
 * correction qui tient est celle qui supprime le jumeau.
 *
 * ⚠️ La visibilité est propre à chaque champ, comme dans l'application publiée : révéler la
 * confirmation ne révèle pas la phrase au-dessus.
 */
@Composable
private fun ChampDePhraseSecrete(
    valeur: String,
    onValeurChange: (String) -> Unit,
    label: String,
    actionClavier: ImeAction,
    actif: Boolean,
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = valeur,
        onValueChange = onValeurChange,
        label = { Text(label) },
        singleLine = true,
        enabled = actif,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = actionClavier),
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
}

// ── Code à quatre-six chiffres ───────────────────────────────────────────────────────────────────

@Composable
private fun PinSheet(
    folder: Folder,
    creating: Boolean,
    onDismiss: () -> Unit,
    onDone: (chiffrees: Int) -> Unit,
    onConversionIncomplete: (VaultAttempt) -> Unit = {},
) {
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
        if (state.attempt != null) saisi = ""
    }

    // 🔴 **La clôture passe par le MÊME composable que la feuille à phrase secrète.** Elle était
    // recopiée à la main ici, avec sa propre condition : deux jumeaux à corriger ensemble, qui ont
    // failli diverger dès l'évolution suivante. Cf. [chiffreesSiTermine].
    ResultatDeTentative(state.attempt, onSuccess = onDone, onConsumed = viewModel::consumeAttempt)

    val enConfirmation = creating && confirmation != null

    ModalBottomSheet(
        // ⚠️ Fermer la feuille ANNULE la dérivation en cours. Sans ça, le travail continue dans
        // `viewModelScope` et le coffre s'ouvre une seconde plus tard, sans que rien à l'écran
        // ne l'indique. Relevé par une relecture externe (GPT-5.2).
        onDismissRequest = {
            // 🔴 **On ne ferme PAS pendant le chiffrement du contenu.**
            //
            // Le coffre est déjà créé à ce stade. Fermer annulait la coroutine : le dossier restait
            // un coffre, ses notes restaient en clair, et la feuille disparaissait **sans rien
            // dire** — l'utilisateur croyant avoir annulé une création qui avait eu lieu. Relevé
            // CONFIRMÉ par une relecture externe (Gemini, 2026-08-15).
            //
            // L'application publiée répond pareil, avec un dialogue `barrierDismissible: false`
            // (`folders_drawer.dart:654`). Pendant la dérivation, en revanche, fermer annule
            // vraiment : rien n'a encore été écrit.
            if (viewModel.chiffrementEnCours()) return@ModalBottomSheet
            rapporterUneConversionIncomplete(state.attempt, onConversionIncomplete)
            viewModel.cancelAttempt()
            onDismiss()
        },
        sheetState = etatDeFeuilleDeCoffre(),
    ) {
        Column(
            modifier = Modifier
                .contenuDeFeuilleDeCoffre()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
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

            // 🔴 **La visibilité retombe à chaque étape, et ce n'est pas une commodité.**
            //
            // `saisi` est vidé entre la première saisie et sa confirmation, et après chaque
            // tentative. Laisser l'œil ouvert d'une étape à l'autre afficherait en clair, sur un
            // écran qu'on peut lire par-dessus l'épaule, un code que l'utilisateur avait révélé
            // pour une saisie précédente. La clé du `remember` est donc l'étape elle-même.
            var codeVisible by remember(enConfirmation, state.attempt) { mutableStateOf(false) }

            PointsDeSaisie(saisi = saisi, visible = codeVisible, onBasculer = { codeVisible = !codeVisible })

            MessageDEtat(
                message = erreurLocale ?: messageDeTentative(state.attempt),
                busy = state.busy,
                phase = state.phase,
            )

            // 🔴 **Le même garde que la feuille à phrase secrète** — il n'était QUE là-bas.
            //
            // Après une conversion partielle, la feuille PIN gardait son clavier et son bouton
            // « Valider » actifs. L'utilisateur qui appuie dessus relance une création, qui échoue
            // sur « dossier déjà protégé » — et **ce refus écrase le message qui compte**, celui
            // qui dit combien de ses notes sont restées en clair sous un cadenas. Le seul moment
            // où on pouvait le lui dire, effacé par un geste que rien n'empêchait.
            //
            // Jumeau asymétrique relevé par une relecture externe (Gemini, 2026-08-15) : le garde
            // avait été écrit une fois, sur une seule des deux feuilles.
            if (state.attempt.coffreExiste()) {
                BoutonDeFermeture {
                    rapporterUneConversionIncomplete(state.attempt, onConversionIncomplete)
                    // ⚠️ **Consommer.** Ce ViewModel vit plus longtemps que la feuille : sans ça,
                    // l'issue survit et le message de conversion partielle réapparaît à l'ouverture
                    // de la feuille d'un AUTRE dossier. Relevé PROBABLE par une relecture externe
                    // (GPT-5.2), et vérifié : `hiltViewModel()` s'accroche à l'entrée de navigation.
                    viewModel.consumeAttempt()
                    onDismiss()
                }
                return@Column
            }

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
private fun MessageDEtat(message: String?, busy: Boolean, phase: PhaseDeCoffre) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                busy -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(
                            when (phase) {
                                PhaseDeCoffre.DERIVATION -> R.string.vault_pass_deriving
                                PhaseDeCoffre.CHIFFREMENT -> R.string.folder_convert_progress_title
                            },
                        ),
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

        // ⚠️ **Cette seconde ligne n'apparaît QUE pendant le chiffrement**, donc uniquement quand
        // toutes les commandes de la feuille sont déjà désactivées (`enabled = !state.busy`). Le
        // décalage de mise en page que le paragraphe ci-dessus interdit ne peut donc pas déplacer une
        // touche sous un doigt en route : il n'y a plus rien à toucher.
        if (busy && phase == PhaseDeCoffre.CHIFFREMENT) {
            Text(
                text = stringResource(R.string.folder_convert_progress_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PointsDeSaisie(saisi: String, visible: Boolean, onBasculer: () -> Unit) {
    val couleurs = MaterialTheme.colorScheme
    val annonce = stringResource(R.string.vault_pin_digits_announce, saisi.length, VaultParams.PIN_MAX_LENGTH)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics { contentDescription = annonce },
        ) {
            repeat(VaultParams.PIN_MAX_LENGTH) { index ->
                val chiffre = saisi.getOrNull(index)
                if (visible && chiffre != null) {
                    // ⚠️ Même largeur qu'une pastille : sans cela, la rangée change de longueur au
                    // basculement et le pavé numérique sautille sous les doigts.
                    Text(
                        text = chiffre.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = couleurs.primary,
                        modifier = Modifier.width(14.dp).clearAndSetSemantics { },
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(14.dp).clip(CircleShape).clearAndSetSemantics { },
                        shape = CircleShape,
                        color = if (index < saisi.length) couleurs.primary else couleurs.surfaceContainerHighest,
                    ) {}
                }
            }
        }
        IconButton(onClick = onBasculer) {
            Icon(
                imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = stringResource(
                    if (visible) R.string.pin_hide_tooltip else R.string.pin_show_tooltip,
                ),
            )
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
    // ⚠️ `vault_pin_wiped`, et **pas** `note_editor_error_vault_wiped`. Cette dernière reste
    // orpheline **par construction** : elle décrit l'auto-destruction vue depuis l'éditeur, or
    // `FolderVaultService.decrypt` ne lève jamais `VaultPinWipedException` — seuls les chemins de
    // déverrouillage le font, et ils aboutissent ici. Un `catch` de cette exception dans le
    // chargement de l'éditeur serait un chemin mort ; j'en avais écrit un le 2026-08-15, retiré le
    // jour même après vérification.
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
/**
 * Remonte une conversion **incomplète** avant que la feuille ne disparaisse.
 *
 * 🔴 **Deux issues, pas une.** Cette fonction ne traitait que la conversion partielle
 * ([VaultAttempt.Created] avec des échecs). Or [coffreExiste] — la condition qui mène ici — couvre
 * **aussi** [VaultAttempt.CreatedButNotEncrypted], c'est-à-dire le cas où le chiffrement n'a même
 * pas pu commencer : le dossier est un coffre et **toutes** ses notes sont en clair. Le fermer
 * effaçait ce message-là sans rien remonter. Relevé par une relecture externe (GPT-5.2, 2026-08-15),
 * qui a vu que le commentaire disait « partielle » là où le code en acceptait deux.
 *
 * ⚠️ Ne dit rien dans les autres cas — déverrouillage, mauvais secret, conversion complète : ceux-là
 * ont déjà leur propre retour, et en ajouter un second serait du bruit.
 */
private fun rapporterUneConversionIncomplete(attempt: VaultAttempt?, onIncomplete: (VaultAttempt) -> Unit) {
    when {
        attempt is VaultAttempt.Created && !attempt.isComplete -> onIncomplete(attempt)
        attempt is VaultAttempt.CreatedButNotEncrypted -> onIncomplete(attempt)
    }
}

/**
 * Le nombre de notes chiffrées **si cette issue clôt la feuille**, `null` si la feuille doit rester.
 *
 * 🔴 **Une seule définition pour les deux feuilles.** La feuille PIN portait sa propre copie de ce
 * calcul, écrite à la main dans son `LaunchedEffect` : deux jumeaux qu'il fallait penser à corriger
 * ensemble, et qui ont failli diverger dès la première évolution — celle qui fait remonter le
 * décompte. C'est le motif du **jumeau asymétrique**, et la correction qui tient est celle qui
 * supprime le jumeau, comme pour `ChampDePhraseSecrete`.
 *
 * ⚠️ `Created` avec des échecs rend `null` : la feuille NE se ferme pas, l'utilisateur doit voir
 * combien de ses notes sont restées en clair dans un dossier qui affiche désormais un cadenas.
 */
private fun VaultAttempt?.chiffreesSiTermine(): Int? = when {
    this is VaultAttempt.Created && isComplete -> encrypted
    this is VaultAttempt.Success -> 0
    else -> null
}

@Composable
private fun ResultatDeTentative(attempt: VaultAttempt?, onSuccess: (chiffrees: Int) -> Unit, onConsumed: () -> Unit) {
    val vue = LocalView.current
    val coffreOuvert = stringResource(R.string.home_announce_vault_unlocked)

    LaunchedEffect(attempt) {
        // ⚠️ `Created` avec des échecs NE ferme PAS la feuille : l'utilisateur doit voir combien
        // de ses notes sont restées en clair dans un dossier qui affiche désormais un cadenas.
        val chiffrees = attempt.chiffreesSiTermine()
        if (chiffrees != null) {
            // ⚠️ **Seul un DÉVERROUILLAGE s'annonce ici.** `Success` n'est produit que par les
            // chemins d'ouverture ; une conversion rend `Created`, et son propre message part de
            // l'accueil avec le décompte des notes chiffrées. Annoncer les deux dirait « coffre
            // déverrouillé » à quelqu'un qui vient d'en créer un.
            //
            // ⚠️ Se distinguer par `chiffrees == 0` aurait été faux : convertir un dossier VIDE rend
            // zéro lui aussi.
            //
            // Un lecteur d'écran ne voit pas une feuille se refermer sur un dossier devenu lisible.
            if (attempt is VaultAttempt.Success) {
                @Suppress("DEPRECATION")
                vue.announceForAccessibility(coffreOuvert)
            }
            onSuccess(chiffrees)
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
