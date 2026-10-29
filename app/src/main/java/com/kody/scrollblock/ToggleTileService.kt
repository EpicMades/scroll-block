package com.kody.scrollblock

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: swipe down twice, tap "Scroll Block" to turn blocking on/off. */
class ToggleTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val p = Prefs.get(this)
        p.edit().putBoolean(Prefs.ENABLED, !p.getBoolean(Prefs.ENABLED, true)).apply()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Prefs.get(this).getBoolean(Prefs.ENABLED, true)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
