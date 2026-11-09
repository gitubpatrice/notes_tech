package com.filestech.notes_tech.domain.model

import java.time.Duration

/**
 * How long a note lives (D-043, 3.4.0) — the five choices Patrice kept on 2026-11-09.
 *
 * A [duree] counts from the moment it is set; [APRES_LECTURE] has none: the note is erased when it is
 * closed after it was opened again — the session where the choice is made does not count.
 */
enum class DureeDeVie(val duree: Duration?) {
    UNE_HEURE(Duration.ofHours(1)),
    UN_JOUR(Duration.ofDays(1)),
    SEPT_JOURS(Duration.ofDays(7)),
    TRENTE_JOURS(Duration.ofDays(30)),
    APRES_LECTURE(null),
}
