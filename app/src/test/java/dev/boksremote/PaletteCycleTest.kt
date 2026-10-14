package dev.boksremote

import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.CycleConfigStore
import dev.boksremote.speaker.CycleSnapshot
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.speaker.CycleTransition
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.MemoryKeyValue
import dev.boksremote.speaker.PaletteCycle
import dev.boksremote.speaker.PaletteLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaletteCycleTest {
    private val names = listOf("Sunset", "Ocean", "Forest")
    private fun preset(name: String) = LightPalette.PRESETS.first { it.name == name }

    private fun TestScope.cycle(transition: CycleTransition = CycleTransition.INSTANT): PaletteCycle {
        val store = CycleConfigStore(MemoryKeyValue()).apply { save(CycleConfig(names, 60, shuffle = false, transition = transition)) }
        return PaletteCycle(backgroundScope, PaletteLibrary(MemoryKeyValue()), store, { testScheduler.currentTime })
    }

    /** Lets what was just set off run, then [ms] of virtual time pass. */
    private fun TestScope.after(ms: Long = 0) {
        runCurrent()
        if (ms > 0) {
            advanceTimeBy(ms)
            runCurrent()
        }
    }

    @Test
    fun playsInOrderOnePaletteAnInterval() = runTest {
        val light = FakeLight()
        val cycle = cycle()
        cycle.start(light)
        after()
        assertEquals(CycleStatus.Running("Sunset", "Ocean", nextAt = 60_000), cycle.status.value)
        after(60_000)
        after(60_000)
        assertEquals(listOf("Sunset", "Ocean", "Forest"), light.writes)
    }

    @Test
    fun pauseAndResumeKeepTheTimeLeft() = runTest {
        val light = FakeLight()
        val cycle = cycle()
        cycle.start(light)
        after(20_000)
        cycle.hold()
        assertEquals(CycleStatus.Paused("Sunset", "Ocean", onLight = true, remainingMs = 40_000), cycle.status.value)
        after(300_000)
        cycle.start(light)
        after(39_000)
        assertEquals(listOf("Sunset"), light.writes)
        after(1_000)
        assertEquals(listOf("Sunset", "Ocean"), light.writes)
    }

    @Test
    fun cancellingAPreviewPlaysOnFromTheSamePaletteWithItsTimeLeft() = runTest {
        val light = FakeLight()
        val cycle = cycle()
        cycle.start(light)
        after(20_000)
        val preview = cycle.preview(light)
        preview.show(preset("Neon").colors)
        assertFalse(cycle.status.value.running)
        after(5_000)
        preview.cancel()
        after()
        // Back on Sunset rather than on to Ocean, and with the 40 s it had when the preview began.
        assertEquals(listOf("Sunset", "Custom", "Sunset"), light.writes)
        assertEquals(CycleStatus.Running("Sunset", "Ocean", nextAt = 25_000 + 40_000), cycle.status.value)
    }

    @Test
    fun cancellingAPreviewWithCycleOffPutsBackThePaletteThatWasOn() = runTest {
        val light = FakeLight()
        light.setPalette(preset("Forest"))
        val cycle = cycle()
        val preview = cycle.preview(light)
        preview.show(preset("Neon").colors)
        preview.show(preset("Ice").colors)
        preview.cancel()
        assertEquals(listOf("Forest", "Custom", "Custom", "Forest"), light.writes)
        assertEquals(CycleStatus.Stopped, cycle.status.value)
    }

    @Test
    fun aPalettePickedByHandPausesCycleAndResumeGoesOn() = runTest {
        val light = FakeLight()
        val cycle = cycle()
        cycle.start(light)
        after(10_000)
        cycle.showByHand(light, preset("Neon"))
        assertEquals(CycleStatus.Paused("Sunset", "Ocean", onLight = false, remainingMs = null), cycle.status.value)
        after(120_000)
        assertEquals(listOf("Sunset", "Neon"), light.writes)
        cycle.start(light)
        after()
        assertEquals(listOf("Sunset", "Neon", "Ocean"), light.writes)
    }

    @Test
    fun theDefaultColoursPickedByHandPauseCycleToo() = runTest {
        val light = FakeLight()
        val cycle = cycle()
        cycle.start(light)
        after()
        cycle.showByHand(light, null)
        assertEquals(listOf("Sunset", "default"), light.writes)
        assertEquals(true, cycle.status.value.paused)
    }

    @Test
    fun whileTheLightIsLentCycleNeitherStartsNorRuns() = runTest {
        val light = FakeLight()
        val cycle = cycle()
        cycle.start(light)
        after(10_000)
        cycle.lend()
        assertFalse(cycle.canStart())
        cycle.start(light)
        cycle.jumpTo(light, "Forest")
        val running = CycleSnapshot(CycleConfig(names, 60, transition = CycleTransition.INSTANT), running = true, current = "Ocean", remainingMs = 5_000)
        assertFalse(cycle.restore(light, running, showing = true))
        assertEquals(CycleStatus.Paused("Ocean", "Forest", onLight = true, remainingMs = 5_000), cycle.status.value)
        after(120_000)
        assertEquals(listOf("Sunset"), light.writes)
        cycle.giveBack()
        cycle.start(light)
        after(5_000)
        assertEquals(listOf("Sunset", "Forest"), light.writes)
    }

    @Test
    fun aChangeWaitsForTheLightToReconnect() = runTest {
        val light = FakeLight(connected = false)
        val cycle = cycle()
        cycle.start(light)
        after(90_000)
        assertEquals(CycleStatus.Running(null, null, nextAt = null), cycle.status.value)
        assertEquals(emptyList<String>(), light.writes)
        light.connect()
        after()
        assertEquals(listOf("Sunset"), light.writes)
        assertEquals(90_000L + 60_000, (cycle.status.value as CycleStatus.Running).nextAt)
    }

    @Test
    fun stoppingMidFadeLandsOnThePaletteItWasFadingTo() = runTest {
        val light = FakeLight()
        light.setPalette(preset("Forest"))
        val cycle = cycle(CycleTransition.FADE)
        cycle.start(light)
        after(1_000)
        cycle.stop()
        assertEquals(listOf("Forest", "fade:Sunset", "Sunset"), light.writes)
        assertEquals(CycleStatus.Stopped, cycle.status.value)
    }

    @Test
    fun aPaletteTappedWhileStoppedWaitsOnTheLightForResume() = runTest {
        val light = FakeLight()
        val cycle = cycle()
        cycle.jumpTo(light, "Ocean")
        assertEquals(CycleStatus.Paused("Ocean", "Forest", onLight = true, remainingMs = null), cycle.status.value)
        cycle.start(light)
        after(59_000)
        assertEquals(listOf("Ocean"), light.writes)
        after(1_000)
        assertEquals(listOf("Ocean", "Forest"), light.writes)
    }

    @Test
    fun aSnapshotPutsCycleBackWithTheTimeItsPaletteHadLeft() = runTest {
        val light = FakeLight()
        val cycle = cycle()
        cycle.start(light)
        after(45_000)
        val snapshot = cycle.snapshot()
        assertEquals(CycleSnapshot(CycleConfig(names, 60, transition = CycleTransition.INSTANT), running = true, current = "Sunset", remainingMs = 15_000), snapshot)
        cycle.stop()
        assertEquals(true, cycle.restore(light, snapshot))
        after(14_000)
        assertEquals(listOf("Sunset", "Sunset"), light.writes)
        after(1_000)
        assertEquals(listOf("Sunset", "Sunset", "Ocean"), light.writes)
    }
}
