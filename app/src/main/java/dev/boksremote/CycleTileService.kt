package dev.boksremote

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that pauses and resumes Cycle without opening the app. It acts on the user's own
 * scene light, so it is unavailable while there is none (the connection lives in the app's process,
 * which a tile alone can't bring back).
 */
class CycleTileService : TileService() {
    private var updates: Job? = null
    private val app get() = application as BoksApp

    override fun onStartListening() {
        updates?.cancel()
        // Collected only while the tile is visible.
        updates = app.scope.launch {
            val stage = app.ownStage
            combine(stage.status, stage.cycle.status, ::Pair).collect { (status, cycle) ->
                val tile = qsTile ?: return@collect
                val unavailable = status.cycle.takeUnless { cycle.running }
                tile.icon = Icon.createWithResource(this@CycleTileService, R.drawable.ic_tile_cycle)
                tile.label = "Cycle"
                tile.state = when {
                    unavailable != null -> Tile.STATE_UNAVAILABLE
                    cycle.running -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.subtitle = when {
                    unavailable != null -> unavailable.label
                    cycle.running -> cycle.current
                    cycle.paused -> "Paused"
                    else -> "Off"
                }
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        updates?.cancel()
        updates = null
    }

    override fun onClick() {
        val stage = app.ownStage
        // Like the screen's Pause and Resume: the palette stays on, and a second tap plays on from it.
        if (stage.cycle.status.value.running) stage.holdCycle() else stage.startCycle()
    }
}
