package com.filestech.notes_tech.ui.editor

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.links.TitleNormalizer
import com.filestech.notes_tech.domain.model.Note
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
    suggestions: List<Note>,
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

    LaunchedEffect(Unit) {
        // ⚠️ **Réémettre la requête restaurée.**
        //
        // `saisie` survit à une mort de processus (`rememberSaveable`), mais la requête du ViewModel
        // ne survit pas : elle repart vide. La feuille revenait donc avec le titre à l'écran et
        // **zéro suggestion**, ce qui rend `proposerLaCreation` vrai — et proposait de créer une
        // note qui existe peut-être déjà, exactement ce que [valider] cherche à éviter en consultant
        // les suggestions. Relevé par la relecture externe du 2026-08-15.
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
                    onRequeteChange(it)
                },
                label = { Text(stringResource(R.string.link_autocomplete_hint)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Valider au clavier prend le titre tapé tel quel — jamais la première suggestion
                // approchante. Le détail de ce que « tel quel » veut dire quand ce titre existe déjà
                // est dans [valider], et il diverge de l'application publiée.
                keyboardActions = KeyboardActions(onDone = { valider(saisie, suggestions, onChoisirUnTitre, onCreer) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(12.dp))

            val requete = saisie.trim()
            val proposerLaCreation = requete.isNotEmpty() && aucuneCorrespondanceExacte(suggestions, requete)

            if (suggestions.isEmpty() && !proposerLaCreation) {
                Text(
                    text = stringResource(R.string.link_autocomplete_empty),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = HAUTEUR_MAXIMALE_LISTE_DE_CHOIX)) {
                    items(items = suggestions, key = { it.id }) { note ->
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
                    if (proposerLaCreation) {
                        item(key = CLE_CREATION) {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(R.string.link_autocomplete_create_new, requete))
                                },
                                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                                modifier = Modifier.clickable { onCreer(requete) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun valider(
    saisie: String,
    suggestions: List<Note>,
    onChoisirUnTitre: (String) -> Unit,
    onCreer: (String) -> Unit,
) {
    val requete = saisie.trim()
    if (requete.isEmpty()) return
    // ⚠️ Si le titre tapé existe **déjà**, on le lie au lieu d'en créer un second du même nom.
    // L'application publiée crée dans tous les cas (`_onSubmit` ne consulte pas les suggestions),
    // ce qui produit deux notes homonymes et un lien qui n'en désigne qu'une — celle que l'ordre de
    // tri décide. Divergence délibérée.
    val existante = suggestions.firstOrNull { correspondExactement(it.title, requete) }
    if (existante != null) onChoisirUnTitre(existante.title) else onCreer(requete)
}

private fun aucuneCorrespondanceExacte(suggestions: List<Note>, requete: String): Boolean =
    suggestions.none { correspondExactement(it.title, requete) }

private fun correspondExactement(titre: String, requete: String): Boolean =
    TitleNormalizer.normalize(titre) == TitleNormalizer.normalize(requete)

private const val CLE_CREATION = "creation"
