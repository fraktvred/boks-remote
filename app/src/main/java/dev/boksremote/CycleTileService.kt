package dev.boksremote

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.boksremote.protocol.Family
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.SpeakerController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that starts and stops Cycle without opening the app. It acts on the Lightboks
 * the app is connected to, so it is unavailable while there is none (the connection lives in the app's
 * process, which a tile alone can't bring back).
 */
class CycleTileService : TileService() {
    private var updates: Job? = null
    private val app get() = application as BoksApp

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartListening() {
        updates?.cancel()
        // Collected only while the tile is visible.
        updates = app.scope.launch {
            val light = app.speaker.flatMapLatest { it?.state ?: flowOf(null) }
            combine(light, app.cycle.status, app.bridge.status) { s, cycle, bridge -> Triple(s, cycle, bridge) }.collect { (s, cycle, bridge) ->
                val tile = qsTile ?: return@collect
                val connected = s?.family == Family.LIGHT && s.connection == ConnectionState.CONNECTED && s.rpcAvailable
                tile.icon = Icon.createWithResource(this@CycleTileService, R.drawable.ic_tile_cycle)
                tile.label = "Cycle"
                tile.state = when {
                    !connected || bridge.running -> Tile.STATE_UNAVAILABLE
                    cycle.running -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.subtitle = when {
                    !connected -> "No Lightboks"
                    bridge.running -> "DMX bridge on"
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
        val light: SpeakerController = app.speaker.value ?: return
        val cycle = app.cycle
        when {
            cycle.status.value.running -> cycle.stop()
            app.bridge.status.value.running -> return
            cycle.palettes().size >= CycleConfig.MIN_PALETTES -> cycle.start(light)
        }
    }
}
