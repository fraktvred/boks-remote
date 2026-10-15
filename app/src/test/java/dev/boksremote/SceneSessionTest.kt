package dev.boksremote

import dev.boksremote.protocol.Family
import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.CycleConfigStore
import dev.boksremote.speaker.CycleSnapshot
import dev.boksremote.speaker.CycleTransition
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.Light
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.MemoryKeyValue
import dev.boksremote.speaker.PaletteCycle
import dev.boksremote.speaker.PaletteLibrary
import dev.boksremote.speaker.Scene
import dev.boksremote.speaker.SceneSession
import dev.boksremote.speaker.SceneStore
import dev.boksremote.speaker.SleepFade
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SceneSessionTest {
    private val ocean = LightPalette.PRESETS.first { it.name == "Ocean" }
    private val neon = LightPalette.PRESETS.first { it.name == "Neon" }

    private class Setup(val light: FakeLight, val scenes: SceneStore, val cycle: PaletteCycle, val sleep: SleepFade, val session: SceneSession) {
        fun saved(name: String) = scenes.scenes.value.first { it.name == name }
    }

    private fun TestScope.setup(light: FakeLight = FakeLight()): Setup {
        val clock = { testScheduler.currentTime }
        val library = PaletteLibrary(MemoryKeyValue())
        val config = CycleConfig(listOf("Sunset", "Ocean", "Forest"), 60, transition = CycleTransition.INSTANT)
        val cycle = PaletteCycle(backgroundScope, library, CycleConfigStore(MemoryKeyValue()).apply { save(config) }, clock)
        val scenes = SceneStore(MemoryKeyValue())
        val sleep = SleepFade(backgroundScope, clock)
        val session = SceneSession(backgroundScope, MutableStateFlow<Light?>(light), scenes, cycle, sleep, clock)
        return Setup(light, scenes, cycle, sleep, session)
    }

    private fun TestScope.after(ms: Long) {
        runCurrent()
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun aChangeGoesIntoTheSceneOnceItHolds() = runTest {
        val s = setup()
        s.session.save("Party")
        s.session.setBrightness(120)
        after(1_000)
        assertEquals(200, s.saved("Party").brightness)
        after(1_500)
        assertEquals(120, s.saved("Party").brightness)
    }

    @Test
    fun aChangeRightAfterApplyingWaitsOutTheScenesOwnWritesRatherThanBeingLost() = runTest {
        val s = setup()
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.session.apply(s.saved("Calm"))
        after(500)
        s.light.setEnergyLevel(EnergyLevel.HIGH)
        // Held for 2 s at 2.5 s, but the scene's own writes may land until 3 s.
        after(2_300)
        assertEquals(EnergyLevel.LOW, s.saved("Calm").level)
        after(1_000)
        assertEquals(EnergyLevel.HIGH, s.saved("Calm").level)
    }

    @Test
    fun aSleepFadeNeverPutsItsBrightnessIntoTheScene() = runTest {
        val s = setup()
        s.session.save("Night")
        s.sleep.start(s.light, 15)
        after(5 * 60_000)
        assertEquals(200, s.saved("Night").brightness)
        // A change by hand still goes in, without the fade's brightness.
        s.light.setEnergyLevel(EnergyLevel.HIGH)
        after(3_000)
        assertEquals(EnergyLevel.HIGH, s.saved("Night").level)
        assertEquals(200, s.saved("Night").brightness)
        // Once the user sets the brightness, it is theirs again.
        s.session.setBrightness(90)
        after(3_000)
        assertEquals(90, s.saved("Night").brightness)
        assertEquals(false, s.sleep.status.value.running)
    }

    @Test
    fun whatTheBridgeShowsStaysOutOfTheSceneWhichComesBackAfterIt() = runTest {
        val s = setup()
        s.light.setPalette(ocean)
        s.session.save("Show")
        s.session.lend()
        s.light.setPalette(neon)
        s.light.setBrightness(10)
        after(10_000)
        assertEquals(ocean, s.saved("Show").palette)
        assertEquals(200, s.saved("Show").brightness)
        s.session.giveBack(reapply = true)
        assertEquals("Ocean", s.light.writes.last())
        assertEquals(200, s.light.state.value.brightness)
    }

    @Test
    fun leavingASceneKeepsTheExactTimeItsCyclePaletteHadLeft() = runTest {
        val s = setup()
        s.scenes.add(Scene("Party", 200, EnergyLevel.HIGH, null, CycleSnapshot(s.cycle.config.value, running = true)))
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.session.apply(s.saved("Party"))
        after(20_000)
        s.session.apply(s.saved("Calm"))
        assertEquals(CycleSnapshot(s.cycle.config.value, running = true, current = "Sunset", remainingMs = 40_000), s.saved("Party").cycle)
        // And going back to it plays on from there.
        s.session.apply(s.saved("Party"))
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
        val again = SceneSession(backgroundScope, MutableStateFlow<Light?>(s.light), s.scenes, s.cycle, s.sleep) { testScheduler.currentTime }
        again.connecting()
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
        s.session.save("Party")
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.light.refusing = true
        s.session.apply(s.saved("Calm"))
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
        s.session.apply(s.saved("Calm"))
        after(1_000)
        s.light.refusing = false
        after(2_500)
        assertEquals(EnergyLevel.LOW, s.light.state.value.energyLevel)
        assertEquals(ocean, s.light.state.value.palette)
        s.light.setEnergyLevel(EnergyLevel.HIGH)
        after(3_000)
        assertEquals(EnergyLevel.HIGH, s.saved("Calm").level)
        assertEquals(ocean, s.saved("Calm").palette)
    }

    /** A connected Lightboks that answers RPC, as the first-scene check wants. */
    private fun FakeLight.asLightboks() = apply { state.update { it.copy(family = Family.LIGHT, rpcAvailable = true) } }

    @Test
    fun theFirstLightboksConnectedStartsWithItsLookAsTheSceneInUse() = runTest {
        val s = setup(FakeLight(connected = false).asLightboks())
        s.session.connecting()
        s.light.connect()
        after(0)
        assertEquals(listOf(Scene("Default Scene", 200, EnergyLevel.MEDIUM, null, s.cycle.snapshot())), s.scenes.scenes.value)
        assertEquals("Default Scene", s.session.active.value)
        // It is a scene like any other: a change goes into it.
        s.session.setBrightness(90)
        after(3_000)
        assertEquals(90, s.saved("Default Scene").brightness)
    }

    @Test
    fun aDeletedFirstSceneIsNotMadeAgain() = runTest {
        val s = setup(FakeLight(connected = false).asLightboks())
        s.session.connecting()
        s.light.connect()
        after(0)
        s.session.delete(s.saved("Default Scene"))
        s.light.connect(false)
        s.session.connecting()
        s.light.connect()
        after(0)
        assertEquals(emptyList<Scene>(), s.scenes.scenes.value)
    }

    @Test
    fun noFirstSceneWhereThereAreScenesAlready() = runTest {
        val s = setup(FakeLight(connected = false).asLightboks())
        s.scenes.add(Scene("Calm", 60, EnergyLevel.LOW, ocean))
        s.scenes.delete(s.saved("Calm"))
        s.session.connecting()
        s.light.connect()
        after(0)
        assertEquals(emptyList<Scene>(), s.scenes.scenes.value)
    }
}
