package dev.boksremote

import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.CycleConfigCodec
import dev.boksremote.speaker.CycleConfigStore
import dev.boksremote.speaker.CycleTransition
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.MemoryKeyValue
import dev.boksremote.speaker.PaletteLibrary
import dev.boksremote.speaker.Scene
import dev.boksremote.speaker.SceneStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoresTest {
    @Test
    fun cycleConfigRoundTrips() {
        val c = CycleConfig(listOf("Synthwave", "Mine, with a comma"), 300, shuffle = true, transition = CycleTransition.INSTANT)
        assertEquals(c, CycleConfigCodec.decode(CycleConfigCodec.encode(c)))
        assertNull(CycleConfigCodec.decode("45|false|A|FADE"))
        val kv = MemoryKeyValue()
        CycleConfigStore(kv).save(c)
        assertEquals(c, CycleConfigStore(kv).load())
    }

    @Test
    fun cycleConfigReadsTheKeysItWasSavedUnderBefore() {
        val kv = MemoryKeyValue(mapOf("names" to "A\nB", "interval" to "120", "shuffle" to "true", "transition" to "INSTANT"))
        assertEquals(CycleConfig(listOf("A", "B"), 120, shuffle = true, transition = CycleTransition.INSTANT), CycleConfigStore(kv).load())
        // Before the transition could be chosen.
        assertEquals(CycleTransition.FADE, CycleConfigStore(MemoryKeyValue(mapOf("names" to "A", "interval" to "60", "shuffle" to "false"))).load().transition)
        assertEquals(CycleConfig(), CycleConfigStore(MemoryKeyValue()).load())
    }

    @Test
    fun scenesGetUniqueNamesAndKeepTheirPlace() {
        val kv = MemoryKeyValue()
        val store = SceneStore(kv)
        val a = store.add(Scene("Party", 200, EnergyLevel.HIGH, null))
        val b = store.add(Scene("Party", 100, EnergyLevel.LOW, null))
        assertEquals("Party 2", b.name)
        store.replace(a, a.copy(name = "Late"))
        store.move(1, 0)
        store.active = "Late"
        val again = SceneStore(kv)
        assertEquals(listOf("Party 2", "Late"), again.scenes.value.map { it.name })
        assertEquals("Late", again.active)
    }

    @Test
    fun savedPalettesNeverTakeAPresetName() {
        val colors = LightPalette.PRESETS[0].colors
        val store = PaletteLibrary(MemoryKeyValue())
        assertEquals("Galaxy 2", store.add("Galaxy", colors).name)
        val mine = store.add("Mine", colors)
        val undo = store.delete(mine)
        store.add("Mine", colors)
        undo()
        // Back in its place, under a new name since its own was taken meanwhile.
        assertEquals(listOf("Galaxy 2", "Mine 2", "Mine"), store.palettes.value.map { it.name })
    }
}
