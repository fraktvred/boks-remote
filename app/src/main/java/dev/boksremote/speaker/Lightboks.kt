package dev.boksremote.speaker

import android.os.SystemClock
import dev.boksremote.proto.ProtoMessage
import dev.boksremote.protocol.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** The two ways a Lightboks is written to: RPC calls and characteristic writes. A fake in tests. */
interface LightLink {
    suspend fun call(service: String, method: String, request: ByteArray = ByteArray(0)): ProtoMessage
    suspend fun write(characteristic: UUID, value: ByteArray)
}

/**
 * What a Lightboks shows: brightness, light mode, palette and effects, written in the order its
 * firmware needs, with [state] following what the light was last told. [SpeakerController] makes one
 * per connection; the DMX bridge paces its own writes through it.
 *
 * [report] hears of a failed step: with what failed, or with null for an optional step that older
 * firmware may lack.
 */
class Lightboks(
    private val link: LightLink,
    private val state: MutableStateFlow<SpeakerState>,
    private val scope: CoroutineScope,
    private val report: (what: String?, e: Exception) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /**
     * One light-content write at a time, in the order they were made. A light mode change is two
     * writes, and a palette landing between them doesn't show until the next mode change.
     */
    private val content = Mutex()

    suspend fun applyBrightness(value: Int) {
        val v = value.coerceIn(0, 255)
        link.write(Uuids.LIGHT_BRIGHTNESS, byteArrayOf(v.toByte()))
        state.update { it.copy(brightness = v) }
    }

    /**
     * Mirrors the official app's order: Uplight starts the built-in program and then reports level 3;
     * the other modes write their level and then clear any program (an energy-level write of 3 alone is rejected).
     */
    suspend fun applyEnergyLevel(level: EnergyLevel) = content.withLock {
        if (level == EnergyLevel.CUSTOM) {
            checkStatus(link.call(SERVICE, "SetCustomProgram", LightContentCodec.customProgramRequest(UPLIGHT_PROGRAM)))
            link.write(Uuids.LIGHT_ENERGY_LEVEL, byteArrayOf(level.ordinal.toByte()))
        } else {
            link.write(Uuids.LIGHT_ENERGY_LEVEL, byteArrayOf(level.ordinal.toByte()))
            // Older light firmware has no custom programs; the level change already took effect.
            quietly { link.call(SERVICE, "SetCustomProgram", LightContentCodec.customProgramRequest(LightContentCodec.NO_ID)) }
        }
        state.update { it.copy(energyLevel = level) }
    }

    /** Sends a palette. A fresh id by default, so the light never keeps a cached copy of an earlier edit. */
    suspend fun applyPalette(palette: LightPalette, id: UUID = UUID.randomUUID()) = send(palette, id, publish = true)

    /** [publish] false leaves [state] as it is, for a step on the way to another palette. */
    private suspend fun send(palette: LightPalette, id: UUID, publish: Boolean) = content.withLock {
        link.call(SERVICE, "SetActivePalette", LightContentCodec.palette(palette, id))
        if (publish) state.update { it.copy(palette = palette, paletteIsDefault = false) }
    }

    /**
     * Blends [from] into [to] over [durationMs] in [FADE_STEPS] evenly spaced writes, the last of them
     * [to] itself. The steps before it alternate between two fixed ids, like the DMX bridge's, so a fade
     * doesn't leave a stored palette per step; [to] goes out like any other palette. A step that fails
     * is skipped. [state] has [to] from the start: the colours in between are on their way to it, and
     * never what a scene should keep.
     */
    suspend fun fadePalette(from: LightPalette, to: LightPalette, durationMs: Long) {
        state.update { it.copy(palette = to, paletteIsDefault = false) }
        val start = clock()
        for (step in 1 until FADE_STEPS) {
            quietly { send(from.blend(to, step.toFloat() / FADE_STEPS), if (step % 2 == 0) FADE_ID_A else FADE_ID_B, publish = false) }
            delay((start + durationMs * step / (FADE_STEPS - 1) - clock()).coerceAtLeast(0))
        }
        attempt("palette") { applyPalette(to) }
    }

    /** The light's own default colours. */
    suspend fun resetPalette() = content.withLock {
        // The firmware ignores SetActivePalette with AUTO control; the official app uses this dedicated call.
        link.call(SERVICE, "ResetActiveColorPalette")
        state.update { it.copy(palette = null, paletteIsDefault = true) }
    }

    /** Starts a built-in animation; [LightContentCodec.NO_ID] stops it. */
    suspend fun applyFx(id: UUID) = content.withLock {
        checkStatus(link.call(SERVICE, "SetAnimation", LightContentCodec.animationRequest(id)))
    }

    private var dropJob: Job? = null
    /** True while the Drop pad holds or plays, when brightness and effect are only borrowed. */
    val dropping: Boolean get() = dropJob?.isActive == true
    /** The brightness to return to after a drop; kept when a new drop cuts the last one short. */
    private var dropReturn: Int? = null

    /**
     * Drop pad: blackout while held, the silence before the drop; on release a short strobe at full
     * brightness, then the effect stops and the brightness goes back to what it was.
     */
    fun holdDrop(held: Boolean) {
        val previous = dropJob
        if (held) {
            // A new press cuts the last drop short.
            previous?.cancel()
            dropJob = scope.launch { attempt("drop") { applyFx(LightFx.BLACKOUT.id) } }
            return
        }
        dropJob = scope.launch {
            // In order after the blackout, even for a quick tap.
            previous?.join()
            val before = dropReturn ?: state.value.brightness
            dropReturn = before
            attempt("drop") {
                applyFx(LightFx.STROBE.id)
                if (before != null) applyBrightness(255)
                delay(DROP_MS)
                applyFx(LightContentCodec.NO_ID)
                if (before != null) applyBrightness(before)
            }
            dropReturn = null
        }
    }

    private fun checkStatus(response: ProtoMessage) {
        val status = response.int(1)
        if (status != 0) throw IllegalStateException("light rejected the request (status $status)")
    }

    private suspend fun attempt(what: String?, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // a cancelled session isn't a failed control
            report(what, e)
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) = attempt(null, block)

    companion object {
        private const val SERVICE = "LightContentService"
        private const val DROP_MS = 2_000L
        /**
         * Each palette sent may cost the light a flash write (see DmxBridge); fewer than this and a fade
         * shows its steps. 12 over 3 s is one every ~270 ms, about the DMX bridge's own limit.
         */
        const val FADE_STEPS = 12
        val FADE_ID_A: UUID = UUID.fromString("b0c5d3a0-0d1e-4f7a-9c11-000000000fa0")
        val FADE_ID_B: UUID = UUID.fromString("b0c5d3a0-0d1e-4f7a-9c11-000000000fa1")
        // Program ids built into the Lightboks firmware, as used by the official app.
        val UPLIGHT_PROGRAM: UUID = UUID.fromString("303c4ee1-e178-4f2f-bc18-6b3f4f682825")
    }
}
