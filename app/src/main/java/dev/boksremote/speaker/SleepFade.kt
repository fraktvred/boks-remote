package dev.boksremote.speaker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SleepStatus(
    val running: Boolean = false,
    val minutes: Int = 0,
    /** [Clock] time when the light switches off. */
    val endsAt: Long = 0,
)

/**
 * Sleep fade: lowers the Lightboks brightness evenly to nothing over [SleepStatus.minutes], then
 * switches the light off. Lives next to Cycle in [dev.boksremote.BoksApp] so it carries on with
 * the screen off; the brightness is a plain characteristic write, which the DMX bridge streams too.
 */
class SleepFade(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.ELAPSED,
    private val awake: KeepAwake = KeepAwake.NONE,
) {
    private val _status = MutableStateFlow(SleepStatus())
    val status: StateFlow<SleepStatus> = _status
    private var job: Job? = null

    fun start(light: Light, minutes: Int) {
        job?.cancel()
        val total = minutes * 60_000L
        val start = clock.now()
        val from = light.state.value.brightness ?: 255
        awake.acquire(total + WAKE_MARGIN_MS)
        _status.value = SleepStatus(running = true, minutes = minutes, endsAt = start + total)
        job = scope.launch {
            while (true) {
                if (clock.now() - start >= total) break
                // While the light is reconnecting it waits; the level then catches up with the clock.
                light.state.first { it.connection == ConnectionState.CONNECTED }
                val level = levelAt(from, clock.now() - start, total)
                if (level != light.state.value.brightness) light.setBrightness(level)
                delay(STEP_MS)
            }
            light.state.first { it.connection == ConnectionState.CONNECTED }
            light.setBrightness(0)
            light.powerOff()
            finish()
        }
    }

    /** Leaves the brightness where the fade had got to. */
    fun stop() {
        job?.cancel()
        finish()
    }

    private fun finish() {
        job = null
        awake.release()
        _status.value = SleepStatus()
    }

    companion object {
        val DURATIONS = listOf(15, 30, 60)
        /** Fine enough that the steps aren't seen: 255 levels over 15 minutes is one every 3.5 s. */
        private const val STEP_MS = 5_000L
        private const val WAKE_MARGIN_MS = 60_000L

        fun levelAt(from: Int, elapsedMs: Long, totalMs: Long): Int =
            (from * (1 - elapsedMs.toDouble() / totalMs)).toInt().coerceIn(0, from)
    }
}
