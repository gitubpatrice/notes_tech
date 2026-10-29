package com.filestech.notes_tech.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.filestech.notes_tech.data.local.entity.NoteEntity

/**
 * What the encrypted backup (3.2.0) reads, and nothing else does: every note, the trash included, a
 * page at a time in a stable order — read inside one transaction, written to the file as it comes.
 */
@Dao
interface SauvegardeDao {

    @Query("SELECT * FROM notes ORDER BY id LIMIT :limite OFFSET :decalage")
    suspend fun pageDeNotes(limite: Int, decalage: Int): List<NoteEntity>
}
