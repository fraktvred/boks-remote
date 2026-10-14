package dev.boksremote.audio

import dev.boksremote.protocol.ProductModel
import dev.boksremote.speaker.EqCurve
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What one EQ band does, as a standard (RBJ cookbook) filter at the speaker's DSP rate. [scale] and
 * [cutScale] are the real gain per dB set on the speaker for boosts and cuts, measured separately
 * in case "+10 dB" is not exactly 10 dB or cuts don't mirror boosts.
 */
data class BandShape(val type: Type, val hz: Double, val q: Double, val scale: Double = 1.0, val cutScale: Double = scale) {
    enum class Type { PEAK, LOW_SHELF, HIGH_SHELF }

    /** Response in dB at [f] when the band is set to [gainDb] on the speaker. */
    fun responseDb(f: Double, gainDb: Double): Double {
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

/** Per-model band shapes, measured with [BandCalibration]. Null until a model has been measured. */
object BandModels {
    fun forModel(model: ProductModel?): List<BandShape>? = when (model) {
        else -> null
    }
}

/**
 * Turns room measurements into a suggested custom EQ. A reference taken close to the speaker is
 * subtracted from the listening-position measurements, which cancels the speaker's own voicing
 * and the phone microphone's colouring and leaves what the room adds. The six bands are then
 * fitted to undo that, preferring cuts: boosting into a room null wastes power and rarely works.
 */
object RoomCorrection {
    /** Only frequencies where a phone at a few metres measures reliably are corrected. */
    const val MIN_HZ = 45.0
    const val MAX_HZ = 12_000.0
    const val MAX_BOOST = 4
    const val MAX_CUT = -10

    data class Result(val bands: List<Int>, val roomDb: DoubleArray, val beforeRmsDb: Double, val afterRmsDb: Double)

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

    fun fit(room: DoubleArray, shapes: List<BandShape>, grid: DoubleArray = Spectrum.GRID): Result {
        val used = grid.indices.filter { grid[it] in MIN_HZ..MAX_HZ }
        val n = shapes.size
        fun residuals(gains: DoubleArray) = DoubleArray(used.size) { k ->
            val f = grid[used[k]]
            room[used[k]] + (0 until n).sumOf { shapes[it].responseDb(f, gains[it]) }
        }
        // Mean squared error per point, plus a small pull towards 0 so bands don't move for nothing.
        fun cost(gains: DoubleArray) = residuals(gains).sumOf { it * it } / used.size + REGULARISATION * gains.sumOf { it * it }

        val gains = round(continuousFit(::residuals, n, used.size), ::cost)
        val bands = gains.map { it.toInt() }
        fun rms(g: DoubleArray) = sqrt(residuals(g).sumOf { it * it } / used.size)
        return Result(bands, room, rms(DoubleArray(n)), rms(gains))
    }

    private const val REGULARISATION = 0.005

    /**
     * Bounded Levenberg-Marquardt over real-valued gains. Solving all bands together handles
     * overlapping neighbours, where adjusting one band at a time stalls.
     */
    private fun continuousFit(residuals: (DoubleArray) -> DoubleArray, n: Int, points: Int): DoubleArray {
        val lo = MAX_CUT.toDouble(); val hi = MAX_BOOST.toDouble()
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
    private fun round(continuous: DoubleArray, cost: (DoubleArray) -> Double): DoubleArray {
        val g = DoubleArray(continuous.size) { Math.round(continuous[it]).toDouble().coerceIn(MAX_CUT.toDouble(), MAX_BOOST.toDouble()) }
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
                    if (trial[i] !in MAX_CUT.toDouble()..MAX_BOOST.toDouble()) ok = false
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

    fun suggest(reference: DoubleArray, listening: List<DoubleArray>, shapes: List<BandShape>): Result =
        fit(roomEffect(reference, listening), shapes)

    /** Room effects smaller than this are within measurement noise; no correction is worth it. */
    const val NEGLIGIBLE_RMS_DB = 1.5

    fun isNegligible(r: Result) = r.beforeRmsDb < NEGLIGIBLE_RMS_DB || abs(r.beforeRmsDb - r.afterRmsDb) < 0.5
}
