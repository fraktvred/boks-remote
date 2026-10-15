package dev.boksremote.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.boksremote.DevicePanel
import dev.boksremote.speaker.SleepFade
import kotlinx.coroutines.delay

/** Off, or how long the fade to dark takes; picking a length starts it from the brightness now. */
@Composable
internal fun SleepSection(panel: DevicePanel, view: DeviceView) {
    val stage = panel.stage
    val status by stage.sleep.collectAsState()
    val options = listOf(0) + SleepFade.DURATIONS
    // A running fade can always be switched off; a new one only starts where the Stage lets it.
    Choice(options, if (status.running) status.minutes else 0, { if (it == 0) "Off" else "$it min" }, enabled = status.running || view.sleep == null) { minutes ->
        if (minutes == 0) stage.stopSleep() else stage.startSleep(minutes)
    }
    when {
        status.running -> {
            val left by produceState(0L, status.endsAt) {
                while (true) {
                    value = (status.endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    delay(1_000)
                }
            }
            val minutes = (left + 59_999) / 60_000
            HintText("Fading out. The Lightboks switches off in $minutes min. Moving the brightness slider stops the fade.")
        }
        view.sleep != null -> HintText(view.sleep.hint("Sleep fade"))
        else -> HintText("Dims the light slowly to dark, then switches it off. Keeps going with the screen off.")
    }
}
