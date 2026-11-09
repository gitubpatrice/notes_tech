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
 * 🔴 **The PHONE is unlocked first** (Patrice, 2026-11-09): on a locked phone, [unlockAndRun] asks for its
 * unlocking, and only then opens the drop. Only Notes Tech's own lock is stepped over, and only to write
 * — whoever holds a locked phone cannot drop anything into it.
 *
 * Bound by the system only: the service is exported for it, behind `BIND_QUICK_SETTINGS_TILE`, which no
 * app holds. It reads nothing and shows nothing but its own label.
 */
class TuileDeDepot : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            // Nothing to switch on or off: a door, shown at rest.
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { ouvrirLeDepot() } else ouvrirLeDepot()
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
