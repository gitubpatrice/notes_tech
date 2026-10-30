package com.filestech.notes_tech.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.filestech.notes_tech.data.local.entity.NoteEntity

/**
 * What the encrypted backup (3.2.0) reads, and nothing else does: every note, the trash included, in a
 * stable order — read inside one transaction, written to the file as it comes.
 */
@Dao
interface SauvegardeDao {

    /**
     * Each note's id, folder and weight — characters of text, bytes of a sealed blob — and nothing of
     * its text: enough to count the notes and to cut them in lots ([notes]) that the heap can hold.
     */
    @Query(
        "SELECT id, folder_id AS dossierId, " +
            "length(title) + length(content) + length(tags) + ifnull(length(encrypted_content), 0) AS poids " +
            "FROM notes ORDER BY id",
    )
    suspend fun poidsDesNotes(): List<PoidsDUneNote>

    /** The notes of [ids], in the order of [poidsDesNotes]. */
    @Query("SELECT * FROM notes WHERE id IN (:ids) ORDER BY id")
    suspend fun notes(ids: List<String>): List<NoteEntity>
}

data class PoidsDUneNote(val id: String, val dossierId: String, val poids: Long)
