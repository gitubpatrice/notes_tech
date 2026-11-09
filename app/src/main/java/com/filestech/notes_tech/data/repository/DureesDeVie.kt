package com.filestech.notes_tech.data.repository

import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.domain.model.DureeDeVie
import java.time.Clock
import javax.inject.Inject

/**
 * Notes that erase themselves (D-043, 3.4.0): set a note's lifetime, erase those whose lifetime is over.
 *
 * Apart from [NotesRepository], as the gestures on several notes are ([NotesEnLot]): its threshold of
 * functions holds a class that already says a great deal.
 */
class DureesDeVie @Inject constructor(private val databases: DatabaseProvider, private val clock: Clock) {

    /**
     * How long the note lives; `null` for always. A duration counts from now: choosing "1 hour" again
     * starts the hour again. Writes those two columns only — not `updated_at`, which orders the lists:
     * giving a note a lifetime is not editing it.
     */
    suspend fun poser(id: String, duree: DureeDeVie?): Boolean {
        val expireLe = duree?.duree?.let { clock.instant().plus(it).toEpochMilli() }
        val lectureUnique = duree == DureeDeVie.APRES_LECTURE
        return databases.get().noteWriteDao().updateLifetime(id, expireLe, lectureUnique) > 0
    }

    /**
     * Erases the notes whose lifetime is over — the trash's too —, as "Delete permanently" erases one: the
     * same DELETE, so the full-text index (trigger `notes_ad`, `secure-delete`), the links (foreign keys)
     * and `PRAGMA secure_delete` treat them alike. A vault note needs no key for that.
     *
     * @return how many were erased.
     */
    suspend fun purger(): Int = databases.get().noteWriteDao().deleteExpired(clock.millis())
}
