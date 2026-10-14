package dev.boksremote.audio

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import dev.boksremote.speaker.EqCurve
import dev.boksremote.speaker.EqMode
import dev.boksremote.speaker.SpeakerController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Developer tool: finds out what each EQ band does on the connected model. Every band response is
 * a difference against a flat measurement at the same spot, so the room and the microphone cancel.
 *
 * - Background noise first, so the analysis can drop frequencies the sweep doesn't clearly beat.
 * - Each band at +10 and -10 dB (two sweeps each, these feed the model) and +5 dB (linearity).
 * - Flat at the start, middle and end: their spread is the measurement's own repeatability.
 * - One multi-band setting, to check the bands simply add up the way the model assumes.
 *
 * Volume is capped while it runs, so the speaker's limiter can't flatten the boosts. Logs CSV
 * under the tag "BandCal"; the speaker's EQ and volume are restored afterwards.
 */
object BandCalibration {
    private const val TAG = "BandCal"
    /** Sweeps per measurement: more for the settings the model is fitted to. */
    private const val MAIN = 2
    private const val CHECK = 1
    /** About 30 %: loud enough to beat a quiet room, well below where limiting starts. */
    private const val MAX_VOLUME = 77
    val COMBO = listOf(-6, 4, 0, -8, 6, -4)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun run(speaker: SpeakerController, meter: RoomMeter, onProgress: (String) -> Unit) {
        val before = speaker.state.value
        val columns = linkedMapOf<String, DoubleArray>()
        try {
            before.volume?.takeIf { it > MAX_VOLUME }?.let { speaker.setVolume(MAX_VOLUME) }
            speaker.setCustomEq(EqCurve.FLAT)
            val total = 4 + EqCurve.BANDS * 3 + 1
            var step = 0
            meter.session {
                onProgress("${++step} of $total: background noise (stay quiet)")
                columns["noise"] = noiseFloor()
                suspend fun take(name: String, bands: List<Int>, sweeps: Int) {
                    onProgress("${++step} of $total: $name")
                    speaker.setCustomEq(bands)
                    delay(800)
                    columns[name] = measure(sweeps)
                }
                fun one(i: Int, db: Int) = EqCurve.FLAT.toMutableList().also { it[i] = db }

                take("flat", EqCurve.FLAT, MAIN)
                for (i in 0 until EqCurve.BANDS) {
                    take("b${i + 1}_p10", one(i, 10), MAIN)
                    take("b${i + 1}_m10", one(i, -10), MAIN)
                }
                take("flat_mid", EqCurve.FLAT, CHECK)
                for (i in 0 until EqCurve.BANDS) take("b${i + 1}_p5", one(i, 5), CHECK)
                take("combo", COMBO, CHECK)
                take("flat_end", EqCurve.FLAT, CHECK)
            }

            // Levels are in the meter's own units; the analysis only uses differences and the noise margin.
            Log.i(TAG, "# volume=${minOf(before.volume ?: 0, MAX_VOLUME)} combo=${COMBO.joinToString(" ")}")
            Log.i(TAG, "hz," + columns.keys.joinToString(","))
            Spectrum.GRID.forEachIndexed { g, hz ->
                Log.i(TAG, "%.1f,".format(hz) + columns.values.joinToString(",") { "%.2f".format(it[g]) })
            }
            Log.i(TAG, "done")
            onProgress("Done")
        } finally {
            withContext(NonCancellable) {
                if (before.eq == EqMode.USER) speaker.setCustomEq(before.eqBands ?: EqCurve.FLAT)
                else before.eq?.let(speaker::setEq)
                before.volume?.takeIf { it > MAX_VOLUME }?.let(speaker::setVolume)
            }
        }
    }
}
