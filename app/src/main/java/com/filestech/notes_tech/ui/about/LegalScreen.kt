package com.filestech.notes_tech.ui.about

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.markdown.LinkTarget
import com.filestech.notes_tech.ui.common.ouvrirUnLienExterne
import com.filestech.notes_tech.ui.editor.apercuMarkdown
import com.filestech.notes_tech.ui.editor.rememberLectureDeLApercu
import kotlinx.coroutines.launch

/**
 * Les mentions légales : **le vrai texte**, dans les deux langues.
 *
 * ## 🔴 Ce que cet écran affichait avant le 2026-08-15
 *
 * Deux phrases d'une ligne, empruntées à l'écran « à propos » : le sous-titre de sa ligne de menu,
 * et **un badge de sa carte de confidentialité**. Une page annoncée « mentions légales complètes »
 * qui affichait « Aucun compte, aucune inscription » en guise de politique de confidentialité.
 *
 * L'application publiée rend quatre fichiers Markdown (`assets/legal/{PRIVACY,TERMS}.{fr,en}.md`).
 * Ils sont ici dans `res/raw` et `res/raw-fr`, copiés octet pour octet : c'est un **texte
 * juridique**, il ne se reformule pas en portant.
 *
 * ⚠️ Le choix de la langue passe par la résolution de ressources d'Android, pas par une condition
 * dans le code. Le portage suit ainsi le réglage de langue de l'application — y compris quand
 * l'utilisateur le change dans les réglages, ce qui recrée l'activité.
 */
@Composable
fun LegalRoute(onBack: () -> Unit) {
    var onglet by rememberSaveable { mutableIntStateOf(0) }
    val titres = listOf(R.string.legal_tab_privacy, R.string.legal_tab_terms)
    val messages = remember { SnackbarHostState() }
    val portee = rememberCoroutineScope()
    val contexte = LocalContext.current
    val aucuneApplication = stringResource(R.string.note_preview_link_no_app)
    val onLien: (LinkTarget) -> Unit = { cible ->
        when (cible) {
            // As in the note preview: another app opens it, and a tap that can do nothing says so.
            is LinkTarget.Web -> if (!ouvrirUnLienExterne(contexte, cible.url)) {
                portee.launch { messages.showSnackbar(aucuneApplication) }
            }
            // A legal page holds no `[[…]]` outside code, where it is text — `PagesLegalesTest`
            // reads the four files to keep it so: there is no note to open from here.
            is LinkTarget.Note -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.legal_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(messages) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = onglet) {
                titres.forEachIndexed { index, titre ->
                    Tab(
                        selected = onglet == index,
                        onClick = { onglet = index },
                        text = { Text(stringResource(titre)) },
                    )
                }
            }
            TexteLegal(if (onglet == 0) R.raw.privacy else R.raw.terms, onLien)
        }
    }
}

/**
 * A Markdown file of `res/raw`, drawn as the note preview draws a note (D-024): headings, lists,
 * quotes, emphasis, rules and links, parsed off the main thread, laid out lazily — the file itself,
 * a few KB, is read with the composition. 2.0.9 renders these pages with a Markdown widget too,
 * `selectable` so that a passage can be copied — hence the [SelectionContainer], which, around a lazy
 * list as in 2.0.9, selects within what is laid out. Links open in another app, as in the preview.
 *
 * Until 2026-09-25, this screen drew its own minimal Markdown, line by line. It had shown the MIT
 * licence of `whisper.cpp` with its `>` markers while its comment said these files held no quote;
 * the preview's reader covers CommonMark and GFM instead of a counted subset.
 */
@Composable
private fun TexteLegal(@RawRes fichier: Int, onLien: (LinkTarget) -> Unit) {
    val ressources = LocalResources.current
    val texte = remember(fichier) {
        ressources.openRawResource(fichier).bufferedReader().use { it.readText() }
    }
    val lecture = rememberLectureDeLApercu(texte, actif = true)
    val barreDeNavigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + barreDeNavigation),
        ) {
            apercuMarkdown(lecture, onLien)
        }
    }
}
