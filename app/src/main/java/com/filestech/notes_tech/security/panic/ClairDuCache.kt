package com.filestech.notes_tech.security.panic

import android.content.Context
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.data.voice.VoiceCapture
import java.io.File

/**
 * What, in the cache, can carry the content of a note — **the one definition**, shared by the panic
 * (its erasing and its final measure) and by the purge at every startup.
 *
 * ⚠️⚠️ It was a private function of `PanicService` until 2026-09-26, and "the one definition of
 * plaintext" there too (2026-08-19). It moved out because the startup purge needs it: a second list
 * written in `NotesTechApplication` is the divergence it was made to end.
 *
 * ## What 2.x left, and 3.x never cleaned — security audit of 2026-09-26, P1 and P2
 *
 * The app replaced here, notes_tech 2.x, wrote plaintext in the same sandbox, and 3.x purged only its
 * own two directories at startup; the rest waited for a panic, and came last in it:
 *
 * - `share_plus/` — share_plus 10.1.4 copies **every shared file** there before the chooser opens
 *   (`Share.kt:187`, `:245-252`), even for a share cancelled, and empties it only at the next share
 *   (`:95`) — which 3.x, no longer using share_plus, never makes. The last export of 2.x — the ZIP
 *   of every note, the open vaults' included — stayed there for good.
 * - `<title>.md` at the root — the export of one note, deleted 30 s later by a timer a killed
 *   process never ran (`note_editor_screen.dart:700-709`).
 * - `stt_capture_*.wav` at the root — the dictation of `files_tech_voice`, purged by 2.x at its own
 *   startup only.
 *
 * ⚠️ Only the **first level** is looked at by name: a directory is judged by its own name, never by
 * what it holds. `share_plus` and the two directories of 3.x are named here for that reason.
 */
internal object ClairDuCache {

    /** Where share_plus 10.x copies the files it shares — cf. above. */
    private const val COPIES_DE_SHARE_PLUS = "share_plus"

    fun estUnArtefactSensible(context: Context, nom: String): Boolean {
        val n = nom.lowercase()
        // ⚠️⚠️ `captures` was missing once, and it was an asymmetric twin: `exports` made the step
        // fail by surviving, its neighbour did not. Both directories hold plaintext — the notes' text
        // on one side, the voice that dictates them on the other. Found by an external review (Gemini,
        // 2026-08-16).
        //
        // ⚠️⚠️ The two names are **asked of those who write these directories**, never copied: two
        // definitions, and the panic would watch a directory the export no longer uses.
        return n == NoteExporter.repertoireDExport(context).name.lowercase() ||
            n == VoiceCapture.repertoireDeCapture(context).name.lowercase() ||
            n == COPIES_DE_SHARE_PLUS ||
            n.endsWith(".zip") ||
            n.endsWith(".md") ||
            n.endsWith(".wav")
    }

    /**
     * Deletes, at the root of the cache, every entry the definition calls plaintext.
     *
     * @return the entries that are still there after — the caller decides what a survivor means.
     */
    fun purger(context: Context): List<File> = context.cacheDir.listFiles().orEmpty()
        .filter { estUnArtefactSensible(context, it.name) }
        .filter { entree ->
            entree.deleteRecursively()
            entree.exists()
        }
}
