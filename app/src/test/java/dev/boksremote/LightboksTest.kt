package dev.boksremote

import dev.boksremote.proto.ProtoMessage
import dev.boksremote.protocol.Uuids
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.LightLink
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.Lightboks
import dev.boksremote.speaker.SpeakerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

/** Records what the light is sent, as `Method` for a call and `characteristic=value` for a write. */
private class FakeLink(private val failing: Set<String> = emptySet()) : LightLink {
    val sent = mutableListOf<String>()
    val paletteIds = mutableListOf<UUID>()

    override suspend fun call(service: String, method: String, request: ByteArray): ProtoMessage {
        sent += method
        if (method in failing) throw IllegalStateException("$method unsupported")
        if (method == "SetActivePalette") {
            val b = ByteBuffer.wrap(ProtoMessage.parse(request).message(2)!!.message(2)!!.bytes(1))
            paletteIds += UUID(b.long, b.long)
        }
        return ProtoMessage.parse(ByteArray(0))
    }

    override suspend fun write(characteristic: UUID, value: ByteArray) {
        val name = when (characteristic) {
            Uuids.LIGHT_BRIGHTNESS -> "brightness"
            Uuids.LIGHT_ENERGY_LEVEL -> "level"
            else -> characteristic.toString()
        }
        sent += "$name=${value.single().toInt() and 0xFF}"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LightboksTest {
    private val state = MutableStateFlow(SpeakerState(brightness = 100))
    private val errors = mutableListOf<String?>()

    private fun TestScope.lightboks(link: LightLink) = Lightboks(link, state, backgroundScope, { what, _ -> errors += what }, { testScheduler.currentTime })

    @Test
    fun uplightStartsItsProgramBeforeTheLevel() = runTest {
        val link = FakeLink()
        lightboks(link).applyEnergyLevel(EnergyLevel.CUSTOM)
        assertEquals(listOf("SetCustomProgram", "level=3"), link.sent)
        assertEquals(EnergyLevel.CUSTOM, state.value.energyLevel)
    }

    @Test
    fun otherLevelsClearTheProgramAfterwardsWhereTheFirmwareCan() = runTest {
        val link = FakeLink(failing = setOf("SetCustomProgram"))
        lightboks(link).applyEnergyLevel(EnergyLevel.HIGH)
        assertEquals(listOf("level=2", "SetCustomProgram"), link.sent)
        // Optional on older firmware: reported quietly, and the level still took.
        assertEquals(listOf<String?>(null), errors)
        assertEquals(EnergyLevel.HIGH, state.value.energyLevel)
    }

    @Test
    fun aFadeStepsOnTwoFixedIdsAndEndsOnAFreshOne() = runTest {
        val link = FakeLink()
        val (from, to) = LightPalette.PRESETS[0] to LightPalette.PRESETS[1]
        launch { lightboks(link).fadePalette(from, to, 3_000) }
        runCurrent()
        // What the light is on its way to, from the start, so a scene never keeps a colour in between.
        assertEquals(to, state.value.palette)
        advanceUntilIdle()
        assertEquals(3_000L, testScheduler.currentTime)
        val ids = link.paletteIds
        assertEquals(Lightboks.FADE_STEPS, ids.size)
        assertEquals(setOf(Lightboks.FADE_ID_A, Lightboks.FADE_ID_B), ids.dropLast(1).toSet())
        assertTrue(ids.zipWithNext().dropLast(1).all { (a, b) -> a != b })
        assertTrue(ids.last() != Lightboks.FADE_ID_A && ids.last() != Lightboks.FADE_ID_B)
        assertEquals(to, state.value.palette)
    }

    @Test
    fun resetHandsTheColoursBackToTheLight() = runTest {
        val l = lightboks(FakeLink())
        l.applyPalette(LightPalette.PRESETS[0])
        assertEquals(false, state.value.paletteIsDefault)
        l.resetPalette()
        assertEquals(null, state.value.palette)
        assertEquals(true, state.value.paletteIsDefault)
    }

    @Test
    fun aDropCutShortStillReturnsToTheBrightnessBeforeIt() = runTest {
        val link = FakeLink()
        val l = lightboks(link)
        l.holdDrop(true)
        l.holdDrop(false)
        runCurrent()
        assertEquals(255, state.value.brightness)
        assertTrue(l.dropping)
        // Pressed again mid-strobe: the next drop still returns to 100, not to the strobe's 255.
        l.holdDrop(true)
        l.holdDrop(false)
        // The drop runs in the background, which advanceUntilIdle leaves alone.
        advanceTimeBy(5_000)
        assertEquals(100, state.value.brightness)
        assertEquals("brightness=100", link.sent.last())
        assertNotEquals(true, l.dropping)
    }
}
