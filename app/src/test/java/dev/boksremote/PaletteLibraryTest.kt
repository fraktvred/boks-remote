package dev.boksremote

import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.CycleConfigStore
import dev.boksremote.speaker.CycleSnapshot
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.MemoryKeyValue
import dev.boksremote.speaker.PaletteCycle
import dev.boksremote.speaker.PaletteKind
import dev.boksremote.speaker.PaletteLibrary
import dev.boksremote.speaker.Scene
import dev.boksremote.speaker.SceneStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaletteLibraryTest {
    private val colors = LightPalette.PRESETS[3].colors

    private class Setup(val library: PaletteLibrary, val cycle: PaletteCycle, val scenes: SceneStore)

    private fun TestScope.setup(): Setup {
        val library = PaletteLibrary(MemoryKeyValue())
        val mine = library.add("Mine", colors)
        val names = listOf("Sunset", mine.name, "Ocean")
        val cycle = PaletteCycle(backgroundScope, library, CycleConfigStore(MemoryKeyValue()).apply { save(CycleConfig(names)) }, { testScheduler.currentTime })
        val scenes = SceneStore(MemoryKeyValue())
        library.register(scenes)
        scenes.add(Scene("Party", 200, EnergyLevel.HIGH, mine, CycleSnapshot(CycleConfig(names), running = true, current = mine.name)))
        scenes.add(Scene("Calm", 60, EnergyLevel.LOW, null, CycleSnapshot(CycleConfig(listOf("Ocean", "Sunset")), running = false)))
        return Setup(library, cycle, scenes)
    }

    @Test
    fun aRenameReachesCycleAndEveryScene() = runTest {
        val s = setup()
        val light = FakeLight()
        s.cycle.jumpTo(light, "Mine")
        s.library.rename(s.library.palettes.value.single(), "Ours")
        assertEquals(listOf("Sunset", "Ours", "Ocean"), s.cycle.config.value.names)
        assertEquals("Ours", s.cycle.status.value.current)
        val party = s.scenes.scenes.value.first()
        assertEquals(listOf("Sunset", "Ours", "Ocean"), party.cycle!!.config.names)
        assertEquals("Ours", party.cycle!!.current)
        assertEquals("Ours", party.palette!!.name)
        // Applied later, the scene's Cycle still finds the palette.
        s.cycle.stop()
        s.cycle.restore(light, party.cycle!!)
        runCurrent()
        assertEquals(listOf("Sunset", "Ours", "Ocean"), s.cycle.palettes().map { it.name })
        assertEquals(CycleStatus.Running("Ours", "Ocean", nextAt = 60_000), s.cycle.status.value)
    }

    @Test
    fun aDeleteDropsThePaletteEverywhereAndUndoPutsItBackInPlace() = runTest {
        val s = setup()
        val undo = s.library.delete(s.library.palettes.value.single())
        assertEquals(listOf("Sunset", "Ocean"), s.cycle.config.value.names)
        assertEquals(listOf("Sunset", "Ocean"), s.scenes.scenes.value.first().cycle!!.config.names)
        // The scene keeps its own copy of the colours.
        assertEquals("Mine", s.scenes.scenes.value.first().palette!!.name)
        undo()
        assertEquals(listOf("Sunset", "Mine", "Ocean"), s.cycle.config.value.names)
        assertEquals(listOf("Sunset", "Mine", "Ocean"), s.scenes.scenes.value.first().cycle!!.config.names)
        assertEquals(listOf("Ocean", "Sunset"), s.scenes.scenes.value.last().cycle!!.config.names)
    }

    @Test
    fun palettesAreKnownForWhatTheyAre() {
        val library = PaletteLibrary(MemoryKeyValue())
        val mine = library.add("Mine", colors)
        assertEquals(PaletteKind.DEFAULT, library.kind(null))
        assertEquals(PaletteKind.PRESET, library.kind(LightPalette.PRESETS[0]))
        assertEquals(PaletteKind.YOURS, library.kind(mine))
        assertEquals(PaletteKind.UNSAVED, library.kind(LightPalette("Custom", colors)))
        assertEquals(mine, library.resolve("Mine"))
        assertEquals(LightPalette.PRESETS[0], library.resolve(LightPalette.PRESETS[0].name))
    }
}
