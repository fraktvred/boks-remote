package dev.boksremote

import dev.boksremote.protocol.Family
import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.CycleConfigStore
import dev.boksremote.speaker.CycleSnapshot
import dev.boksremote.speaker.CycleTransition
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.Feature
import dev.boksremote.speaker.LightFx
import dev.boksremote.speaker.LightContentCodec
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.MemoryKeyValue
import dev.boksremote.speaker.PaletteLibrary
import dev.boksremote.speaker.Scene
import dev.boksremote.speaker.SceneStore
import dev.boksremote.speaker.Stage
import dev.boksremote.speaker.StageStatus
import dev.boksremote.speaker.Unavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StageTest {
    private val ocean = LightPalette.PRESETS.first { it.name == "Ocean" }
    private val neon = LightPalette.PRESETS.first { it.name == "Neon" }

    private class Setup(val light: FakeLight, val scenes: SceneStore, val stage: Stage, val again: () -> Stage) {
        fun saved(name: String) = scenes.scenes.value.first { it.name == name }
        val control get() = stage.control(light)
    }

    private fun TestScope.setup(light: FakeLight = FakeLight()): Setup {
        val clock = { testScheduler.currentTime }
        val library = PaletteLibrary(MemoryKeyValue())
        val config = CycleConfig(listOf("Sunset", "Ocean", "Forest"), 60, transition = CycleTransition.INSTANT)
        val cycleStore = CycleConfigStore(MemoryKeyValue()).apply { save(config) }
        val scenes = SceneStore(MemoryKeyValue())
        fun stage() = Stage(backgroundScope, library, scenes, cycleStore, clock)
        return Setup(light, scenes, stage().apply { attach(light) }, ::stage)
    }

    private fun TestScope.after(ms: Long) {
        runCurrent()
        advanceTimeBy(ms)
        runCurrent()
    }

    /** A connected Lightboks that answers RPC, as the first-scene check and Cycle want. */
    private fun FakeLight.asLightboks() = apply { state.update { it.copy(family = Family.LIGHT, features = setOf(Feature.LIGHT_CONTENT)) } }

    // --- the scene in use ------------------------------------------------------------------------

    @Test
    fun aChangeGoesIntoTheSceneOnceItHolds() = runTest {
        val s = setup()
        s.stage.scenes.save("Party")
        s.control.setBrightness(120)
        after(1_000)
        assertEquals(200, s.saved("Party").brightness)
        after(1_500)
        assertEquals(120, s.saved("Party").brightness)
    }

    @Test
    fun aChangeRightAfterApplyingWaitsOutTheScenesOwnWritesRatherThanBeingLost() = runTest {
        val s = setup()
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.stage.applyScene(s.saved("Calm"))
        after(500)
        s.control.setMode(EnergyLevel.HIGH)
        // Held for 2 s at 2.5 s, but the scene's own writes may land until 3 s.
        after(2_300)
        assertEquals(EnergyLevel.LOW, s.saved("Calm").level)
        after(1_000)
        assertEquals(EnergyLevel.HIGH, s.saved("Calm").level)
    }

    @Test
    fun aSleepFadeNeverPutsItsBrightnessIntoTheScene() = runTest {
        val s = setup()
        s.stage.scenes.save("Night")
        s.stage.startSleep(15)
        after(5 * 60_000)
        assertEquals(200, s.saved("Night").brightness)
        // A change by hand still goes in, without the fade's brightness.
        s.control.setMode(EnergyLevel.HIGH)
        after(3_000)
        assertEquals(EnergyLevel.HIGH, s.saved("Night").level)
        assertEquals(200, s.saved("Night").brightness)
        // Once the user sets the brightness, it is theirs again.
        s.control.setBrightness(90)
        after(3_000)
        assertEquals(90, s.saved("Night").brightness)
        assertEquals(false, s.stage.sleep.value.running)
    }

    @Test
    fun whatTheBridgeShowsStaysOutOfTheSceneWhichComesBackAfterIt() = runTest {
        val s = setup()
        s.light.setPalette(ocean)
        s.stage.scenes.save("Show")
        val lease = s.stage.lend()!!
        // The bridge writes to the light itself.
        s.light.setPalette(neon)
        s.light.setBrightness(10)
        after(10_000)
        assertEquals(ocean, s.saved("Show").palette)
        assertEquals(200, s.saved("Show").brightness)
        lease.end(restore = true)
        assertEquals("Ocean", s.light.writes.last())
        assertEquals(200, s.light.state.value.brightness)
    }

    @Test
    fun leavingASceneKeepsTheExactTimeItsCyclePaletteHadLeft() = runTest {
        val s = setup()
        val config = s.stage.cycle.config.value
        s.scenes.add(Scene("Party", 200, EnergyLevel.HIGH, null, CycleSnapshot(config, running = true)))
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.stage.applyScene(s.saved("Party"))
        after(20_000)
        s.stage.applyScene(s.saved("Calm"))
        assertEquals(CycleSnapshot(config, running = true, current = "Sunset", remainingMs = 40_000), s.saved("Party").cycle)
        // And going back to it plays on from there.
        s.stage.applyScene(s.saved("Party"))
        after(39_000)
        assertEquals(listOf("Sunset", "Ocean", "Sunset"), s.light.writes)
        after(1_000)
        assertEquals("Ocean", s.light.writes.last())
    }

    @Test
    fun theSceneInUseComesBackOnceWithTheLight() = runTest {
        val s = setup(FakeLight(connected = false))
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.scenes.active = "Calm"
        s.again().attach(s.light)
        after(1_000)
        assertEquals(emptyList<String>(), s.light.writes)
        s.light.connect()
        after(0)
        assertEquals(listOf("Ocean"), s.light.writes)
        assertEquals(60, s.light.state.value.brightness)
        s.light.connect(false)
        s.light.connect()
        after(0)
        assertEquals(listOf("Ocean"), s.light.writes)
    }

    @Test
    fun aSceneTheLightRefusedIsNeverOverwrittenWithWhatTheLightStillShows() = runTest {
        val s = setup()
        s.light.setPalette(neon)
        s.stage.scenes.save("Party")
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.light.refusing = true
        s.stage.applyScene(s.saved("Calm"))
        after(10_000)
        assertEquals(Scene("Calm", 60, EnergyLevel.LOW, ocean), s.saved("Calm"))
        // Sent once more after the settle, then left: still refused here, so nothing was saved.
        assertEquals(listOf("Neon", "refused", "refused", "refused", "refused"), s.light.writes)
    }

    @Test
    fun aSceneRefusedOnceIsSentAgainAndThenKeepsChangesAsUsual() = runTest {
        val s = setup()
        s.light.setPalette(neon)
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.light.refusing = true
        s.stage.applyScene(s.saved("Calm"))
        after(1_000)
        s.light.refusing = false
        after(2_500)
        assertEquals(EnergyLevel.LOW, s.light.state.value.energyLevel)
        assertEquals(ocean, s.light.state.value.palette)
        s.control.setMode(EnergyLevel.HIGH)
        after(3_000)
        assertEquals(EnergyLevel.HIGH, s.saved("Calm").level)
        assertEquals(ocean, s.saved("Calm").palette)
    }

    @Test
    fun theFirstLightboksConnectedStartsWithItsLookAsTheSceneInUse() = runTest {
        val s = setup(FakeLight(connected = false).asLightboks())
        s.light.connect()
        after(0)
        assertEquals(listOf(Scene("Default Scene", 200, EnergyLevel.MEDIUM, null, CycleSnapshot(s.stage.cycle.config.value, running = false))), s.scenes.scenes.value)
        assertEquals("Default Scene", s.stage.scenes.active.value)
        // It is a scene like any other: a change goes into it.
        s.control.setBrightness(90)
        after(3_000)
        assertEquals(90, s.saved("Default Scene").brightness)
    }

    @Test
    fun aDeletedFirstSceneIsNotMadeAgain() = runTest {
        val s = setup(FakeLight(connected = false).asLightboks())
        s.light.connect()
        after(0)
        s.stage.scenes.delete(s.saved("Default Scene"))
        s.light.connect(false)
        s.stage.attach(null)
        s.stage.attach(s.light)
        s.light.connect()
        after(0)
        assertEquals(emptyList<Scene>(), s.scenes.scenes.value)
    }

    @Test
    fun noFirstSceneWhereThereAreScenesAlready() = runTest {
        val s = setup(FakeLight(connected = false).asLightboks())
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.scenes.delete(s.saved("Calm"))
        s.light.connect()
        after(0)
        assertEquals(emptyList<Scene>(), s.scenes.scenes.value)
    }

    // --- who drives the scene light ---------------------------------------------------------------

    @Test
    fun aPalettePickedOnAnotherLightLeavesCyclePlayingOnTheSceneLight() = runTest {
        val s = setup()
        val other = FakeLight()
        s.stage.startCycle()
        after(1_000)
        s.stage.control(other).showPalette(neon)
        assertTrue(s.stage.cycle.status.value.running)
        assertEquals(listOf("Neon"), other.writes)
        after(60_000)
        assertEquals(listOf("Sunset", "Ocean"), s.light.writes)
    }

    @Test
    fun cancellingAPreviewOnAnotherLightLeavesCycleWhereItWas() = runTest {
        val s = setup()
        val other = FakeLight()
        s.stage.startCycle()
        after(1_000)
        val preview = s.stage.control(other).preview()
        preview.show(neon.colors)
        preview.cancel()
        assertEquals(listOf("Custom", "default"), other.writes)
        assertTrue(s.stage.cycle.status.value.running)
        after(60_000)
        assertEquals(listOf("Sunset", "Ocean"), s.light.writes)
        assertEquals(2, other.writes.size)
    }

    @Test
    fun aPalettePickedOnTheSceneLightWinsOverCycle() = runTest {
        val s = setup()
        s.stage.startCycle()
        after(1_000)
        s.control.showPalette(neon)
        assertTrue(s.stage.cycle.status.value.paused)
        after(120_000)
        assertEquals(listOf("Sunset", "Neon"), s.light.writes)
    }

    @Test
    fun whileTheLightIsLeasedTheStageWritesNothingToIt() = runTest {
        val s = setup()
        s.light.setPalette(ocean)
        val lease = s.stage.lend()!!
        s.control.showPalette(neon)
        s.control.updateShown(neon)
        s.control.preview().show(neon.colors)
        s.control.setBrightness(10)
        s.control.setMode(EnergyLevel.HIGH)
        s.stage.startCycle()
        s.stage.jumpTo("Forest")
        s.stage.startSleep(15)
        s.stage.applyScene(Scene("Calm", 60, EnergyLevel.LOW, neon))
        after(120_000)
        assertEquals(listOf("Ocean"), s.light.writes)
        assertEquals(200, s.light.state.value.brightness)
        assertEquals(EnergyLevel.MEDIUM, s.light.state.value.energyLevel)
        assertFalse(s.stage.cycle.status.value.running)
        assertFalse(s.stage.sleep.value.running)
        // Another light is still the user's.
        val other = FakeLight()
        s.stage.control(other).showPalette(neon)
        assertEquals(listOf("Neon"), other.writes)
        lease.end(restore = false)
        s.control.showPalette(neon)
        assertEquals(listOf("Ocean", "Neon"), s.light.writes)
    }

    @Test
    fun aLeaseEndsWhenItsLightStopsBeingTheSceneLight() = runTest {
        val s = setup()
        val lease = s.stage.lend()!!
        val next = FakeLight()
        s.stage.attach(next)
        assertFalse(s.stage.status.value.leased)
        s.stage.control(next).showPalette(neon)
        assertEquals(listOf("Neon"), next.writes)
        // Ending it later changes nothing on either light.
        lease.end(restore = true)
        assertEquals(emptyList<String>(), s.light.writes)
        assertEquals(listOf("Neon"), next.writes)
    }

    @Test
    fun theBridgeWritesThroughItsLeaseWhichTheStageEndsWhenTheLightMovesOn() = runTest {
        val s = setup()
        var ended = 0
        val lease = s.stage.lend(onEnd = { ended++ })!!
        lease.output.applyBrightness(40)
        lease.output.applyFx(LightFx.STROBE.id)
        assertEquals(listOf("brightness:40", "fx:STROBE"), s.light.writes)
        s.stage.attach(FakeLight())
        after(0)
        assertEquals(1, ended)
        // The effect the bridge left running stops; what it sends after that goes nowhere.
        assertEquals("fx:none", s.light.writes.last())
        lease.output.applyPalette(neon)
        assertEquals(listOf("brightness:40", "fx:STROBE", "fx:none"), s.light.writes)
    }

    @Test
    fun endingTheLeaseByHandStopsTheBridgesEffectButIsNoEndTheStageReports() = runTest {
        val s = setup()
        var ended = 0
        val lease = s.stage.lend(onEnd = { ended++ })!!
        lease.output.applyFx(LightFx.CHASE.id)
        lease.end(restore = false)
        after(0)
        assertEquals(listOf("fx:CHASE", "fx:none"), s.light.writes)
        assertEquals(0, ended)
        // No effect left running, nothing to stop.
        val again = s.stage.lend()!!
        again.output.applyFx(LightFx.CHASE.id)
        again.output.applyFx(LightContentCodec.NO_ID)
        again.end(restore = false)
        after(0)
        assertEquals(listOf("fx:CHASE", "fx:none", "fx:CHASE", "fx:none"), s.light.writes)
    }

    @Test
    fun poweringOffTheSceneLightStopsCycleAndSleepFade() = runTest {
        val s = setup()
        s.stage.startCycle()
        s.stage.startSleep(15)
        after(1_000)
        s.control.powerOff()
        assertFalse(s.stage.cycle.status.value.running)
        assertFalse(s.stage.sleep.value.running)
        after(120_000)
        assertEquals(listOf("Sunset", "off"), s.light.writes)
    }

    @Test
    fun attachingAnotherLightSavesTheSceneAsTheOldOneWasLeftAndPutsItOnTheNewOne() = runTest {
        val s = setup()
        s.light.setPalette(ocean)
        s.stage.scenes.save("Show")
        s.stage.startCycle()
        after(1_000)
        // Not held long enough to be synced yet: leaving saves it exactly.
        s.control.showPalette(neon)
        val next = FakeLight(connected = false)
        s.stage.attach(next)
        assertEquals(neon, s.saved("Show").palette)
        assertFalse(s.stage.cycle.status.value.running)
        next.connect()
        after(0)
        assertEquals("Neon", next.writes.last())
        after(120_000)
        assertEquals(listOf("Ocean", "Sunset", "Neon"), s.light.writes)
    }

    @Test
    fun theStatusSaysWhatCanStart() = runTest {
        val s = setup(FakeLight().asLightboks())
        after(0)
        assertEquals(StageStatus(leased = false, cycle = null, sleep = null), s.stage.status.value)
        val lease = s.stage.lend()!!
        after(0)
        assertEquals(StageStatus(leased = true, cycle = Unavailable.LEASED, sleep = Unavailable.LEASED), s.stage.status.value)
        lease.end(restore = false)
        s.light.state.update { it.copy(features = emptySet()) }
        after(0)
        assertEquals(Unavailable.UNSUPPORTED, s.stage.status.value.cycle)
        assertNull(s.stage.status.value.sleep)
        s.stage.attach(null)
        after(0)
        assertEquals(StageStatus(), s.stage.status.value)
    }
}
