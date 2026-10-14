package dev.boksremote

import dev.boksremote.audio.BandModels
import dev.boksremote.audio.BandShape
import dev.boksremote.audio.BandShape.Type
import dev.boksremote.audio.Place
import dev.boksremote.audio.RoomCorrection
import dev.boksremote.audio.SoundGoal
import dev.boksremote.audio.Spectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RoomCorrectionTest {
    private val grid = Spectrum.GRID
    private fun at(hz: Double) = grid.indices.minBy { kotlin.math.abs(grid[it] - hz) }

    /** A plausible six-band layout, only for testing the maths. */
    private val shapes = listOf(
        BandShape(Type.LOW_SHELF, 80.0, 0.7),
        BandShape(Type.PEAK, 200.0, 1.0),
        BandShape(Type.PEAK, 600.0, 1.0),
        BandShape(Type.PEAK, 1_800.0, 1.0),
        BandShape(Type.PEAK, 5_000.0, 1.0),
        BandShape(Type.HIGH_SHELF, 10_000.0, 0.7),
    )

    private val sweep = Spectrum.logSweep(2.0)

    /** What a microphone would record: [sweep] through [system], arriving after [delay] samples. */
    private fun recordThrough(delay: Int, system: (FloatArray) -> FloatArray): FloatArray {
        val out = FloatArray(delay + sweep.size + 48_000)
        system(sweep).copyInto(out, delay)
        return out
    }

    private fun biquad(x: FloatArray, shape: BandShape, gainDb: Double): FloatArray {
        val (b, a) = shape.coefficients(gainDb)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return FloatArray(x.size) { i ->
            val y = (b[0] * x[i] + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2) / a[0]
            x2 = x1; x1 = x[i].toDouble(); y2 = y1; y1 = y
            y.toFloat()
        }
    }

    private fun inBand(db: DoubleArray) = grid.indices.filter { grid[it] in 50.0..15_000.0 }.map { db[it] }

    @Test
    fun sweepFindsDelayAndLevel() {
        val rec = recordThrough(9_000) { x -> FloatArray(x.size) { x[it] * 0.25f } }
        val ir = Spectrum.impulseResponse(rec, sweep)
        assertEquals(9_000, Spectrum.peakIndex(ir))
        val db = Spectrum.fdwResponseDb(ir)
        // A pure gain of 0.25 must read as one flat level everywhere.
        val levels = inBand(db)
        assertTrue("spread ${levels.max() - levels.min()}", levels.max() - levels.min() < 0.5)
    }

    @Test
    fun sweepIgnoresHarmonicDistortion() {
        val clean = Spectrum.fdwResponseDb(Spectrum.impulseResponse(recordThrough(5_000) { it }, sweep))
        // 2nd and 3rd harmonics at -20 dB, as a speaker pushed hard would add.
        val distorted = recordThrough(5_000) { x -> FloatArray(x.size) { x[it] + 0.2f * x[it] * x[it] + 0.2f * x[it] * x[it] * x[it] } }
        val db = Spectrum.fdwResponseDb(Spectrum.impulseResponse(distorted, sweep))
        val diff = inBand(db).zip(inBand(clean)).maxOf { (a, b) -> kotlin.math.abs(a - b) }
        assertTrue("distortion changed the response by $diff dB", diff < 1.0)
    }

    @Test
    fun shortWindowsLeaveOutLateEchoesInTheTreble() {
        // A strong echo 20 ms later: it combs the bass (long windows) but not the treble.
        val echo = 960
        val rec = recordThrough(4_000) { x -> FloatArray(x.size + echo) { i -> (if (i < x.size) x[i] else 0f) + (if (i >= echo) 0.7f * x[i - echo] else 0f) } }
        val db = Spectrum.fdwResponseDb(Spectrum.impulseResponse(rec, sweep))
        val treble = grid.indices.filter { grid[it] in 2_000.0..12_000.0 }.map { db[it] }
        assertTrue("treble spread ${treble.max() - treble.min()}", treble.max() - treble.min() < 1.5)
        val bass = grid.indices.filter { grid[it] in 40.0..150.0 }.map { db[it] }
        assertTrue("bass should show the echo", bass.max() - bass.min() > 3)
    }

    @Test
    fun sweepMeasuresAFilter() {
        val band = BandShape(Type.PEAK, 1_000.0, 1.4)
        val flat = Spectrum.fdwResponseDb(Spectrum.impulseResponse(recordThrough(3_000) { it }, sweep))
        val boosted = Spectrum.fdwResponseDb(Spectrum.impulseResponse(recordThrough(3_000) { biquad(it, band, 8.0) }, sweep))
        assertEquals(8.0, boosted[at(1_000.0)] - flat[at(1_000.0)], 0.6)
        assertEquals(0.0, boosted[at(100.0)] - flat[at(100.0)], 0.3)
    }

    @Test
    fun bandShapesHitTheirGain() {
        assertEquals(6.0, BandShape(Type.PEAK, 1_000.0, 1.0).responseDb(1_000.0, 6.0), 0.01)
        assertEquals(0.0, BandShape(Type.PEAK, 1_000.0, 1.0).responseDb(20.0, 6.0), 0.1)
        assertEquals(-8.0, BandShape(Type.LOW_SHELF, 200.0, 0.7).responseDb(20.0, -8.0), 0.2)
        assertEquals(5.0, BandShape(Type.HIGH_SHELF, 2_000.0, 0.7).responseDb(16_000.0, 5.0), 0.3)
    }

    @Test
    fun cutsABassBoom() {
        // A room mode adds +7 dB around 200 Hz.
        val room = DoubleArray(grid.size) { BandShape(Type.PEAK, 200.0, 1.0).responseDb(grid[it], 7.0) }
        val r = RoomCorrection.fit(room, shapes)
        assertTrue("band 2 should cut: ${r.bands}", r.bands[1] in -8..-6)
        assertTrue(r.afterRmsDb < r.beforeRmsDb / 3)
    }

    @Test
    fun limitsBoostsIntoANull() {
        val room = DoubleArray(grid.size) { BandShape(Type.PEAK, 600.0, 1.0).responseDb(grid[it], -9.0) }
        assertEquals(RoomCorrection.MAX_BOOST, RoomCorrection.fit(room, shapes).bands[2])
    }

    @Test
    fun roomEffectCancelsSpeakerAndMicrophone() {
        // Whatever the speaker/mic colouring is, it is in both measurements and drops out.
        val colouring = DoubleArray(grid.size) { sin(it / 5.0) * 6 }
        val roomBump = DoubleArray(grid.size) { BandShape(Type.PEAK, 200.0, 1.0).responseDb(grid[it], 5.0) }
        val reference = DoubleArray(grid.size) { colouring[it] - 10 }
        val listening = DoubleArray(grid.size) { colouring[it] + roomBump[it] - 30 } // further away: quieter
        val effect = RoomCorrection.roomEffect(reference, listOf(listening))
        assertEquals(5.0, effect[at(200.0)], 0.5)
        assertEquals(0.0, effect[at(10_000.0)], 0.5)
    }

    @Test
    fun flatRoomNeedsNothing() {
        val r = RoomCorrection.fit(DoubleArray(grid.size), shapes)
        assertEquals(List(6) { 0 }, r.bands)
        assertTrue(RoomCorrection.isNegligible(r))
    }

    @Test
    fun solvesOverlappingBandsTogether() {
        // Bands 2 and 3 overlap heavily; a room that needs them to pull in opposite directions.
        val overlapping = listOf(
            BandShape(Type.LOW_SHELF, 80.0, 0.7),
            BandShape(Type.PEAK, 220.0, 0.6),
            BandShape(Type.PEAK, 330.0, 0.6),
            BandShape(Type.PEAK, 1_800.0, 1.0),
            BandShape(Type.PEAK, 5_000.0, 1.0),
            BandShape(Type.HIGH_SHELF, 10_000.0, 0.7),
        )
        val target = listOf(-4, -7, 3, 0, -2, 1)
        val room = DoubleArray(grid.size) { g -> -overlapping.indices.sumOf { overlapping[it].responseDb(grid[g], target[it].toDouble()) } }
        val r = RoomCorrection.fit(room, overlapping)
        assertTrue("got ${r.bands}", r.afterRmsDb < 0.4)
        // Overlapping bands can trade off; an equally good answer with smaller moves is better.
        assertTrue("got ${r.bands}, want at most the moves of $target", r.bands.sumOf { it * it } <= target.sumOf { it * it })
    }

    @Test
    fun bassGoalLiftsTheLowEndOfAFlatRoom() {
        val r = RoomCorrection.fit(DoubleArray(grid.size), shapes, Place.OUTDOORS, SoundGoal.BASS)
        assertTrue("got ${r.bands}", r.bands[0] >= 4)
        assertTrue("treble should stay put: ${r.bands}", r.bands[5] in -1..1)
        assertFalse(RoomCorrection.isNegligible(r))
    }

    @Test
    fun bassGoalKeepsARoomsBassBumpInsteadOfCuttingIt() {
        // Indoors the room already adds the low end a bass lover wants; only balanced cuts it.
        val room = DoubleArray(grid.size) { BandShape(Type.LOW_SHELF, 120.0, 0.7).responseDb(grid[it], 6.0) }
        val balanced = RoomCorrection.fit(room, shapes, Place.INDOORS, SoundGoal.BALANCED)
        val bass = RoomCorrection.fit(room, shapes, Place.INDOORS, SoundGoal.BASS)
        assertTrue("got ${balanced.bands}", balanced.bands[0] <= -4)
        assertTrue("got ${bass.bands}", bass.bands[0] in -1..1)
    }

    @Test
    fun outdoorsMayBoostFurther() {
        val room = DoubleArray(grid.size) { BandShape(Type.PEAK, 600.0, 1.0).responseDb(grid[it], -9.0) }
        assertEquals(Place.OUTDOORS.maxBoost, RoomCorrection.fit(room, shapes, Place.OUTDOORS).bands[2])
    }

    @Test
    fun mixBandsAreFlatToppedWithSteepEdges() {
        val band2 = BandModels.MIX[1]
        assertEquals(10.0, band2.responseDb(180.0, 10.0), 0.5)
        assertEquals(-10.0, band2.responseDb(180.0, -10.0), 0.5)
        assertEquals(5.0, band2.responseDb(180.0, 5.0), 0.25)
        assertEquals(0.0, band2.responseDb(600.0, 10.0), 0.5)
        assertEquals(0.0, band2.responseDb(50.0, 10.0), 0.5)
    }

    @Test
    fun mixCutsABassBoom() {
        val room = DoubleArray(grid.size) { BandShape(Type.PEAK, 180.0, 1.4).responseDb(grid[it], 7.0) }
        val r = RoomCorrection.fit(room, BandModels.MIX)
        assertTrue("band 2 should cut: ${r.bands}", r.bands[1] <= -4)
        assertTrue("treble should stay put: ${r.bands}", r.bands[5] in -1..1)
        assertTrue("${r.beforeRmsDb} -> ${r.afterRmsDb}", r.afterRmsDb < r.beforeRmsDb / 2)
    }

    @Test
    fun mixBassGoalLiftsTheLowestBand() {
        val r = RoomCorrection.fit(DoubleArray(grid.size), BandModels.MIX, Place.OUTDOORS, SoundGoal.BASS)
        assertTrue("got ${r.bands}", r.bands[0] >= 4)
        assertTrue("got ${r.bands}", r.bands.drop(2).all { it in -1..1 })
    }

    @Test
    fun cutsUseTheirOwnScale() {
        val band = BandShape(Type.PEAK, 1_000.0, 1.0, scale = 0.8, cutScale = 0.6)
        assertEquals(8.0, band.responseDb(1_000.0, 10.0), 0.01)
        assertEquals(-6.0, band.responseDb(1_000.0, -10.0), 0.01)
    }
}
