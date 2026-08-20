package com.filestech.notes_tech.ui.about

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R

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
            TexteLegal(if (onglet == 0) R.raw.privacy else R.raw.terms)
        }
    }
}

/**
 * Rend un fichier Markdown de `res/raw`.
 *
 * ## Un rendu volontairement minimal, et ce qu'il couvre
 *
 * Titres `#` à `###`, listes à puces, **citations**, gras `**…**`, paragraphes, lignes horizontales.
 * Embarquer une bibliothèque de rendu Markdown pour quatre pages statiques coûterait plus qu'elle
 * ne rapporte, et ajouterait une dépendance à une application qui en compte peu.
 *
 * ## 🔴 Ce commentaire a menti, et il faut savoir pourquoi
 *
 * Il affirmait : « tableaux, liens, images, code — ce qui n'est pas géré est aussi ce qui n'apparaît
 * pas dans ces fichiers, **vérifié en les lisant, pas supposé** ». C'était faux : `terms.md` contient
 * **20 lignes de citation** dans ses deux langues — la licence MIT de `whisper.cpp`, que cette
 * licence **exige** de reproduire — et un lien vers le dépôt. Elles tombaient dans la branche par
 * défaut et s'affichaient **chevron compris** : `> MIT License`, `> Copyright (c)…`.
 *
 * *Une affirmation de vérification est une affirmation comme une autre : elle se vérifie. Un
 * `grep -c '^>'` sur les quatre fichiers y suffisait, et personne ne l'avait fait.* Repéré en
 * lisant l'écran sur un téléphone, le 2026-08-20.
 *
 * ⚠️ Restent non gérés, et cette fois **comptés** : tableaux (0 occurrence), images (0), blocs de
 * code (0). Le lien unique des CGU s'affiche en clair, ce qui convient — une URL lisible reste une
 * URL recopiable, et l'application n'a pas la permission Internet pour l'ouvrir de toute façon.
 */
@Composable
private fun TexteLegal(@RawRes fichier: Int) {
    val ressources = LocalResources.current
    val lignes = remember(fichier) {
        ressources.openRawResource(fichier).bufferedReader().use { it.readText() }.lines()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
    ) {
        lignes.forEach { ligne ->
            val nette = ligne.trim()
            when {
                nette.isEmpty() -> Column(Modifier.padding(top = 6.dp)) {}

                nette.startsWith("### ") -> Titre(nette.removePrefix("### "), MaterialTheme.typography.titleSmall)
                nette.startsWith("## ") -> Titre(nette.removePrefix("## "), MaterialTheme.typography.titleMedium)
                nette.startsWith("# ") -> Titre(nette.removePrefix("# "), MaterialTheme.typography.titleLarge)

                // Une ligne horizontale Markdown : on ne dessine rien, on espace. Un trait plein
                // entre deux paragraphes juridiques alourdit sans rien séparer que le blanc ne
                // sépare déjà.
                nette.all { it == '-' } && nette.length >= 3 -> Column(Modifier.padding(top = 12.dp)) {}

                // Citation : la licence MIT reproduite dans les conditions d'utilisation.
                // ⚠️ Testé avant les puces : une ligne `> - quelque chose` est d'abord une citation.
                nette.startsWith(">") -> {
                    val cite = nette.removePrefix(">").trim()
                    if (cite.isEmpty()) {
                        // `>` seul sépare deux paragraphes de la citation : c'est un blanc, pas un vide.
                        Column(Modifier.padding(top = 6.dp)) {}
                    } else {
                        Text(
                            text = enrichir(cite),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                        )
                    }
                }

                nette.startsWith("- ") || nette.startsWith("* ") -> Text(
                    text = enrichir("•  " + nette.drop(2)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )

                else -> Text(
                    text = enrichir(nette),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Titre(texte: String, style: androidx.compose.ui.text.TextStyle) {
    Text(
        text = texte,
        style = style.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * Applique le gras `**…**` et retire ses marqueurs.
 *
 * ⚠️ Un nombre **impair** de délimiteurs laisse le dernier segment en maigre plutôt que d'ouvrir un
 * gras qui ne se referme jamais. Un texte juridique mal balisé doit rester lisible.
 */
private fun enrichir(ligne: String): AnnotatedString = buildAnnotatedString {
    val morceaux = ligne.split("**")
    morceaux.forEachIndexed { index, morceau ->
        val enGras = index % 2 == 1 && index < morceaux.size - 1
        if (enGras) {
            withStyleGras(morceau)
        } else {
            append(morceau)
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleGras(texte: String) {
    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
    append(texte)
    pop()
}
