package dev.boksremote

import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Family
import dev.boksremote.protocol.ProductModel
import dev.boksremote.protocol.Uuids
import dev.boksremote.speaker.Clock
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.DemoDevice
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.EqMode
import dev.boksremote.speaker.Feature
import dev.boksremote.speaker.MemoryKeyValue
import dev.boksremote.speaker.PincodeStore
import dev.boksremote.speaker.SimulatedBoks
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.speaker.SpeakerState
import dev.boksremote.speaker.StereoRole
import dev.boksremote.speaker.TeamUpMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
class SpeakerControllerTest {
    private val legacy = Advertisement("L", "Gen 4", -50, Family.LEGACY, ProductModel.SB4, "2.1.0", null)
    private val legacyState = SpeakerState(
        family = Family.LEGACY, model = ProductModel.SB4, name = "Gen 4", firmware = "2.1.0", volume = 100,
        stereoRole = StereoRole.MONO, teamUp = TeamUpMode.SOLO, eq = EqMode.DEFAULT,
    )

    private class Setup(val sim: SimulatedBoks, val controller: SpeakerController, val pins: PincodeStore) {
        val state get() = controller.state.value
        /** Every connection state it went through, in order. */
        val seen = mutableListOf<ConnectionState>()
    }

    private fun TestScope.setup(
        device: DemoDevice = DemoDevice.LIGHTBOKS,
        initial: SpeakerState = device.initial,
        advert: Advertisement = device.advert,
        pincode: String? = null,
        saved: String? = null,
        rpc: Boolean = true,
    ): Setup {
        val sim = SimulatedBoks(initial, backgroundScope, pincode = pincode, rpc = rpc)
        val pins = PincodeStore(MemoryKeyValue())
        if (saved != null) pins.put(advert.address, saved)
        val controller = SpeakerController(sim, advert, backgroundScope, pins, clock = Clock { testScheduler.currentTime })
        return Setup(sim, controller, pins).also { s ->
            backgroundScope.launch { controller.state.collect { if (s.seen.lastOrNull() != it.connection) s.seen += it.connection } }
        }
    }

    private fun TestScope.after(ms: Long = 0) {
        runCurrent()
        if (ms > 0) advanceTimeBy(ms)
        runCurrent()
    }

    private fun Setup.connect() = controller.connect()

    @Test
    fun aLightboksIsReadAndSaysWhatItCanDo() = runTest {
        val s = setup()
        s.connect()
        after(1_000)
        assertEquals(ConnectionState.CONNECTED, s.state.connection)
        assertEquals(200, s.state.brightness)
        assertEquals(EnergyLevel.MEDIUM, s.state.energyLevel)
        assertEquals(80, s.state.battery)
        assertEquals(ProductModel.LIGHTBOKS, s.state.model)
        assertTrue(s.state.paletteIsDefault)
        assertEquals(setOf(Feature.RENAME, Feature.RESTART, Feature.LIGHT_CONTENT), s.state.features)
    }

    @Test
    fun aSavedPincodeUnlocksItBeforeAnythingElseIsRead() = runTest {
        val s = setup(DemoDevice.SPEAKER, pincode = "1234", saved = "1234")
        s.connect()
        after(1_000)
        assertEquals(110, s.state.volume)
        assertFalse(s.state.pincodeRequired)
        assertEquals(listOf("read SYSTEM_STATE", "call SecurityService/AuthenticatePincode"), s.sim.log.take(2))
    }

    @Test
    fun aWrongSavedPincodeIsForgottenAndTheRightOneLoadsTheState() = runTest {
        val s = setup(DemoDevice.SPEAKER, pincode = "1234", saved = "0000")
        s.connect()
        after(1_000)
        assertEquals(ConnectionState.CONNECTED, s.state.connection)
        assertTrue(s.state.pincodeRequired)
        assertNull(s.pins.get(DemoDevice.SPEAKER.advert.address))
        assertNull(s.state.volume)
        s.controller.submitPincode("1234")
        after(1_000)
        assertFalse(s.state.pincodeRequired)
        assertEquals(110, s.state.volume)
        assertEquals("1234", s.pins.get(DemoDevice.SPEAKER.advert.address))
    }

    @Test
    fun aLockoutIsReportedAndTheSavedPincodeKept() = runTest {
        val s = setup(DemoDevice.SPEAKER, pincode = "1234", saved = "0000")
        s.sim.lockoutMs = 30_000
        s.connect()
        after(1_000)
        assertEquals("Too many attempts; locked for 30s", s.state.message)
        assertEquals("0000", s.pins.get(DemoDevice.SPEAKER.advert.address))
    }

    @Test
    fun powerOffGoesOverRpcWhereItCanAndToTheCharacteristicWhereNot() = runTest {
        val s = setup(DemoDevice.SPEAKER)
        s.connect()
        after(1_000)
        s.controller.powerOff()
        after(5_000)
        assertTrue("call SystemService/PowerOff" in s.sim.log)
        assertFalse("write SYSTEM_POWER" in s.sim.log)
        assertEquals(ConnectionState.DISCONNECTED, s.state.connection)

        val old = setup(DemoDevice.SPEAKER, rpc = false)
        old.connect()
        after(1_000)
        assertEquals(emptySet<Feature>(), old.state.features)
        old.controller.powerOff()
        after(1_000)
        assertTrue("write SYSTEM_POWER" in old.sim.log)
        assertEquals(ConnectionState.DISCONNECTED, old.state.connection)
    }

    @Test
    fun anRpcChannelThatNeverAnswersIsDroppedWithWhatNeedsIt() = runTest {
        val s = setup()
        s.sim.silent += "GetProductModel"
        s.connect()
        after(10_000)
        assertEquals(ConnectionState.CONNECTED, s.state.connection)
        assertEquals(emptySet<Feature>(), s.state.features)
        assertEquals(200, s.state.brightness)
        // Nothing more is asked of it.
        assertEquals(listOf("call DeviceInformationService/GetProductModel"), s.sim.log.filter { it.startsWith("call") })
    }

    @Test
    fun aConnectSupersededByAnotherNeverFails() = runTest {
        val s = setup()
        s.sim.silent += "GetProductModel"
        s.connect()
        after(1_000)
        s.sim.silent.clear()
        s.connect()
        after(10_000)
        assertEquals(ConnectionState.CONNECTED, s.state.connection)
        assertTrue(Feature.LIGHT_CONTENT in s.state.features)
        assertFalse(ConnectionState.FAILED in s.seen)
    }

    @Test
    fun aRestartedDeviceComesBackByItself() = runTest {
        val s = setup()
        s.connect()
        after(1_000)
        s.controller.restart()
        after(1_000)
        assertTrue(s.state.connection != ConnectionState.CONNECTED)
        after(SimulatedBoks.REBOOT_MS + 5_000)
        assertEquals(ConnectionState.CONNECTED, s.state.connection)
        assertEquals(200, s.state.brightness)
    }

    @Test
    fun aDropIsReportedAndWhatTheDeviceNotifiesIsFollowed() = runTest {
        val s = setup(DemoDevice.SPEAKER)
        s.connect()
        after(1_000)
        s.sim.notify(Uuids.VOLUME, byteArrayOf(50))
        after()
        assertEquals(50, s.state.volume)
        s.sim.drop()
        after()
        assertEquals(ConnectionState.DISCONNECTED, s.state.connection)
        assertEquals("Speaker disconnected", s.state.message)
    }

    @Test
    fun aLegacySpeakerWithoutRpcTakesItsEqOnACharacteristic() = runTest {
        val s = setup(initial = legacyState, advert = legacy, rpc = false)
        s.connect()
        after(1_000)
        assertEquals(setOf(Feature.EQ), s.state.features)
        assertEquals(100, s.state.volume)
        val curve = listOf(3, 0, -3, 0, 0, 6)
        s.controller.setCustomEq(curve)
        s.controller.setVolume(40)
        after(1_000)
        assertEquals(EqMode.USER, s.sim.device.eq)
        assertEquals(curve, s.sim.device.eqBands)
        assertEquals(40, s.sim.device.volume)
    }

    @Test
    fun aDemoSpeakerTurnsItselfOffOnceNothingHasPlayedForItsAutoOffTime() = runTest {
        val demo = SpeakerController.demo(DemoDevice.SPEAKER, backgroundScope, PincodeStore(MemoryKeyValue()))
        demo.connect()
        after(1_000)
        assertEquals(ConnectionState.CONNECTED, demo.state.value.connection)
        assertTrue(demo.demo)
        assertTrue(Feature.EQ in demo.state.value.features)
        assertEquals(30, demo.state.value.autoOffMinutes)
        after(30 * 60_000L)
        assertEquals(ConnectionState.DISCONNECTED, demo.state.value.connection)
    }
}
