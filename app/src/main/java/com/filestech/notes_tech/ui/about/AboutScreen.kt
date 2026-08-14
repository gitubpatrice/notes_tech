package com.filestech.notes_tech.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.BuildConfig
import com.filestech.notes_tech.R

/**
 * L'écran « à propos ».
 *
 * ⚠️ **La version vient de `BuildConfig`, jamais d'une constante recopiée.** Une constante de
 * version dupliquée dérive au premier oubli de bump, et l'écran affiche alors une version que
 * personne n'a publiée. `BuildConfig.VERSION_NAME` vient de `version.properties`, source unique.
 */
@Composable
fun AboutRoute(onBack: () -> Unit, onOpenLegal: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_close),
                        )
                    }
                },
                title = { Text(stringResource(R.string.about_title)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.app_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp).semantics { heading() },
            )
            Text(
                text = BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 16.dp),
            )

            CarteDeConfidentialite()

            ListItem(
                headlineContent = { Text(stringResource(R.string.legal_title)) },
                leadingContent = { Icon(Icons.Outlined.Gavel, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenLegal),
            )
        }
    }
}

/**
 * La promesse de confidentialité, affichée telle qu'elle est annoncée publiquement.
 *
 * ⚠️ Ce texte est **une promesse vérifiable**, pas un argument commercial : l'absence de permission
 * réseau est contrôlée par la CI sur le manifeste fusionné. Le modifier sans que le manifeste
 * change ferait mentir l'écran.
 */
@Composable
private fun CarteDeConfidentialite() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.about_privacy_card_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.about_privacy_1),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Les mentions légales, texte long et statique. */
@Composable
fun LegalRoute(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_close),
                        )
                    }
                },
                title = { Text(stringResource(R.string.legal_title)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.legal_tab_terms),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.about_legal_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Text(
                text = stringResource(R.string.legal_tab_privacy),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.about_privacy_2),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
            )
        }
    }
}
