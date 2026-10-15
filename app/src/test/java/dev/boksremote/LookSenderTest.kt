package dev.boksremote

import dev.boksremote.dmx.LightLook
import dev.boksremote.dmx.LookSender
import dev.boksremote.dmx.Rgb
import dev.boksremote.speaker.Clock
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.LightFx
import dev.boksremote.speaker.LightOutput
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.SpeakerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class LookSenderTest {
    /** Records each write as `time what`; fails them all while [failing]. */
    private class Output(private val now: () -> Long) : LightOutput {
        val sent = mutableListOf<String>()
        val paletteIds = mutableListOf<UUID>()
        var failing = false

        private fun send(what: String) {
            if (failing) throw IllegalStateException("write failed (133)")
            sent += "${now()} $what"
        }

        override suspend fun applyFx(id: UUID) = send("fx:" + (LightFx.entries.firstOrNull { it.id == id }?.name ?: "none"))
        override suspend fun applyBrightness(value: Int) = send("dimmer:$value")
        override suspend fun applyEnergyLevel(level: EnergyLevel) = send("mode:$level")
        override suspend fun applyPalette(palette: LightPalette, id: UUID) {
            send("colors:${palette.colors.first().hex()}")
            paletteIds += id
        }
    }

    private val red = List(4) { Rgb(255, 0, 0) }
    private val blue = List(4) { Rgb(0, 0, 255) }
    private val green = List(4) { Rgb(0, 255, 0) }
    private fun look(dimmer: Int = 200, colors: List<Rgb> = red, fx: LightFx? = null) = LightLook(dimmer, EnergyLevel.MEDIUM, colors, fx)

    private class Setup(val sender: LookSender, val output: Output, val light: MutableStateFlow<SpeakerState>)

    private fun TestScope.setup(): Setup {
        val output = Output { testScheduler.currentTime }
        val light = MutableStateFlow(SpeakerState(connection = ConnectionState.CONNECTED))
        val sender = LookSender(output, light, Clock { testScheduler.currentTime })
        backgroundScope.launch { sender.run() }
        return Setup(sender, output, light)
    }

    private fun TestScope.after(ms: Long) {
        runCurrent()
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun theFirstLookGoesOutEffectDimmerModeThenColoursOnceTheyHaveSettled() = runTest {
        val s = setup()
        s.sender.want(look(fx = LightFx.CHASE))
        after(1_000)
        assertEquals(listOf("0 fx:CHASE", "0 dimmer:200", "0 mode:MEDIUM", "${LookSender.COLOR_SETTLE_MS} colors:#FF0000"), s.output.sent)
    }

    @Test
    fun aFadeSendsItsEndColourNotEveryStep() = runTest {
        val s = setup()
        s.sender.want(look(colors = red))
        after(1_000)
        // A step every 25 ms, as a desk at 40 Hz sends a fade, then holding on green.
        repeat(8) { i ->
            s.sender.want(look(colors = if (i % 2 == 0) blue else green))
            after(25)
        }
        after(1_000)
        assertEquals(listOf("colors:#FF0000", "colors:#00FF00"), s.output.sent.filter { "colors" in it }.map { it.substringAfter(' ') })
    }

    @Test
    fun snapsAreSpacedBySoMuchAtLeastAndAlternateTwoIds() = runTest {
        val s = setup()
        s.sender.want(look(colors = red))
        after(1_000)
        // Each colour held past the settle time, but faster than the spacing allows.
        s.sender.want(look(colors = blue))
        after(LookSender.COLOR_SETTLE_MS)
        s.sender.want(look(colors = green))
        after(1_000)
        val times = s.output.sent.filter { "colors" in it }.map { it.substringBefore(' ').toLong() }
        assertEquals(3, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= LookSender.PALETTE_MIN_INTERVAL_MS })
        assertEquals(listOf(LookSender.PALETTE_ID_A, LookSender.PALETTE_ID_B, LookSender.PALETTE_ID_A), s.output.paletteIds)
    }

    @Test
    fun aDimmerChangeGoesAheadOfColoursStillSettling() = runTest {
        val s = setup()
        s.sender.want(look(colors = red))
        after(1_000)
        s.sender.want(look(colors = blue))
        after(50)
        s.sender.want(look(dimmer = 10, colors = blue))
        after(1_000)
        assertEquals(listOf("1050 dimmer:10", "1150 colors:#0000FF"), s.output.sent.drop(4))
    }

    @Test
    fun aLightBackFromADropGetsTheWholeLookAgain() = runTest {
        val s = setup()
        s.sender.want(look())
        after(1_000)
        s.light.update { it.copy(connection = ConnectionState.DISCONNECTED) }
        after(1_000)
        assertTrue(s.sender.sending.value.reconnecting)
        s.light.update { it.copy(connection = ConnectionState.CONNECTED) }
        after(1_000)
        assertEquals(false, s.sender.sending.value.reconnecting)
        assertEquals(listOf("fx:none", "dimmer:200", "mode:MEDIUM", "colors:#FF0000"), s.output.sent.drop(4).map { it.substringAfter(' ') })
    }

    @Test
    fun aFailedWriteIsReportedAndTriedAgainShortlyAfter() = runTest {
        val s = setup()
        s.output.failing = true
        s.sender.want(look())
        after(100)
        assertEquals("write failed (133)", s.sender.sending.value.error)
        s.output.failing = false
        after(LookSender.RETRY_MS)
        assertNull(s.sender.sending.value.error)
        assertEquals("${LookSender.RETRY_MS} fx:none", s.output.sent.first())
    }
}
