package dev.boksremote

import dev.boksremote.protocol.Family
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.speaker.Feature
import dev.boksremote.speaker.SpeakerState
import dev.boksremote.speaker.StageStatus
import dev.boksremote.speaker.Unavailable
import dev.boksremote.ui.deviceView
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTest {
    private val speaker = SpeakerState(connection = ConnectionState.CONNECTED, family = Family.BIFROST, name = "Mix", volume = 102)
    private val light = SpeakerState(connection = ConnectionState.CONNECTED, family = Family.LIGHT, name = "Light", brightness = 255, features = setOf(Feature.LIGHT_CONTENT))
    private val free = StageStatus(cycle = null, sleep = null)

    private fun row(s: SpeakerState, stage: StageStatus = free, cycle: CycleStatus = CycleStatus.Stopped, sceneLight: Boolean = false) =
        remoteRow(RemoteTarget.OPEN, deviceView(s, stage, sceneLight, cycle), cycle, sceneLight, bridge = "Bridge")

    @Test
    fun stepsLandOnRoundPercentages() {
        assertEquals(115, stepLevel(102, up = true, stepPercent = 5)) // 40% → 45%
        assertEquals(89, stepLevel(102, up = false, stepPercent = 5)) // 40% → 35%
        assertEquals(102, stepLevel(97, up = true, stepPercent = 5)) // 38% → 40%
        assertEquals(89, stepLevel(97, up = false, stepPercent = 5)) // 38% → 35%
        assertEquals(255, stepLevel(255, up = true, stepPercent = 10))
        assertEquals(0, stepLevel(0, up = false, stepPercent = 10))
    }

    @Test
    fun aSpeakerOffersVolumeAndMute() {
        val r = row(speaker)
        assertEquals("Volume 40%", r.status)
        assertEquals(listOf(RemoteButton.VOLUME_DOWN, RemoteButton.MUTE, RemoteButton.VOLUME_UP), r.buttons)
        val muted = row(speaker.copy(volume = 0))
        assertEquals("Muted", muted.status)
        assertEquals(RemoteButton.UNMUTE, muted.buttons[1])
    }

    @Test
    fun cycleIsOfferedOnTheSceneLightOnly() {
        assertEquals(listOf(RemoteButton.DIM, RemoteButton.BRIGHTEN), row(light).buttons)
        assertEquals(listOf(RemoteButton.DIM, RemoteButton.PLAY_CYCLE, RemoteButton.BRIGHTEN), row(light, sceneLight = true).buttons)
        val running = CycleStatus.Running("Sunset", "Ocean", nextAt = 0)
        assertEquals(RemoteButton.PAUSE_CYCLE, row(light, cycle = running, sceneLight = true).buttons[1])
        val tooFew = free.copy(cycle = Unavailable.TOO_FEW_PALETTES)
        assertEquals(listOf(RemoteButton.DIM, RemoteButton.BRIGHTEN), row(light, stage = tooFew, sceneLight = true).buttons)
    }

    @Test
    fun aLeasedLightShowsTheBridgeWithoutButtons() {
        val leased = StageStatus(leased = true, cycle = Unavailable.LEASED, sleep = Unavailable.LEASED)
        val r = row(light, stage = leased, sceneLight = true)
        assertEquals("Bridge", r.status)
        assertEquals(emptyList<RemoteButton>(), r.buttons)
    }

    @Test
    fun aDeviceThatIsntReadySaysWhy() {
        val r = row(speaker.copy(connection = ConnectionState.DISCONNECTED))
        assertEquals("Disconnected", r.status)
        assertEquals(emptyList<RemoteButton>(), r.buttons)
    }
}
