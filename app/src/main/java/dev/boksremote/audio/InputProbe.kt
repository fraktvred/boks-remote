package dev.boksremote.audio

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import dev.boksremote.speaker.SpeakerController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.util.Locale

/**
 * Developer tool: checks whether the speaker applies the per-input gain and EQ the firmware
 * accepts but never reports back. Test noise has to reach the AUX input (phone → USB-C audio
 * adapter → aux cable) while the microphone listens. Logs under the tag "InputProbe".
 */
object InputProbe {
    private const val TAG = "InputProbe"
    private val COUNTS = listOf(6, 10, 5, 8, 3)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun run(speaker: SpeakerController, meter: RoomMeter, onProgress: (String) -> Unit) {
        val grid = Spectrum.GRID
        val mid = grid.indices.filter { grid[it] in 200.0..5_000.0 }
        fun change(a: DoubleArray, b: DoubleArray) = mid.map { b[it] - a[it] }.sorted()[mid.size / 2]
        suspend fun measureAfter(what: String, apply: suspend () -> Unit): DoubleArray {
            onProgress(what)
            apply()
            delay(800)
            return meter.measure()
        }
        try {
            val base = measureAfter("Baseline") { speaker.setAuxGain(0f) }
            val quiet = measureAfter("Gain -12 dB") { speaker.setAuxGain(-12f) }
            Log.i(TAG, "gain -12 dB -> level change %.1f dB (works if close to -12)".format(change(base, quiet)))
            speaker.setAuxGain(0f)

            var bandCount: Int? = null
            for (n in COUNTS) {
                val cut = measureAfter("EQ: $n bands at -10") { speaker.setAuxEq(List(n) { -10f }) }
                val d = change(base, cut)
                Log.i(TAG, "eq $n bands all -10 dB -> level change %.1f dB".format(d))
                if (d < -4) { bandCount = n; break }
            }
            if (bandCount == null) {
                Log.i(TAG, "no band count changed the sound; input EQ looks ignored")
            } else {
                val flat = measureAfter("EQ: flat") { speaker.setAuxEq(List(bandCount) { 0f }) }
                Log.i(TAG, "hz," + (1..bandCount).joinToString(",") { "b$it" })
                val responses = (0 until bandCount).map { i ->
                    val r = measureAfter("EQ: band ${i + 1} of $bandCount") { speaker.setAuxEq(List(bandCount) { if (it == i) 10f else 0f }) }
                    DoubleArray(grid.size) { r[it] - flat[it] }
                }
                grid.forEachIndexed { g, hz -> Log.i(TAG, "%.1f,".format(Locale.ROOT, hz) + responses.joinToString(",") { "%.2f".format(Locale.ROOT, it[g]) }) }
                responses.forEachIndexed { i, r ->
                    val peak = r.indices.maxBy { abs(r[it]) }
                    Log.i(TAG, "band ${i + 1}: %+.1f dB at %.0f Hz".format(r[peak], grid[peak]))
                }
            }
            Log.i(TAG, "done")
            onProgress("Done")
        } finally {
            // Put the input back to neutral whatever happened, even when cancelled.
            withContext(NonCancellable) {
                runCatching { speaker.setAuxGain(0f) }
                runCatching { speaker.setAuxEq(List(6) { 0f }) }
            }
        }
    }
}
