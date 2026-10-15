package dev.boksremote

import dev.boksremote.proto.proto
import dev.boksremote.protocol.RpcClient
import dev.boksremote.protocol.RpcException
import dev.boksremote.protocol.Uuids
import dev.boksremote.speaker.DemoDevice
import dev.boksremote.speaker.LightContentCodec
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.SimulatedBoks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RpcClientTest {
    private val ocean = LightPalette.PRESETS.first { it.name == "Ocean" }

    private suspend fun TestScope.connect(sim: SimulatedBoks, mtu: Int = 23): RpcClient {
        sim.connect()
        sim.requestMtu(mtu)
        return RpcClient(sim, Uuids.RPC_TX, Uuids.RPC_RX, backgroundScope).also { it.start() }
    }

    private fun TestScope.lightboks() = SimulatedBoks(DemoDevice.LIGHTBOKS.initial.copy(palette = ocean, paletteIsDefault = false), backgroundScope)

    @Test
    fun aRequestGoesOutInPiecesTheMtuAllows() = runTest {
        val sim = lightboks()
        val rpc = connect(sim, mtu = 23)
        val name = "A Lightboks with a name far longer than one Bluetooth packet holds"
        rpc.call("DeviceInformationService", "SetName", proto { string(1, name) })
        assertTrue(sim.rpcChunks.size > 3)
        assertTrue(sim.rpcChunks.all { it <= 20 })
        assertEquals(name, sim.device.name)
    }

    @Test
    fun anAnswerInManyPiecesIsPutBackTogether() = runTest {
        val sim = lightboks()
        val rpc = connect(sim, mtu = 23)
        val palette = LightContentCodec.parsePalette(rpc.call("LightContentService", "GetActivePalette"))!!
        assertEquals("Ocean", palette.name)
        assertEquals(ocean.colors.map { it.hex() }, palette.colors.map { it.hex() })
    }

    @Test
    fun aCallNobodyAnswersTimesOutAndItsLateAnswerIsNotTakenForTheNextOnes() = runTest {
        val sim = lightboks()
        val rpc = connect(sim, mtu = 247)
        sim.silent += setOf("GetName", "GetFirmwareVersion")
        try {
            rpc.call("DeviceInformationService", "GetName", timeoutMs = 1_000)
            fail("answered")
        } catch (e: TimeoutCancellationException) {
        }
        val firmware = async { rpc.call("DeviceInformationService", "GetFirmwareVersion").string(1) }
        runCurrent()
        // The name comes first, late, then the firmware version.
        sim.answerSilent()
        assertEquals("1.2.0", firmware.await())
    }

    @Test
    fun aRefusedCallThrowsWithItsStatus() = runTest {
        val sim = lightboks()
        val rpc = connect(sim)
        sim.failing["SetName"] = 5
        try {
            rpc.call("DeviceInformationService", "SetName", proto { string(1, "x") })
            fail("took it")
        } catch (e: RpcException) {
            assertEquals(5, e.status)
        }
        // The next call is matched as before.
        assertEquals("Demo Lightboks", rpc.call("DeviceInformationService", "GetName").string(1))
    }
}
