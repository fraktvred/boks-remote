package dev.boksremote.audio

import dev.boksremote.protocol.ProductModel
import dev.boksremote.speaker.EqCurve
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What one EQ band does to the sound, as a function of frequency and the gain set on the speaker. */
interface BandResponse {
    /** Response in dB at [f] when the band is set to [gainDb] on the speaker. */
    fun responseDb(f: Double, gainDb: Double): Double
}

/**
 * What one EQ band does, as a standard (RBJ cookbook) filter at the speaker's DSP rate. [scale] and
 * [cutScale] are the real gain per dB set on the speaker for boosts and cuts, measured separately
 * in case "+10 dB" is not exactly 10 dB or cuts don't mirror boosts.
 */
data class BandShape(val type: Type, val hz: Double, val q: Double, val scale: Double = 1.0, val cutScale: Double = scale) : BandResponse {
    enum class Type { PEAK, LOW_SHELF, HIGH_SHELF }

    override fun responseDb(f: Double, gainDb: Double): Double {
        if (gainDb == 0.0) return 0.0
        val (b, d) = coefficients(gainDb)
        val w = 2 * PI * f / FS
        fun mag2(k: DoubleArray): Double {
            val re = k[0] + k[1] * cos(w) + k[2] * cos(2 * w)
            val im = -(k[1] * sin(w) + k[2] * sin(2 * w))
            return re * re + im * im
        }
        return 10 * log10(mag2(b) / mag2(d))
    }

    /** Biquad numerator and denominator (b0, b1, b2) / (a0, a1, a2) for [gainDb] on the speaker. */
    fun coefficients(gainDb: Double): Pair<DoubleArray, DoubleArray> {
        val a = 10.0.pow(gainDb * (if (gainDb < 0) cutScale else scale) / 40)
        val w0 = 2 * PI * hz / FS
        val alpha = sin(w0) / (2 * q)
        val c = cos(w0)
        return when (type) {
            Type.PEAK -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a) to doubleArrayOf(1 + alpha / a, -2 * c, 1 - alpha / a)
            Type.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                doubleArrayOf(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s)) to
                    doubleArrayOf((a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
            }
            Type.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                doubleArrayOf(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s)) to
                    doubleArrayOf((a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
            }
        }
    }

    private companion object {
        const val FS = 48_000.0
    }
}

/**
 * One band of a crossover-style EQ: lifts or cuts everything between [lowHz] and [highHz] by the
 * gain, with logistic edges [slope] per octave steep. Null [lowHz] or [highHz] leaves that side
 * open, for the lowest and highest bands. Its effect in dB scales with the setting, so +5 dB is
 * half of +10 dB and a cut mirrors a boost.
 */
data class SplitBand(val lowHz: Double?, val highHz: Double?, val slope: Double) : BandResponse {
    override fun responseDb(f: Double, gainDb: Double): Double {
        val x = log2(f)
        val rise = lowHz?.let { 1 / (1 + exp(-slope * (x - log2(it)))) } ?: 1.0
        val fall = highHz?.let { 1 / (1 + exp(slope * (x - log2(it)))) } ?: 1.0
        return gainDb * rise * fall
    }
}

/** Per-model band shapes, measured with [BandCalibration]. Null until a model has been measured. */
object BandModels {
    /**
     * SOUNDBOKS Mix (fw 1.1.1), measured 2026-10-14: six flat-topped bands split at about 100 Hz,
     * 330 Hz, 1 kHz, 3 kHz and 8.3 kHz. Fits each band's +10 and -10 dB within 0.2 dB RMS, +5 dB
     * within 0.2 dB, and a six-band setting within 0.3 dB, so bands add up in dB.
     */
    val MIX = listOf(
        SplitBand(null, 102.0, 8.8),
        SplitBand(100.0, 332.0, 8.5),
        SplitBand(331.0, 994.0, 9.6),
        SplitBand(990.0, 3_029.0, 9.5),
        SplitBand(3_015.0, 8_281.0, 10.5),
        SplitBand(8_261.0, null, 12.1),
    )

    fun forModel(model: ProductModel?): List<BandResponse>? = when (model) {
        ProductModel.SB_MIX -> MIX
        else -> null
    }
}

/** Where the speaker plays. Sets how far the fit may boost. */
enum class Place(val maxBoost: Int) {
    /** Room modes leave narrow, position-dependent nulls; boosting into them wastes power. */
    INDOORS(RoomCorrection.MAX_BOOST),

    /** No walls to reinforce the bass and no modes to boost into, so a broad lift is worth more. */
    OUTDOORS(6),
}

/** The sound the user wants at the listening spots. The room is corrected towards it instead of flat. */
enum class SoundGoal {
    BALANCED,
    BASS,
    VOICES;

    /** Target response in dB at [f], relative to the midrange. */
    fun targetDb(f: Double): Double = when (this) {
        BALANCED -> 0.0
        BASS -> BASS_SHELF.responseDb(f, 6.0)
        VOICES -> VOICE_SHELF.responseDb(f, -3.0) + PRESENCE.responseDb(f, 3.0)
    }

    private companion object {
        val BASS_SHELF = BandShape(BandShape.Type.LOW_SHELF, 120.0, 0.7)
        val VOICE_SHELF = BandShape(BandShape.Type.LOW_SHELF, 150.0, 0.7)
        val PRESENCE = BandShape(BandShape.Type.PEAK, 2_500.0, 0.8)
    }
}

/**
 * Turns room measurements into a suggested custom EQ. A reference taken close to the speaker is
 * subtracted from the listening-position measurements, which cancels the speaker's own voicing
 * and the phone microphone's colouring and leaves what the room adds. The six bands are then
 * fitted so room plus EQ lands on the chosen [SoundGoal], preferring cuts: indoors, boosting into
 * a room null wastes power and rarely works.
 */
object RoomCorrection {
    /** Only frequencies where a phone at a few metres measures reliably are corrected. */
    const val MIN_HZ = 45.0
    const val MAX_HZ = 12_000.0

    /**
     * A measurement microphone hears deep bass and high treble reliably, and being omnidirectional
     * it doesn't change its treble with how it's held. The range is the measuring grid's.
     */
    val EXTERNAL_MIC_RANGE = Spectrum.GRID.first()..Spectrum.GRID.last()
    const val MAX_BOOST = 4
    const val MAX_CUT = -10

    /**
     * [beforeRmsDb] and [afterRmsDb] are the distance from [targetDb] without and with the EQ, over
     * the corrected range. The microphone's own colouring is in both measurements and cancels, so
     * no microphone calibration file is needed.
     */
    data class Result(
        val bands: List<Int>,
        val roomDb: DoubleArray,
        val targetDb: DoubleArray,
        val beforeRmsDb: Double,
        val afterRmsDb: Double,
    )

    /** Averages measurements in the power domain, so one position's deep null doesn't dominate. */
    fun average(measurements: List<DoubleArray>): DoubleArray = DoubleArray(measurements.first().size) { g ->
        10 * log10(measurements.sumOf { 10.0.pow(it[g] / 10) } / measurements.size)
    }

    /** The room's effect on [Spectrum.GRID]: listening minus reference, levelled to 0 dB in the midrange. */
    fun roomEffect(reference: DoubleArray, listening: List<DoubleArray>, grid: DoubleArray = Spectrum.GRID): DoubleArray {
        val avg = average(listening)
        val diff = DoubleArray(grid.size) { avg[it] - reference[it] }
        // The listening spot is further away, so it is quieter overall; only the shape matters. The
        // median ignores a room bump that spills into the midrange, where a mean would shift with it.
        val mid = grid.indices.filter { grid[it] in 300.0..3_000.0 }.map { diff[it] }.sorted()
        val level = mid[mid.size / 2]
        return DoubleArray(grid.size) { diff[it] - level }
    }

    fun fit(
        room: DoubleArray,
        shapes: List<BandResponse>,
        place: Place = Place.INDOORS,
        sound: SoundGoal = SoundGoal.BALANCED,
        range: ClosedFloatingPointRange<Double> = MIN_HZ..MAX_HZ,
        grid: DoubleArray = Spectrum.GRID,
    ): Result {
        val used = grid.indices.filter { grid[it] in range }
        val n = shapes.size
        val target = DoubleArray(grid.size) { sound.targetDb(grid[it]) }
        fun residuals(gains: DoubleArray) = DoubleArray(used.size) { k ->
            val f = grid[used[k]]
            room[used[k]] + (0 until n).sumOf { shapes[it].responseDb(f, gains[it]) } - target[used[k]]
        }
        // Mean squared error per point, plus a small pull towards 0 so bands don't move for nothing.
        fun cost(gains: DoubleArray) = residuals(gains).sumOf { it * it } / used.size + REGULARISATION * gains.sumOf { it * it }

        val gains = round(continuousFit(::residuals, n, used.size, place.maxBoost), place.maxBoost, ::cost)
        val bands = gains.map { it.toInt() }
        fun rms(g: DoubleArray) = sqrt(residuals(g).sumOf { it * it } / used.size)
        return Result(bands, room, target, rms(DoubleArray(n)), rms(gains))
    }

    private const val REGULARISATION = 0.005

    /**
     * Bounded Levenberg-Marquardt over real-valued gains. Solving all bands together handles
     * overlapping neighbours, where adjusting one band at a time stalls.
     */
    private fun continuousFit(residuals: (DoubleArray) -> DoubleArray, n: Int, points: Int, maxBoost: Int): DoubleArray {
        val lo = MAX_CUT.toDouble(); val hi = maxBoost.toDouble()
        // Residuals scaled to match cost(): the regulariser becomes n extra residuals.
        fun all(g: DoubleArray): DoubleArray {
            val r = residuals(g)
            val w = 1 / sqrt(points.toDouble())
            return DoubleArray(r.size + n) { if (it < r.size) r[it] * w else sqrt(REGULARISATION) * g[it - r.size] }
        }
        fun sq(r: DoubleArray) = r.sumOf { it * it }
        var g = DoubleArray(n)
        var r = all(g)
        var lambda = 1e-2
        repeat(60) {
            // Numeric Jacobian; the filter responses are cheap.
            val jac = Array(n) { j ->
                val h = 1e-3
                val gp = g.copyOf().also { it[j] += h }
                val rp = all(gp)
                DoubleArray(r.size) { (rp[it] - r[it]) / h }
            }
            val jtj = Array(n) { a -> DoubleArray(n) { b -> jac[a].indices.sumOf { jac[a][it] * jac[b][it] } } }
            val jtr = DoubleArray(n) { a -> jac[a].indices.sumOf { jac[a][it] * r[it] } }
            while (true) {
                val m = Array(n) { a -> DoubleArray(n) { b -> jtj[a][b] + if (a == b) lambda * (jtj[a][a] + 1e-9) else 0.0 } }
                val step = solve(m, DoubleArray(n) { -jtr[it] }) ?: break
                val next = DoubleArray(n) { (g[it] + step[it]).coerceIn(lo, hi) }
                val rn = all(next)
                if (sq(rn) < sq(r)) {
                    val gain = sq(r) - sq(rn)
                    g = next; r = rn; lambda = maxOf(lambda / 3, 1e-7)
                    if (gain < 1e-10) return g
                    break
                }
                lambda *= 4
                if (lambda > 1e8) return g
            }
        }
        return g
    }

    /**
     * The speaker takes whole dB. Rounds the continuous answer, then improves it by moving one
     * band, or two neighbours together (overlapping bands trade off), a dB at a time.
     */
    private fun round(continuous: DoubleArray, maxBoost: Int, cost: (DoubleArray) -> Double): DoubleArray {
        val range = MAX_CUT.toDouble()..maxBoost.toDouble()
        val g = DoubleArray(continuous.size) { Math.round(continuous[it]).toDouble().coerceIn(range) }
        var best = cost(g)
        val moves = buildList {
            for (i in g.indices) { add(intArrayOf(i) to intArrayOf(1)); add(intArrayOf(i) to intArrayOf(-1)) }
            for (i in 0 until g.size - 1) for (a in listOf(-1, 1)) for (b in listOf(-1, 1)) add(intArrayOf(i, i + 1) to intArrayOf(a, b))
        }
        var improved = true
        while (improved) {
            improved = false
            for ((idx, delta) in moves) {
                val trial = g.copyOf()
                var ok = true
                idx.forEachIndexed { k, i ->
                    trial[i] += delta[k]
                    if (trial[i] !in range) ok = false
                }
                if (!ok) continue
                val c = cost(trial)
                if (c < best - 1e-9) { trial.copyInto(g); best = c; improved = true }
            }
        }
        return g
    }

    /** Gaussian elimination with partial pivoting; null if the system is singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() + b[i] }
        for (c in 0 until n) {
            val p = (c until n).maxBy { abs(m[it][c]) }
            if (abs(m[p][c]) < 1e-12) return null
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) x[r] = (m[r][n] - (r + 1 until n).sumOf { m[r][it] * x[it] }) / m[r][r]
        return x
    }

    fun suggest(
        reference: DoubleArray,
        listening: List<DoubleArray>,
        shapes: List<BandResponse>,
        place: Place = Place.INDOORS,
        sound: SoundGoal = SoundGoal.BALANCED,
        range: ClosedFloatingPointRange<Double> = MIN_HZ..MAX_HZ,
    ): Result = fit(roomEffect(reference, listening), shapes, place, sound, range)

    /** Deviations smaller than this are within measurement noise; no correction is worth it. */
    const val NEGLIGIBLE_RMS_DB = 1.5

    fun isNegligible(r: Result) = r.beforeRmsDb < NEGLIGIBLE_RMS_DB || abs(r.beforeRmsDb - r.afterRmsDb) < 0.5
}
