package com.filestech.notes_tech.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R

/**
 * Ce que voit un utilisateur dont la base ne s'ouvre pas.
 *
 * ## Cet écran porte une promesse, et elle doit être vraie
 *
 * Il affirme que les notes sont toujours là et n'ont pas été modifiées. Cette phrase n'est
 * acceptable que parce que
 * [com.filestech.notes_tech.security.kek.KekRepository] ne détruit et ne réécrit rien sur les
 * chemins d'échec. Si un jour un correctif introduit une remise à zéro « pour débloquer », cette
 * phrase devient un mensonge — et un message qui ment sur l'état des données est pire que pas de
 * message du tout.
 *
 * ## Pourquoi le message part du geste
 *
 * Chaque cause est traduite en **ce que l'utilisateur peut faire**, pas en ce qui a échoué. « Le
 * déchiffrement a échoué » ne mène nulle part ; « installez d'abord la 2.0.4, ouvrez-la une fois »
 * mène quelque part.
 */
@Composable
fun StartupFailureScreen(reason: FailureReason, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Défilement : le texte peut dépasser sur un petit écran en très grande police,
                // et un message d'erreur tronqué qu'on ne peut pas faire défiler laisse
                // l'utilisateur sans la partie qui lui dit quoi faire.
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.startup_failure_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.startup_failure_notes_are_safe),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(reason.messageRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.startup_failure_retry))
            }
        }
    }
}

/**
 * Un `when` exhaustif sur l'énumération, sans branche `else`.
 *
 * Délibéré : ajouter une cause d'échec sans écrire son message devient une **erreur de
 * compilation**, au lieu de produire silencieusement un écran qui affiche le mauvais conseil.
 */
private val FailureReason.messageRes: Int
    get() = when (this) {
        FailureReason.MISSING_KEY -> R.string.startup_failure_missing_key
        FailureReason.KEY_UNAVAILABLE -> R.string.startup_failure_key_unavailable
        FailureReason.UNKNOWN -> R.string.startup_failure_key_unavailable
    }
