package com.futura.system

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.futura.alarm.AlarmStore
import com.futura.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Quick Settings tile. Shows whether any alarm is enabled and opens the app
 * when tapped.
 */
@AndroidEntryPoint
class FuturaQsTileService : TileService() {

    @Inject
    lateinit var store: AlarmStore

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ requires the PendingIntent overload.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    /**
     * Shows the current track while something is loaded (playing or paused),
     * otherwise the alarm status.
     */
    private fun refreshTile() {
        val tile = qsTile ?: return
        val title = NowPlayingSnapshot.title?.takeIf { it.isNotBlank() }
        if (title != null) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = if (NowPlayingSnapshot.isPlaying) "Now playing" else "Paused"
            tile.subtitle = title.take(24)
        } else {
            val anyEnabled = store.specs.value.any { it.enabled }
            tile.state = if (anyEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = "Futura"
            tile.subtitle = if (anyEnabled) "Alarm on" else "No alarms"
        }
        tile.updateTile()
    }
}
