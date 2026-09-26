package com.filestech.notes_tech.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import com.filestech.notes_tech.security.vault.VaultPinWipedException
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import com.filestech.notes_tech.ui.common.ClavierNumerique
import com.filestech.notes_tech.ui.common.PointsDeSaisie
import com.filestech.notes_tech.ui.common.displayName
import com.filestech.notes_tech.ui.common.refusalMessageFor
import com.filestech.notes_tech.ui.common.retryWaitMessage
import com.filestech.notes_tech.ui.secure.ProprietesDeFeuilleSecrete
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.theme.Formes

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
 *
 * ## 🔴🔴 [bloquer] — la garde de `onDismissRequest` NE SUFFIT PAS, mesuré
 *
 * Il y a **deux** façons de faire disparaître une `ModalBottomSheet`, et elles n'arrivent pas au
 * même endroit :
 *
 *  • **le balayage vers le bas** pousse l'état vers `Hidden` et n'appelle `onDismissRequest`
 *    qu'**une fois la feuille partie**. Une garde qui s'y contente de ne rien faire arrive après la
 *    bataille : la feuille a disparu de l'écran, et le chiffrement continue sans que rien ne le
 *    dise. C'est exactement le scénario que le dialogue publié interdit — et le portage l'avait
 *    rouvert ;
 *  • **le Retour** appelle `onDismissRequest` directement, sans passer par l'état. Là, et là
 *    seulement, la garde fonctionne.
 *
 * D'où deux mécanismes, pas un : ce paramètre refuse la transition vers `Hidden`, la garde de
 * `onDismissRequest` refuse l'action. Aucun des deux ne couvre le chemin de l'autre, et la paire
 * est exhaustive puisqu'il n'existe pas de troisième façon de fermer.
 *
 * ⚠️ **Une lambda, pas un booléen.** L'état n'est construit qu'une fois — `rememberSaveable` — donc
 * un booléen lu à la composition resterait figé sur sa valeur d'alors, et le veto ne s'activerait
 * jamais. Ce paramètre est appelé **à chaque tentative** de fermeture, et doit rester **pur** : il
 * décide, il n'agit pas.
 *
 * Mesuré sur le S9 par `FermetureDeFeuilleTest`, qui contient les cas témoins sans lesquels ces
 * quatre lignes ne seraient qu'une conviction. Deux relectures externes du 2026-08-16 s'étaient
 * contredites sur ce point ; c'est le test qui a tranché, et il a donné tort à la plus assurée des
 * deux.
 */
@Composable
private fun etatDeFeuilleDeCoffre(bloquer: () -> Boolean = { false }) = rememberModalBottomSheetState(
    skipPartiallyExpanded = true,
    confirmValueChange = { cible -> !(cible == SheetValue.Hidden && bloquer()) },
)

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
                // ⚠️ The second sentence (2026-09-25): removing the phone's screen lock deletes a PIN
                // vault's key — measured on API 34 — and no PIN opens the vault afterwards. Said HERE,
                // while a passphrase vault, which does not depend on it, can still be chosen.
                supportingContent = {
                    Text(
                        stringResource(R.string.vault_mode_pin_desc) + " " +
                            stringResource(R.string.vault_mode_pin_screen_lock),
                    )
                },
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

        // ⚠️ **[VaultMode.UNKNOWN] est énumérée, pas absorbée par un `else`.**
        //
        // Elle n'arrive pas ici : `ChooseVaultModeSheet` n'offre que les deux modes créables, et un
        // coffre à créer n'a par définition pas encore de matériel illisible. La brancher sur la
        // phrase secrète est donc un repli qui ne sert jamais.
        //
        // Ce qui justifie de l'écrire quand même, c'est le mode SUIVANT : sous `else`, un troisième
        // mode ajouté à l'énumération serait routé **en silence** vers la feuille de phrase secrète,
        // et l'utilisateur se verrait demander un secret qui n'est pas celui de son coffre. Énumérées,
        // les branches font échouer la **compilation** le jour où ça arrive. Relevé par une relecture
        // externe (GPT-5.2, 2026-08-15) comme chemin mort ; c'en est un, et c'est le seul endroit où
        // un chemin mort se garde — quand il transforme une régression future en erreur de build.
        VaultMode.PASSPHRASE, VaultMode.UNKNOWN -> PassphraseSheet(
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

    ResultatDeTentative(state.attempt, onSuccess = onDone, onConsumed = viewModel::consumeAttempt)

    FeuilleDePhraseSecrete(
        state = state,
        nomDuDossier = folder.displayName(),
        creating = creating,
        chiffrementEnCours = viewModel::chiffrementEnCours,
        // ⚠️ **Un seul rappel de sortie pour les trois chemins** — Retour, balayage, « Annuler ».
        // Ils étaient écrits deux fois, et différemment : la sortie par geste rapportait une
        // conversion incomplète, « Annuler » non. La différence était **sans effet**, et vérifiable :
        // « Annuler » n'existe que sous `!plusRienAEssayer()`, or les deux issues que
        // [rapporterUneConversionIncomplete] retient sont précisément celles de `coffreExiste()`,
        // que ce prédicat contient.
        // Deux jumeaux dont l'un était un sous-ensemble muet de l'autre.
        onQuitter = {
            rapporterUneConversionIncomplete(state.attempt, onConversionIncomplete)
            viewModel.cancelAttempt()
            onDismiss()
        },
        onValider = { secret ->
            if (creating) {
                viewModel.createPassphraseVault(folder.id, secret)
            } else {
                viewModel.unlockWithPassphrase(folder.id, secret)
            }
        },
        onFermerSurUneIssueFinale = {
            rapporterUneConversionIncomplete(state.attempt, onConversionIncomplete)
            viewModel.consumeAttempt()
            onDismiss()
        },
    )
}

/**
 * La feuille à phrase secrète **sans son ViewModel** : un état, des rappels, rien d'autre.
 *
 * ## 🔴 Ce que ce découpage rend mesurable, et qui ne l'était pas
 *
 * Quatre états de cette feuille **ne s'atteignent pas à la main** : une temporisation, une conversion
 * partielle, un coffre créé dont le contenu n'a pas pu être chiffré, et la phase de chiffrement
 * elle-même. Tant que l'état venait de Hilt et d'une base chiffrée, aucun test ne pouvait les poser.
 *
 * ⚠️ Ce paragraphe en annonçait **cinq**, et comptait « un coffre effacé après cinq échecs ». C'est
 * faux : `VaultPinWipedException` n'est levée que par `FolderVaultService.unlockWithPin` — une phrase
 * secrète ne détruit rien. La phrase avait été recopiée du jumeau. Relevé par une relecture externe
 * (GPT-5.2, 2026-08-18), qui l'a vu en constatant qu'elle **contredisait** le KDoc de
 * [plusRienAEssayer] quinze cents lignes plus bas.
 *
 * ⚠️⚠️ Et le seul test de ce fichier, `FermetureDeFeuilleTest`, mesurait une feuille **synthétique**
 * qui ne partageait avec celle-ci que quatre lignes **recopiées à la main**. Il a rendu un vrai
 * service — il a départagé deux relectures qui se contredisaient — mais il ne disait rien de *cette*
 * feuille-ci. C'est la même séparation que `HomeRoute`/`HomeScreen`, `TrashRoute`, `SearchRoute`,
 * `SettingsRoute` et `NoteEditorRoute`.
 *
 * ⚠️⚠️ [chiffrementEnCours] est passé en **fonction**, pas en booléen — pour **deux** raisons
 * distinctes qu'il vaut mieux ne pas confondre :
 *
 * 1. il est **lu à l'instant** où l'on tente de fermer, donc un booléen figerait le veto sur la
 *    valeur qu'il avait à la composition ;
 * 2. c'est `confirmValueChange` — la lambda qui le referme — qui sert de **clé au
 *    `rememberSaveable`** construisant l'état de la feuille ; une lambda instable recréerait donc cet
 *    état à chaque frappe. Une **référence de méthode liée** satisfait les deux points.
 *
 * ⚠️ La première rédaction de ce paragraphe disait que `chiffrementEnCours` **était** cette clé. C'est
 * faux : la clé est `confirmValueChange`. Relevé par une relecture externe (Gemini, 2026-08-18) —
 * troisième « commentaire qui ment » de ce dépôt, et le premier attrapé avant d'être commité.
 * Cf. `etatDeFeuilleDeCoffre` et `FermetureDeFeuilleTest`.
 */
@Composable
internal fun FeuilleDePhraseSecrete(
    state: VaultSheetState,
    nomDuDossier: String,
    creating: Boolean,
    chiffrementEnCours: () -> Boolean,
    onQuitter: () -> Unit,
    onValider: (String) -> Unit,
    onFermerSurUneIssueFinale: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var erreurLocale by remember { mutableStateOf<String?>(null) }

    val tropCourte = stringResource(R.string.vault_pass_min_length, VaultParams.PASSPHRASE_MIN_LENGTH)
    val discordance = stringResource(R.string.vault_pass_mismatch)
    val plusRienAEssayer = state.attempt.plusRienAEssayer()

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
            //
            // ⚠️ **Cette garde reste indispensable, et ne couvre que le Retour.** Mesuré sur le S9
            // le 2026-08-16 : le Retour arrive ici sans toucher à l'état, le balayage n'y arrive
            // qu'après avoir déjà fait disparaître la feuille. Le veto du balayage est posé sur
            // l'état ci-dessous. Cf. `FermetureDeFeuilleTest`.
            if (chiffrementEnCours()) return@ModalBottomSheet
            onQuitter()
        },
        sheetState = etatDeFeuilleDeCoffre(bloquer = chiffrementEnCours),
        properties = ProprietesDeFeuilleSecrete,
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
                    stringResource(R.string.vault_pass_unlock_body, nomDuDossier)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (creating) BanniereDAvertissement(stringResource(R.string.vault_pass_warning_lost))

            // 🔴 **Les champs disparaissent quand il n'y a plus rien à essayer.** Ils restaient
            // affichés sous un bouton « Fermer », à côté d'un message annonçant que des notes sont
            // restées en clair : une surface de saisie qui laisse croire qu'on peut réessayer, alors
            // que le seul geste offert est de partir. Cf. [plusRienAEssayer].
            if (!plusRienAEssayer) {
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
            }

            MessageDEtat(
                message = erreurLocale ?: messageDeTentative(state.attempt),
                busy = state.busy,
                phase = state.phase,
            )

            if (plusRienAEssayer) {
                // 🔴 **`onDismiss`, PAS `onDone`.** On n'arrive ici qu'après une conversion
                // PARTIELLE — des notes sont restées en clair, et le message au-dessus vient de
                // le dire. Passer par le chemin de réussite ferait afficher « Coffre activé »
                // par-dessus, c'est-à-dire contredire l'avertissement qu'on vient de lire.
                BoutonDeFermeture(onFermerSurUneIssueFinale)
            } else {
                Button(
                    shape = Formes.bouton,
                    onClick = {
                        erreurLocale = when {
                            secret.length < VaultParams.PASSPHRASE_MIN_LENGTH -> tropCourte
                            creating && secret != confirmation -> discordance
                            else -> null
                        }
                        if (erreurLocale != null) return@Button
                        onValider(secret)
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
                ActionDeDialogue(
                    texte = stringResource(R.string.common_cancel),
                    onClick = onQuitter,
                    modifier = Modifier.fillMaxWidth(),
                )
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

    ResultatDeTentative(state.attempt, onSuccess = onDone, onConsumed = viewModel::consumeAttempt)

    FeuilleDeCode(
        state = state,
        nomDuDossier = folder.displayName(),
        creating = creating,
        chiffrementEnCours = viewModel::chiffrementEnCours,
        // Même raison qu'à la feuille à phrase secrète : les trois chemins de sortie faisaient deux
        // gestes différents pour un effet identique.
        onQuitter = {
            rapporterUneConversionIncomplete(state.attempt, onConversionIncomplete)
            viewModel.cancelAttempt()
            onDismiss()
        },
        onValider = { code ->
            if (creating) viewModel.createPinVault(folder.id, code) else viewModel.unlockWithPin(folder.id, code)
        },
        onFermerSurUneIssueFinale = {
            rapporterUneConversionIncomplete(state.attempt, onConversionIncomplete)
            // ⚠️ **Consommer.** Ce ViewModel vit plus longtemps que la feuille : sans ça, l'issue
            // survit et le message de conversion partielle réapparaît à l'ouverture de la feuille
            // d'un AUTRE dossier. Relevé PROBABLE par une relecture externe (GPT-5.2), et vérifié :
            // `hiltViewModel()` s'accroche à l'entrée de navigation.
            viewModel.consumeAttempt()
            onDismiss()
        },
    )
}

/**
 * La feuille de code **sans son ViewModel**. Mêmes raisons que [FeuilleDePhraseSecrete] : les issues
 * qui comptent — coffre effacé, temporisation, conversion partielle — ne s'atteignent pas à la main.
 *
 * ⚠️ La machine à deux temps de la création (saisir, puis confirmer) **reste ici** : elle, on
 * l'atteint au doigt, et la sortir la rendrait moins fidèle sans rien rendre de mesurable.
 */
@Composable
internal fun FeuilleDeCode(
    state: VaultSheetState,
    nomDuDossier: String,
    creating: Boolean,
    chiffrementEnCours: () -> Boolean,
    onQuitter: () -> Unit,
    onValider: (String) -> Unit,
    onFermerSurUneIssueFinale: () -> Unit,
) {
    var saisi by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var erreurLocale by remember { mutableStateOf<String?>(null) }

    val tropCourt = stringResource(
        R.string.vault_pin_too_short,
        VaultParams.PIN_MIN_LENGTH,
        VaultParams.PIN_MAX_LENGTH,
    )
    val discordance = stringResource(R.string.vault_pin_mismatch)
    val plusRienAEssayer = state.attempt.plusRienAEssayer()

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
            //
            // ⚠️ **Cette garde reste indispensable, et ne couvre que le Retour.** Mesuré sur le S9
            // le 2026-08-16 : le Retour arrive ici sans toucher à l'état, le balayage n'y arrive
            // qu'après avoir déjà fait disparaître la feuille. Le veto du balayage est posé sur
            // l'état ci-dessous. Cf. `FermetureDeFeuilleTest`.
            if (chiffrementEnCours()) return@ModalBottomSheet
            onQuitter()
        },
        sheetState = etatDeFeuilleDeCoffre(bloquer = chiffrementEnCours),
        properties = ProprietesDeFeuilleSecrete,
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
                    text = stringResource(R.string.vault_pin_unlock_body, nomDuDossier),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // ⚠️ The screen lock warning is NOT here, and was for an hour (2026-09-25): a second
            // sentence in this banner made the sheet taller than the S9's screen, so it scrolled
            // between the two steps and the pad moved — the 2.0.9 defect the test below guards
            // against. It went to the mode chooser, where the choice it informs is made.
            if (creating) BanniereDAvertissement(stringResource(R.string.vault_pin_warning_wipe))

            // 🔴 **Les pastilles disparaissent quand il n'y a plus rien à essayer** — même raison
            // que les champs de la feuille jumelle : six pastilles vides sous « le coffre a été
            // effacé » proposent une saisie qui n'ira nulle part.
            if (!plusRienAEssayer) {
                // 🔴 **La visibilité retombe à chaque étape, et ce n'est pas une commodité.**
                //
                // `saisi` est vidé entre la première saisie et sa confirmation, et après chaque
                // tentative. Laisser l'œil ouvert d'une étape à l'autre afficherait en clair, sur un
                // écran qu'on peut lire par-dessus l'épaule, un code que l'utilisateur avait révélé
                // pour une saisie précédente. La clé du `remember` est donc l'étape elle-même.
                var codeVisible by remember(enConfirmation, state.attempt) { mutableStateOf(false) }

                PointsDeSaisie(
                    saisi = saisi,
                    longueurMax = VaultParams.PIN_MAX_LENGTH,
                    visible = codeVisible,
                    onBasculer = { codeVisible = !codeVisible },
                )
            }

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
            if (plusRienAEssayer) {
                BoutonDeFermeture(onFermerSurUneIssueFinale)
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
                shape = Formes.bouton,
                onClick = {
                    if (saisi.length !in VaultParams.PIN_MIN_LENGTH..VaultParams.PIN_MAX_LENGTH) {
                        erreurLocale = tropCourt
                        return@Button
                    }
                    when {
                        !creating -> onValider(saisi)
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
                        else -> onValider(saisi)
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (creating) R.string.common_validate else R.string.vault_pass_unlock_action))
            }
            ActionDeDialogue(
                texte = stringResource(R.string.common_cancel),
                onClick = onQuitter,
                modifier = Modifier.fillMaxWidth(),
            )
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
            ActionDeDialogue(
                texte = stringResource(R.string.common_close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
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
 * L'emplacement du message d'état — occupé **en permanence**, même vide, et **annoncé**.
 *
 * ## 🔴🔴 La hauteur réservée valait UNE ligne, et le commentaire disait qu'elle suffisait
 *
 * Sans hauteur réservée, l'apparition d'un message décale le pavé numérique vers le bas. Le doigt
 * est déjà en route : la touche visée à l'instant du contact n'est plus celle qu'on frappe. Sur un
 * écran qui détruit le coffre au cinquième essai, un décalage de mise en page est un défaut de
 * sécurité — l'application publiée l'a vécu (« on dirait que le clavier n'est pas tactile du tout »,
 * S24 FE, 2026-08-07) et réserve **deux** lignes suivant `textScaler`.
 *
 * ⚠️⚠️ Ici, la ligne blanche n'en réservait qu'**une**, et à la taille de texte par défaut les
 * messages tiennent sur une ligne : **le décalage est nul, donc invisible**. Mesuré sur le S9 le
 * 2026-08-18, `font_scale` à **2,0** — le réglage d'accessibilité, exactement le public de cette
 * passe — la touche « 5 » descend de **96 px (32 dp)** entre un état sans message et « PIN
 * incorrect. Tentatives restantes : 3 », soit 40 % du pas entre deux touches. La garde existait,
 * elle était **dimensionnée sur la seule taille de texte que son auteur avait sous les yeux**.
 *
 * ⚠️ **Un minimum, pas une hauteur fixe.** L'application publiée fige la hauteur et tronque à deux
 * lignes (`maxLines: 2`, ellipse). À 200 %, « Trop de tentatives — le coffre a été effacé. » prend
 * trois lignes : la figer reviendrait à **amputer la phrase qui annonce la destruction**. Le pavé,
 * lui, n'est plus là à ce moment — cf. [plusRienAEssayer]. Réserver deux lignes couvre donc tous les
 * messages devant lesquels on retape encore, sans en tronquer aucun.
 *
 * ## 🔴🔴 Rien de tout cela n'était annoncé
 *
 * Mesuré le 2026-08-18 : **aucun nœud de ces deux feuilles ne portait de région active**, dans
 * aucun état — ni « PIN incorrect, 3 tentatives restantes », ni « le coffre a été effacé », ni la
 * dérivation en cours. L'application publiée annonce les trois (`Semantics(liveRegion: true)` sur le
 * témoin d'activité des deux feuilles, `SemanticsService.announce` sur l'effacement). Quelqu'un qui
 * n'a pas l'écran voyait donc son coffre détruit **sans un mot**.
 *
 * ⚠️ `Assertive` : ces messages interrompent ce qui est en train d'être lu, et c'est voulu. Même
 * choix qu'à l'issue du mode panique, pour la même raison.
 *
 * ⚠️ La région n'est posée **que s'il y a quelque chose à dire** : sur l'emplacement vide, elle
 * ferait annoncer le silence à chaque effacement de message.
 */
@Composable
private fun MessageDEtat(message: String?, busy: Boolean, phase: PhaseDeCoffre) {
    val aQuelqueChoseADire = busy || message != null
    // ⚠️ `toDp()` exige une hauteur de ligne en `sp` — c'est le cas de `bodySmall`, dont le thème ne
    // redéfinit que la taille. **Ne pas « durcir » ceci en repli sur une valeur en `dp` :** un repli
    // fixe cesserait de suivre la taille de texte du système, c'est-à-dire réintroduirait en silence
    // le défaut que cette ligne répare. Un plantage en développement vaut mieux qu'un repli du
    // mauvais côté.
    val deuxLignes = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() * 2 }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(min = deuxLignes)
            .testTag(EMPLACEMENT_DU_MESSAGE)
            .semantics(mergeDescendants = true) {
                if (aQuelqueChoseADire) liveRegion = LiveRegionMode.Assertive
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )

                // Rien : c'est la hauteur minimale du conteneur qui tient la place, et elle suit la
                // taille de texte du système. La ligne blanche qu'il y avait ici n'en réservait
                // qu'une, et se laissait déborder dès que l'utilisateur agrandissait le texte.
                else -> Unit
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
    // Exhaustive on the reason: a new way to lose a vault cannot compile without its sentence. The
    // invalidated key used to read "Too many attempts" — to someone who had typed one correct PIN.
    is VaultAttempt.Wiped -> stringResource(
        when (attempt.reason) {
            VaultPinWipedException.Reason.TOO_MANY_ATTEMPTS -> R.string.vault_pin_wiped
            VaultPinWipedException.Reason.KEY_INVALIDATED -> R.string.vault_pin_wiped_key_invalidated
        },
    )

    VaultAttempt.KeyMissing -> stringResource(R.string.vault_pin_key_missing)
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

    // "Too many attempts. Try again in 12 seconds." — it read "Error: 12 s" through
    // `common_error_with`, which notes_tech 2.0.9 removed. See `retryWaitMessage`.
    is VaultAttempt.LockedOut -> retryWaitMessage(attempt.remainingMillis)

    is VaultAttempt.CreatedButNotEncrypted ->
        stringResource(R.string.vault_convert_impossible, stringResource(attempt.message))

    is VaultAttempt.Invalid -> stringResource(refusalMessageFor(attempt.reason))

    is VaultAttempt.Failed -> stringResource(attempt.message)
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
/**
 * **Il n'y a plus rien à essayer sur cette feuille** : ni pavé, ni pastilles, ni champ de saisie, ni
 * « Annuler » — seulement « Fermer ».
 *
 * ⚠️ Cette phrase a d'abord été **fausse pour la feuille à phrase secrète**, dont les deux champs
 * restaient affichés sous le bouton « Fermer », à côté d'un message annonçant que des notes sont
 * restées en clair. Relevé par une relecture externe (GPT-5.2, 2026-08-18) : le code a été mis
 * d'accord avec le commentaire, sur les deux feuilles.
 *
 * ## 🔴 Le jumeau que le correctif de la conversion partielle n'avait pas couvert
 *
 * Ce garde n'existait que pour [coffreExiste] — un coffre créé dont le contenu n'est pas
 * entièrement chiffré. La raison en était : *relancer l'action échouerait, et ce refus écraserait le
 * message qui compte*. Or [VaultAttempt.Wiped] a **exactement** cette forme, et n'était pas couvert :
 * après cinq codes faux, le coffre est détruit, et le portage laissait le pavé, « Valider » et
 * « Annuler » actifs. Retaper un code sur un coffre qui n'existe plus fait remonter « ce dossier
 * n'est pas un coffre » **par-dessus** la seule phrase qui disait que les notes ont été effacées.
 *
 * L'application publiée retire son pavé sur ce chemin (`vault_pin_sheets.dart:565`) et remplace
 * « Annuler » par « Fermer ». Le portage avait transposé le garde une fois sur deux — le motif du
 * jumeau asymétrique, pour la troisième fois dans ce fichier.
 *
 * ⚠️ Posé sur les **deux** feuilles, bien que [VaultAttempt.Wiped] ne puisse pas naître d'une phrase
 * secrète : un garde écrit sur une seule des deux est précisément ce qui a produit les deux
 * précédents.
 *
 * [VaultAttempt.KeyMissing] too (2026-09-25): the vault still exists, but its key cannot be found, and
 * no PIN opens it without the key. Leaving the pad there would invite retries that change nothing.
 */
internal fun VaultAttempt?.plusRienAEssayer(): Boolean =
    coffreExiste() || this is VaultAttempt.Wiped || this == VaultAttempt.KeyMissing

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
    Button(onClick = onDone, shape = Formes.bouton, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.common_close))
    }
}

private fun Modifier.clickableListItem(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/**
 * L'emplacement du message, nommé pour être **mesuré**. Sa hauteur est la garde de §87 : c'est elle
 * qu'un test compare à la hauteur d'une ligne, et il n'y a pas d'autre façon de la voir — vide, cet
 * emplacement n'affiche rien.
 */
internal const val EMPLACEMENT_DU_MESSAGE = "emplacement-du-message-de-coffre"
