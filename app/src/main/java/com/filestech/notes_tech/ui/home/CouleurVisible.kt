package com.filestech.notes_tech.ui.home

import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.NoteColor

/**
 * The colour a list shows for this note (3.1.0), given the vaults open right now.
 *
 * ## 🔴 A sealed note shows its colour only while its vault is OPEN
 *
 * Its card says "Locked note" and hides its title, excerpt and tags — even with the vault open, a
 * decision of 2026-09-13. A colour on that card would tell what the card refuses to tell: a red-flagged
 * "medical" note, say. So it is drawn only while the vault is open — Patrice's option A, 2026-10-10 —
 * where it is the one way to tell a vault's notes apart, all named "Locked note". The vault closing,
 * automatic lock included, turns the card neutral again, since [coffresOuverts] is the sessions' flow.
 *
 * ⚠️ What this does NOT cover, and the privacy policy says: the colour is stored in clear in the
 * database — encrypted by SQLCipher as a whole, but outside the vault's own envelope, like the tags.
 *
 * One rule for every list — home, search, trash — so that none shows a colour another would hide.
 */
internal fun Note.couleurVisible(coffresOuverts: Set<String>): NoteColor? =
    color?.takeIf { !isLocked || folderId in coffresOuverts }
