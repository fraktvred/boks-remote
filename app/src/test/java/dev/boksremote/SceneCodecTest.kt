package dev.boksremote

import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.CycleSnapshot
import dev.boksremote.speaker.CycleTransition
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.Scene
import dev.boksremote.speaker.SceneCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneCodecTest {
    @Test
    fun roundTripsScenes() {
        val list = listOf(
            Scene("Peak time", 255, EnergyLevel.HIGH, LightPalette.PRESETS[9]),
            Scene("Wind down", 60, EnergyLevel.LOW, null),
            Scene("Uplight", 128, EnergyLevel.CUSTOM, LightPalette.PRESETS[0]),
            Scene("Party", 200, EnergyLevel.HIGH, LightPalette.PRESETS[1], CycleSnapshot(CycleConfig(listOf("Synthwave", "Mine, with a comma"), 300, shuffle = true), running = true, current = "Synthwave", remainingMs = 42_000)),
            Scene("Cycle set, not running", 90, EnergyLevel.MEDIUM, null, CycleSnapshot(CycleConfig(listOf("A", "B"), 30, shuffle = false, transition = CycleTransition.INSTANT), running = false, current = "B")),
            Scene("Nothing known", 90, EnergyLevel.MEDIUM, null, CycleSnapshot(CycleConfig(listOf("A", "B"), 30, shuffle = false), running = false)),
        )
        assertEquals(list, SceneCodec.decode(SceneCodec.encode(list)))
    }

    @Test
    fun skipsMalformedLines() {
        val good = SceneCodec.encode(listOf(Scene("Ok", 10, EnergyLevel.MEDIUM, null)))
        assertEquals(1, SceneCodec.decode("x|300|LOW\ny|10|LOUD\nz|10|LOW|broken\n$good").size)
    }

    @Test
    fun cyclesSavedBeforeTheRunningFlagWereRunning() {
        val line = "Old|200|HIGH|||||" + "|60|false|A\u001FB"
        assertEquals(Scene("Old", 200, EnergyLevel.HIGH, null, CycleSnapshot(CycleConfig(listOf("A", "B"), 60, false), running = true)), SceneCodec.decode(line).single())
        // Then a running flag, still without the palette showing or its time left.
        assertEquals(CycleSnapshot(CycleConfig(listOf("A", "B"), 60, false), running = false), SceneCodec.decode("$line|false").single().cycle)
    }

    @Test
    fun readsTheFirstFormat() {
        val p = LightPalette.PRESETS[0]
        val old = "Plain|60|LOW\nWith palette|255|HIGH|${p.name}|" + p.colors.joinToString("|") { "${it.hue},${it.saturation},${it.value}" }
        assertEquals(
            listOf(Scene("Plain", 60, EnergyLevel.LOW, null), Scene("With palette", 255, EnergyLevel.HIGH, p)),
            SceneCodec.decode(old),
        )
    }
}
