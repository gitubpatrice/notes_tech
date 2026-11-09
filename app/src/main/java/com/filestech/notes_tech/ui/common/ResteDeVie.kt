package com.filestech.notes_tech.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.filestech.notes_tech.R
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** What is left of a note's lifetime (D-043, 3.4.0), as the cards and the editor say it. */
sealed interface ResteDeVie {
    data class Minutes(val nombre: Int) : ResteDeVie
    data class Heures(val nombre: Int) : ResteDeVie
    data class Jours(val nombre: Int) : ResteDeVie

    /** The time is up and the purge has not run yet: it runs at the next opening, or on the list. */
    data object Bientot : ResteDeVie

    data object ApresLecture : ResteDeVie
}

/**
 * What is left at [maintenant] of a lifetime ending at [expireLe], or "after reading"; `null` when the note
 * has none. Rounded UP: a note with 59 minutes 30 left says "60 min", never "59" — it must not seem to
 * have less time than it has. Minutes below an hour, hours below two days, days beyond.
 */
fun resteDeVie(maintenant: Instant, expireLe: Instant?, lectureUnique: Boolean): ResteDeVie? {
    if (lectureUnique) return ResteDeVie.ApresLecture
    val reste = expireLe?.let { Duration.between(maintenant, it) } ?: return null
    val minutes = arrondiAuDessus(reste, Duration.ofMinutes(1))
    val heures = arrondiAuDessus(reste, Duration.ofHours(1))
    return when {
        reste.isNegative || reste.isZero -> ResteDeVie.Bientot
        minutes < MINUTES_PAR_HEURE -> ResteDeVie.Minutes(minutes.toInt())
        heures < HEURES_AFFICHEES -> ResteDeVie.Heures(heures.toInt())
        else -> ResteDeVie.Jours(arrondiAuDessus(reste, Duration.ofDays(1)).toInt())
    }
}

private fun arrondiAuDessus(duree: Duration, unite: Duration): Long =
    (duree.toMillis() + unite.toMillis() - 1) / unite.toMillis()

private const val MINUTES_PAR_HEURE = 60L
private const val HEURES_AFFICHEES = 48L

/** What a card shows of a lifetime: [court] on its badge, [phrase] in what TalkBack reads. */
data class VieAffichee(val court: String, val phrase: String)

/** [VieAffichee.phrase] as the next sentence of a card's description — nothing for a note without one. */
fun VieAffichee?.enPhraseSuivante(): String = if (this == null) "" else ". $phrase"

/**
 * A card's [VieAffichee], `null` for a note without a lifetime — which asks for no clock: only the cards of
 * notes with a lifetime move every [rememberMaintenant] step.
 */
@Composable
fun vieAffichee(expireLe: Instant?, lectureUnique: Boolean): VieAffichee? {
    if (expireLe == null && !lectureUnique) return null
    val reste = resteDeVie(rememberMaintenant(), expireLe, lectureUnique) ?: return null
    return VieAffichee(court = reste.court(), phrase = reste.phrase())
}

/** Short, for a card's badge: "12 min", "5 h", "3 d". */
@Composable
fun ResteDeVie.court(): String = when (this) {
    is ResteDeVie.Minutes -> stringResource(R.string.lifetime_left_minutes_short, nombre)
    is ResteDeVie.Heures -> stringResource(R.string.lifetime_left_hours_short, nombre)
    is ResteDeVie.Jours -> stringResource(R.string.lifetime_left_days_short, nombre)
    ResteDeVie.Bientot -> stringResource(R.string.lifetime_soon_short)
    ResteDeVie.ApresLecture -> stringResource(R.string.lifetime_after_reading)
}

/** Whole, for TalkBack and the editor: "Erases itself in 12 minutes". */
@Composable
fun ResteDeVie.phrase(): String = when (this) {
    is ResteDeVie.Minutes -> pluralStringResource(R.plurals.lifetime_left_minutes, nombre, nombre)
    is ResteDeVie.Heures -> pluralStringResource(R.plurals.lifetime_left_hours, nombre, nombre)
    is ResteDeVie.Jours -> pluralStringResource(R.plurals.lifetime_left_days, nombre, nombre)
    ResteDeVie.Bientot -> stringResource(R.string.lifetime_soon)
    ResteDeVie.ApresLecture -> stringResource(R.string.lifetime_after_reading_phrase)
}

/**
 * Now, moved on every [pasMs]: a card that says "12 min" says "11 min" a minute later without the list
 * having to change. Only the cards of notes with a lifetime ask for it.
 */
@Composable
fun rememberMaintenant(pasMs: Long = PAS_MS): Instant {
    val maintenant by produceState(Instant.now()) {
        while (true) {
            delay(pasMs)
            value = Instant.now()
        }
    }
    return maintenant
}

private const val PAS_MS = 30_000L
