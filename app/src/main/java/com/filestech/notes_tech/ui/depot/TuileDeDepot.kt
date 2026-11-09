package com.filestech.notes_tech.ui.depot

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The quick settings tile of the blind drop (D-042, 3.4.0): a tap opens [DepotActivity].
 *
 * 🔴 **The PHONE is unlocked first** (Patrice, 2026-11-09): on a locked phone, its unlocking is asked, and
 * only then the drop opens. Only Notes Tech's own lock is stepped over, and only to write — whoever holds a
 * locked phone cannot drop anything into it. [DepotActivity] is not shown over the lock screen.
 * - From Android 14, the system does it itself: an activity started by `startActivityAndCollapse` with a
 *   `PendingIntent` waits for the phone's unlocking. [unlockAndRun] is not used there — measured on
 *   Android 16, its callback never came back after the code was typed: the phone unlocked onto the home
 *   screen, the drop never opened (the S24's tests of 3.4.0, before publication).
 * - Before, [unlockAndRun] asks for the unlocking, then opens the drop.
 *
 * Bound by the system only: the service is exported for it, behind `BIND_QUICK_SETTINGS_TILE`, which no
 * app holds. It reads nothing and shows nothing but its own label.
 *
 * 🔴 **An ACTIVE tile** (manifest, 3-axes audit of 3.4.0, M6): bound when tapped, never because the panel
 * shows it. Measured on the S9 before: with the tile added, pulling down the notifications alone started
 * the app's process each time it was dead — and a start purges `cache/exports`, where a shared sealed
 * note or export may still wait for the app it was sent to. The tile has no state to keep up to date
 * anyway: it is set at rest when added, and again whenever it is bound.
 */
class TuileDeDepot : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        auRepos()
    }

    override fun onStartListening() {
        super.onStartListening()
        auRepos()
    }

    /** Nothing to switch on or off: a door, shown at rest. */
    private fun auRepos() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unlockAndRun { ouvrirLeDepot() }
        } else {
            ouvrirLeDepot()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun ouvrirLeDepot() {
        val intention = Intent(this, DepotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intention,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intention)
        }
    }
}
