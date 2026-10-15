package dev.boksremote.dmx

import android.util.Log
import dev.boksremote.speaker.Clock
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.LightContentCodec
import dev.boksremote.speaker.LightOutput
import dev.boksremote.speaker.SpeakerState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** How sending to the light goes: waiting for it to come back, or the last write failed. */
data class Sending(val reconnecting: Boolean = false, val error: String? = null)

/**
 * Sends the DMX bridge's looks to its light. DMX arrives at up to 44 Hz but each Bluetooth write
 * takes several milliseconds, so only the latest look is kept and whatever differs from what the
 * light last got is sent: effect, then dimmer, then mode, then colours.
 *
 * The Lightboks keeps its active palette across a reboot (tested 2026-10-12: a palette sent by the
 * bridge was still active after SystemService.PowerOff{REBOOT}), so each palette write may cost a
 * flash write. Colours are therefore only sent once they have held for [COLOR_SETTLE_MS], and at
 * most every [PALETTE_MIN_INTERVAL_MS]: a fade sends its end colour, not every step, while snaps and
 * chase steps still go through. A light that drops gets everything again once it is back.
 */
class LookSender(
    private val output: LightOutput,
    private val light: StateFlow<SpeakerState>,
    private val clock: Clock = Clock.ELAPSED,
) {
    private val desired = MutableStateFlow<LightLook?>(null)
    @Volatile private var colorsChangedAt = 0L
    private val _sending = MutableStateFlow(Sending())
    val sending: StateFlow<Sending> = _sending

    /** The look to show now; from any thread. */
    fun want(look: LightLook) {
        if (look.colors != desired.value?.colors) colorsChangedAt = clock.now()
        desired.value = look
    }

    /** Sends until cancelled. */
    suspend fun run() {
        // What the light last received, per output; null means unknown, so it is (re)sent.
        var sentFx: Any? = UNKNOWN
        var sentDimmer: Int? = null
        var sentMode: EnergyLevel? = null
        var sentColors: List<Rgb>? = null
        var lastPaletteAt: Long? = null
        var paletteSlot = false
        while (currentCoroutineContext().isActive) {
            val want = desired.value ?: desired.first { it != null }!!
            if (light.value.connection != ConnectionState.CONNECTED) {
                awaitReconnect()
                sentFx = UNKNOWN; sentDimmer = null; sentMode = null; sentColors = null
                continue
            }
            try {
                when {
                    want.fx != sentFx -> {
                        output.applyFx(want.fx?.id ?: LightContentCodec.NO_ID)
                        sentFx = want.fx
                    }
                    want.dimmer != sentDimmer -> {
                        output.applyBrightness(want.dimmer)
                        sentDimmer = want.dimmer
                    }
                    want.mode != sentMode -> {
                        output.applyEnergyLevel(want.mode)
                        sentMode = want.mode
                    }
                    want.colors != sentColors -> {
                        val earliest = maxOf((lastPaletteAt ?: Long.MIN_VALUE / 2) + PALETTE_MIN_INTERVAL_MS, colorsChangedAt + COLOR_SETTLE_MS)
                        val wait = earliest - clock.now()
                        if (wait > 0) {
                            // Anything else that changes meanwhile is sent first.
                            withTimeoutOrNull(wait) { awaitChange(want) }
                            continue
                        }
                        // Alternating between two fixed ids changes the id on every update (so the light
                        // never treats it as unchanged) without minting a new stored palette each time.
                        paletteSlot = !paletteSlot
                        output.applyPalette(want.palette(), if (paletteSlot) PALETTE_ID_A else PALETTE_ID_B)
                        lastPaletteAt = clock.now()
                        sentColors = want.colors
                    }
                    else -> {
                        awaitChange(want)
                        continue
                    }
                }
                if (_sending.value.error != null) _sending.update { it.copy(error = null) }
            } catch (e: Exception) {
                // Rethrows when the bridge stopped; a BLE/RPC timeout lands here as an ordinary failure.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "DMX send failed", e)
                _sending.update { it.copy(error = e.message ?: "write failed") }
                delay(RETRY_MS)
            }
        }
    }

    /**
     * Sleeps until the look changes or the light drops. Waiting on the look alone would miss a
     * disconnect for as long as the desk keeps sending the same values, so no reconnect would start.
     */
    private suspend fun awaitChange(want: LightLook) {
        combine(desired, light) { d, s -> d != want || s.connection != ConnectionState.CONNECTED }.first { it }
    }

    /** Waits for a dropped light: the device list holds it while the bridge runs, and reconnects it. */
    private suspend fun awaitReconnect() {
        _sending.update { it.copy(reconnecting = true) }
        try {
            light.first { it.connection == ConnectionState.CONNECTED }
        } finally {
            _sending.update { it.copy(reconnecting = false) }
        }
    }

    companion object {
        private const val TAG = "LookSender"
        private val UNKNOWN = Any()
        const val PALETTE_MIN_INTERVAL_MS = 250L
        const val COLOR_SETTLE_MS = 150L
        /** After a failed write. */
        const val RETRY_MS = 500L
        val PALETTE_ID_A: UUID = UUID.fromString("b0c5d3a0-0d1e-4f7a-9c11-000000000d0a")
        val PALETTE_ID_B: UUID = UUID.fromString("b0c5d3a0-0d1e-4f7a-9c11-000000000d0b")
    }
}
