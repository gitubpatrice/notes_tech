package com.filestech.notes_tech.domain.importation

import com.filestech.notes_tech.domain.markdown.MarkdownPreviewReader

/**
 * 🔴 **The longest note an import, a shared file or a shared text brings in** (3.2.0, 2026-10-30).
 *
 * Until then a file of 5 MiB was taken, and a shared text of a million characters: a note the app
 * could neither show — Compose drew it whole, the app froze with its heap full — nor edit. Measured
 * on the S9 (Android 10), release build, once the editor became an `EditText` (`ChampDuCorps`): 450 000
 * characters read and written as fluidly as a short note. The bound is the preview's own
 * ([MarkdownPreviewReader.MAX_LENGTH]): every note brought in is shown drawn, never as written.
 *
 * Counted in characters — UTF-16 units, as `String.length` and the preview count them — on the text
 * decoded. A note typed or pasted in the app has no such bound.
 */
internal object LongueurDUneNote {

    const val CARACTERES_MAX = MarkdownPreviewReader.MAX_LENGTH

    /** Bytes read of a file before decoding it: UTF-8 writes one UTF-16 unit in 3 bytes at most. */
    const val OCTETS_MAX = 3 * CARACTERES_MAX
}

/** [octets] as the text of a note: strict UTF-8 ([utf8Strict]), and no longer than [LongueurDUneNote]. */
internal fun texteDUneNote(octets: ByteArray): String? =
    utf8Strict(octets)?.takeIf { it.length <= LongueurDUneNote.CARACTERES_MAX }
