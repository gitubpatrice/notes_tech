package com.filestech.notes_tech.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.HAUTEUR_MAXIMALE_LISTE_DE_CHOIX

/**
 * La feuille qui propose un titre à lier, et la création si aucun ne convient.
 *
 * ## Ce que cette feuille ne décide pas
 *
 * Elle ne filtre rien et ne normalise rien pour son propre compte, sauf pour répondre à une seule
 * question : « faut-il proposer de créer ? ». Les suggestions viennent du ViewModel, qui les tient
 * d'une requête où l'exclusion des notes de coffre verrouillé est écrite — pas d'un filtre d'écran.
 *
 * ## ⚠️ Le défilement est borné, donc permis
 *
 * Contrairement au panneau de liens, qui vit dans une colonne défilante et ne doit donc porter aucun
 * défilement, cette liste-ci vit dans une feuille dont la hauteur est bornée par [heightIn]. Un
 * `LazyColumn` y reçoit une contrainte finie et se mesure normalement. La différence n'est pas de
 * goût : c'est la contrainte reçue qui décide, et elle se lit chez le parent.
 */
@Composable
fun FeuilleDAutocompletion(
    suggestions: SuggestionsDeLien,
    onRequeteChange: (String) -> Unit,
    onChoisirUnTitre: (String) -> Unit,
    onCreer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // ⚠️ `rememberSaveable` : une rotation ne doit pas vider le titre à moitié tapé. Le
    // ViewModel garde la requête de son côté, et les deux se retrouveraient sinon en désaccord —
    // des suggestions affichées sous un champ redevenu vide.
    var saisie by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    val etat = etatDAutocompletion(saisie, suggestions)
    val chargement = stringResource(R.string.common_loading)

    // 🔴 **Une validation retenue, le temps que la réponse arrive.**
    //
    // Valider au clavier demande de savoir si le titre tapé existe déjà — sinon on crée un homonyme,
    // ce que ce portage refuse exprès. Dans les 120 ms qui suivent une frappe, on ne le sait pas.
    // Ignorer la touche ferait d'« Entrée » un geste sans effet ; agir quand même la ferait toujours
    // créer. Elle est donc **retenue**, et appliquée dès que la réponse répond à la saisie.
    //
    // ⚠️ `remember` et **non** `rememberSaveable` : une validation qui survivrait à une mort de
    // processus se déclencherait au retour, sans que personne n'ait rien demandé. La fenêtre qu'elle
    // couvre est de 120 ms ; elle n'a rien à faire dans un `Bundle`.
    var validationEnAttente by remember { mutableStateOf(false) }

    val appliquer = { decision: DecisionDeValidation ->
        when (decision) {
            is DecisionDeValidation.Lier -> onChoisirUnTitre(decision.titre)
            is DecisionDeValidation.Creer -> onCreer(decision.titre)
            // ⚠️ `Attendre` est le seul cas qui **retient** ; `Rien` ne retient pas, sans quoi une
            // validation sur un champ vide se déclencherait à la première lettre tapée ensuite.
            DecisionDeValidation.Attendre -> validationEnAttente = true
            DecisionDeValidation.Rien -> Unit
        }
    }

    // ⚠️⚠️ **Remettre le drapeau à zéro AVANT d'agir** : une validation vaut une action, et pas deux.
    // C'est la règle §65 du dépôt — un événement qui **agit** doit avoir lieu une fois et pas deux.
    LaunchedEffect(validationEnAttente, etat.enAttente) {
        if (!validationEnAttente || etat.enAttente) return@LaunchedEffect
        validationEnAttente = false
        appliquer(decisionDeValidation(etat))
    }

    LaunchedEffect(Unit) {
        // ⚠️ **Réémettre la requête restaurée.**
        //
        // `saisie` survit à une mort de processus (`rememberSaveable`), mais la requête du ViewModel
        // ne survit pas : elle repart vide. La feuille revenait donc avec le titre à l'écran et
        // **zéro suggestion**, ce qui rend `proposerLaCreation` vrai — et proposait de créer une
        // note qui existe peut-être déjà, exactement ce que [decisionDeValidation] cherche à éviter en
        // consultant les suggestions. Relevé par la relecture externe du 2026-08-15.
        //
        // ⚠️ Depuis que la réponse porte sa question, ce réamorçage a une **seconde** raison, plus
        // forte : sans lui la feuille resterait indéfiniment « en attente » — sa saisie restaurée
        // n'ayant jamais été posée, aucune réponse ne lui répondrait jamais.
        if (saisie.isNotEmpty()) onRequeteChange(saisie)

        // ⚠️ Le focus est demandé **après** que la feuille a été posée. Le contenu d'un
        // `ModalBottomSheet` vit dans sa propre fenêtre, animée : demander le focus à la toute
        // première image peut viser un champ pas encore placé — au mieux le clavier ne s'ouvre pas,
        // au pire `FocusRequester` lève « is not initialized ». Signalé par la relecture externe du
        // 2026-08-15 ; un tour de boucle suffit à laisser la fenêtre s'installer.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.link_autocomplete_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = saisie,
                onValueChange = {
                    saisie = it
                    // ⚠️ Toute frappe **annule** une validation retenue : elle portait sur un autre
                    // titre que celui qui est maintenant à l'écran.
                    validationEnAttente = false
                    onRequeteChange(it)
                },
                label = { Text(stringResource(R.string.link_autocomplete_hint)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Valider au clavier prend le titre tapé tel quel — jamais la première suggestion
                // approchante. Le détail de ce que « tel quel » veut dire quand ce titre existe déjà
                // est dans [decisionDeValidation], et il diverge de l'application publiée.
                keyboardActions = KeyboardActions(onDone = { appliquer(decisionDeValidation(etat)) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(12.dp))

            when {
                // 🔴 **Tant que la réponse n'est pas là, on ne dit rien de ce qu'elle contiendra.**
                // Ni « Aucun résultat », ni « Créer *X* » : les deux affirment quelque chose sur une
                // recherche qui n'a pas eu lieu. C'est le motif §76, sur un autre écran.
                etat.enAttente -> Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = chargement })
                }

                // A failed search offers nothing, creation included: it saw nothing, so it cannot tell
                // whether the note exists. Enter does nothing either — this line says why.
                etat.annoncerLEchec -> Text(
                    text = stringResource(R.string.link_autocomplete_failed),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )

                etat.annoncerAucunResultat -> Text(
                    text = stringResource(R.string.link_autocomplete_empty),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = HAUTEUR_MAXIMALE_LISTE_DE_CHOIX)) {
                    items(items = etat.titres, key = { it.id }) { note ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = note.title.ifEmpty { stringResource(R.string.note_untitled) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null)
                            },
                            modifier = Modifier.clickable { onChoisirUnTitre(note.title) },
                        )
                    }
                    if (etat.proposerLaCreation) {
                        item(key = CLE_CREATION) {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(R.string.link_autocomplete_create_new, etat.requete))
                                },
                                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                                modifier = Modifier.clickable { onCreer(etat.requete) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private const val CLE_CREATION = "creation"
