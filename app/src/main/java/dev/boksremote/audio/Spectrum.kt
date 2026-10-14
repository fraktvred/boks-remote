package dev.boksremote.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plain-JVM signal maths for acoustic measurements. Kept free of Android types so it is unit-testable.
 *
 * Measurements use an exponential sine sweep: the recording is deconvolved into an impulse response.
 * That rejects background noise better than steady noise does, and pushes the speaker's harmonic
 * distortion to before the main impulse, where the window leaves it out.
 */
object Spectrum {
    const val SAMPLE_RATE = 48_000
    const val SWEEP_LOW_HZ = 20.0
    const val SWEEP_HIGH_HZ = 20_000.0

    /** 1/12-octave points from 40 Hz to 16 kHz; every measured response is reported on this grid. */
    val GRID: DoubleArray = generateSequence(40.0) { it * 2.0.pow(1.0 / 12) }.takeWhile { it <= 16_000.0 }.toList().toDoubleArray()

    /** Exponential sine sweep with short fades, so the speaker doesn't click at either end. */
    fun logSweep(seconds: Double, peak: Float = 0.5f, fs: Int = SAMPLE_RATE): FloatArray {
        val n = (seconds * fs).toInt()
        val k = ln(SWEEP_HIGH_HZ / SWEEP_LOW_HZ)
        val fade = fs / 100
        return FloatArray(n) { i ->
            val t = i.toDouble() / fs
            val phase = 2 * PI * SWEEP_LOW_HZ * seconds / k * (exp(t / seconds * k) - 1)
            val env = when {
                i < fade -> 0.5 - 0.5 * cos(PI * i / fade)
                i > n - fade -> 0.5 - 0.5 * cos(PI * (n - i) / fade)
                else -> 1.0
            }
            (peak * env * sin(phase)).toFloat()
        }
    }

    /** In-place radix-2 FFT; [re] and [im] must have the same power-of-two length. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2 else -2) * PI / len
            val wStepR = cos(ang); val wStepI = sin(ang)
            for (i in 0 until n step len) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val vr = re[b] * wr - im[b] * wi
                    val vi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val t = wr * wStepR - wi * wStepI
                    wi = wr * wStepI + wi * wStepR; wr = t
                }
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    private fun nextPow2(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }

    /**
     * Impulse response from a recording of [sweep]: Y / X, regularised so frequencies the sweep
     * didn't cover don't blow up. Index 0 is the moment the sweep started playing in the recording's
     * time base, so the main peak sits at the playback + Bluetooth delay.
     */
    fun impulseResponse(recorded: FloatArray, sweep: FloatArray): DoubleArray {
        val n = nextPow2(recorded.size + sweep.size)
        val yr = DoubleArray(n) { if (it < recorded.size) recorded[it].toDouble() else 0.0 }; val yi = DoubleArray(n)
        val xr = DoubleArray(n) { if (it < sweep.size) sweep[it].toDouble() else 0.0 }; val xi = DoubleArray(n)
        fft(yr, yi); fft(xr, xi)
        var maxMag = 0.0
        for (k in 0 until n) maxMag = max(maxMag, xr[k] * xr[k] + xi[k] * xi[k])
        val eps = maxMag * 1e-5
        for (k in 0 until n) {
            val d = xr[k] * xr[k] + xi[k] * xi[k] + eps
            val r = (yr[k] * xr[k] + yi[k] * xi[k]) / d
            val i = (yi[k] * xr[k] - yr[k] * xi[k]) / d
            yr[k] = r; yi[k] = i
        }
        fft(yr, yi, inverse = true)
        return yr
    }

    /** The direct sound: the largest peak within the first [maxDelaySamples]. */
    fun peakIndex(ir: DoubleArray, maxDelaySamples: Int = ir.size / 2): Int =
        (0 until minOf(maxDelaySamples, ir.size)).maxBy { abs(ir[it]) }

    /** Window lengths for [fdwResponseDb], in √2 steps from 5 ms to 500 ms. */
    private val WINDOWS_S = generateSequence(0.5) { it / sqrt(2.0) }.takeWhile { it >= 0.0049 }.toList()

    /**
     * Frequency-dependent windowing: each frequency is judged over [cycles] periods after the
     * direct sound (between 5 ms and 500 ms). Bass sees the room's steady build-up, which is what
     * people hear there; treble sees the direct sound and early reflections, not every late echo.
     * Starts [preMs] before the peak, which keeps the harmonic distortion products (far earlier in
     * a sweep's response) out. Each grid point averages 1/6 octave of power.
     */
    fun fdwResponseDb(ir: DoubleArray, peak: Int = peakIndex(ir), cycles: Double = 15.0, preMs: Double = 1.0, grid: DoubleArray = GRID): DoubleArray {
        val pre = (preMs / 1000 * SAMPLE_RATE).toInt()
        val start = max(0, peak - pre)
        val perWindow = WINDOWS_S.map { seconds ->
            val len = (seconds * SAMPLE_RATE).toInt()
            val n = nextPow2(maxOf(len + pre, 16_384)) // zero padding keeps a fine bin spacing for short windows
            val re = DoubleArray(n); val im = DoubleArray(n)
            for (i in 0 until len + pre) {
                val src = start + i
                if (src >= ir.size) break
                // Short rise before the peak, half-Hann fall over the window's length.
                val w = if (i < pre) 0.5 - 0.5 * cos(PI * i / pre) else 0.5 + 0.5 * cos(PI * (i - pre) / len)
                re[i] = ir[src] * w
            }
            fft(re, im)
            val power = DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
            toGridDb(power, SAMPLE_RATE, n, grid)
        }
        return DoubleArray(grid.size) { g ->
            val want = (cycles / grid[g]).coerceIn(WINDOWS_S.last(), WINDOWS_S.first())
            // Interpolate in dB between the two windows around the wanted length (log spacing).
            val i = WINDOWS_S.indexOfFirst { it <= want }.coerceAtLeast(1)
            val longer = WINDOWS_S[i - 1]; val shorter = WINDOWS_S[i]
            val t = (ln(longer) - ln(want)) / (ln(longer) - ln(shorter))
            perWindow[i - 1][g] * (1 - t) + perWindow[i][g] * t
        }
    }

    /** Level in dB at each [grid] frequency, averaging power over 1/6 octave around it. */
    fun toGridDb(power: DoubleArray, sampleRate: Int, fftSize: Int, grid: DoubleArray = GRID): DoubleArray {
        val hzPerBin = sampleRate.toDouble() / fftSize
        val half = 2.0.pow(1.0 / 12)
        return DoubleArray(grid.size) { g ->
            val lo = max(1, (grid[g] / half / hzPerBin).toInt())
            val hi = max(lo, (grid[g] * half / hzPerBin).toInt()).coerceAtMost(power.size - 1)
            var sum = 0.0
            for (k in lo..hi) sum += power[k]
            10 * log10(sum / (hi - lo + 1) + 1e-20)
        }
    }

    /** RMS level in dB relative to full scale. */
    fun rmsDbfs(samples: FloatArray): Double = 20 * log10(sqrt(samples.sumOf { it.toDouble() * it } / samples.size) + 1e-12)
}
