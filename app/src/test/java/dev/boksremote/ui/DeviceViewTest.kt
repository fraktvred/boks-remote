package dev.boksremote.ui

import dev.boksremote.protocol.Family
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.EqMode
import dev.boksremote.speaker.EqPreset
import dev.boksremote.speaker.Feature
import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.SpeakerState
import dev.boksremote.speaker.StageStatus
import dev.boksremote.speaker.Unavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceViewTest {
    private val light = SpeakerState(
        connection = ConnectionState.CONNECTED,
        family = Family.LIGHT,
        brightness = 128,
        energyLevel = EnergyLevel.MEDIUM,
        features = setOf(Feature.LIGHT_CONTENT),
    )
    private val free = StageStatus(cycle = null, sleep = null)
    private val leasedStage = StageStatus(leased = true, cycle = Unavailable.LEASED, sleep = Unavailable.LEASED)

    @Test
    fun aLeasedSceneLightGivesUpItsOwnControlsButKeepsTheBridge() {
        val view = deviceView(light, leasedStage, sceneLight = true, CycleStatus.Stopped)
        assertTrue(view.leased)
        assertEquals(LookControls.NONE, view.look)
        assertFalse(view.lightContent)
        assertTrue(view.dmx)
    }

    @Test
    fun anotherLightOpenWhileTheSceneLightIsLeasedKeepsItsControls() {
        val view = deviceView(light, leasedStage, sceneLight = false, CycleStatus.Stopped)
        assertFalse(view.leased)
        assertEquals(LookControls.FULL, view.look)
        assertTrue(view.lightContent)
        // Cycle and Sleep fade belong to the Stage, whose light the bridge has.
        assertEquals(Unavailable.LEASED, view.cycle)
        assertEquals(Unavailable.LEASED, view.sleep)
    }

    @Test
    fun aLeasedLightThatDropsStaysLeased() {
        val view = deviceView(light.copy(connection = ConnectionState.DISCONNECTED), leasedStage, sceneLight = true, CycleStatus.Stopped)
        assertTrue(view.leased)
        assertEquals(StandIn.DISCONNECTED, view.standIn)
    }

    @Test
    fun theConnectionStandsInBeforeAPincodeOrALock() {
        val locked = light.copy(pincodeRequired = true, locked = true)
        assertEquals(StandIn.CONNECTING, deviceView(locked.copy(connection = ConnectionState.CONNECTING), free, true, CycleStatus.Stopped).standIn)
        assertEquals(StandIn.FAILED, deviceView(locked.copy(connection = ConnectionState.FAILED), free, true, CycleStatus.Stopped).standIn)
        assertEquals(StandIn.PINCODE, deviceView(locked, free, true, CycleStatus.Stopped).standIn)
        assertEquals(StandIn.LOCKED, deviceView(light.copy(locked = true), free, true, CycleStatus.Stopped).standIn)
        assertNull(deviceView(light, free, true, CycleStatus.Stopped).standIn)
    }

    @Test
    fun aLightWithoutBrightnessOffersItsModeAlone() {
        assertEquals(LookControls.MODE, deviceView(light.copy(brightness = null), free, true, CycleStatus.Stopped).look)
        assertEquals(LookControls.NONE, deviceView(light.copy(brightness = null, energyLevel = null), free, true, CycleStatus.Stopped).look)
    }

    @Test
    fun cycleCountsDownOnlyOnTheSceneLight() {
        val running = CycleStatus.Running("A", "B", nextAt = 5_000)
        assertEquals(5_000L, deviceView(light, free, sceneLight = true, running).cycleNextAt)
        assertNull(deviceView(light, free, sceneLight = false, running).cycleNextAt)
    }

    @Test
    fun eqShowsByFeature() {
        val speaker = SpeakerState(connection = ConnectionState.CONNECTED, family = Family.BIFROST, features = setOf(Feature.EQ))
        assertTrue(deviceView(speaker, StageStatus(), false, CycleStatus.Stopped).eq)
        assertFalse(deviceView(speaker.copy(features = emptySet(), eq = EqMode.DEFAULT), StageStatus(), false, CycleStatus.Stopped).eq)
    }

    @Test
    fun theDefaultPaletteIsNotShownAsOneOfTheUsers() {
        val palette = LightPalette("Sunset", List(LightPalette.SIZE) { HsvColor(0f, 1f, 1f) })
        assertNull(light.copy(palette = palette, paletteIsDefault = true).shownPalette)
        assertEquals("Lightboks default", light.copy(palette = palette, paletteIsDefault = true).paletteName)
        assertEquals("Sunset", light.copy(palette = palette).paletteName)
    }

    @Test
    fun theActivePresetIsTheSavedCurveThatPlays() {
        val presets = listOf(EqPreset("Flat-ish", listOf(0, 1, 0)), EqPreset("Bass", listOf(4, 0, 0)))
        val playing = SpeakerState(eq = EqMode.USER, eqBands = listOf(4, 0, 0))
        assertEquals("Bass", activePreset(playing, presets)?.name)
        assertNull(activePreset(playing.copy(eq = EqMode.LOUD), presets))
        assertNull(activePreset(playing.copy(eqBands = listOf(9, 9, 9)), presets))
    }
}
