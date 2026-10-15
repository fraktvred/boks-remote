package dev.boksremote

import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Family
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.SpeakerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device that connects at once, or fails at once while it doesn't [answer]s; counts what was asked of it. */
class FakeDevice(val address: String, family: Family, override val demo: Boolean = false) : Device {
    override val state = MutableStateFlow(SpeakerState(connection = ConnectionState.DISCONNECTED, family = family))
    var answers = true
    var connects = 0
    var disconnects = 0

    override fun connect() {
        connects++
        state.update { it.copy(connection = if (answers) ConnectionState.CONNECTED else ConnectionState.FAILED) }
    }

    override suspend fun reconnect(): Boolean {
        connect()
        return awaitConnection()
    }

    override suspend fun awaitConnection() = state.first { it.connection != ConnectionState.CONNECTING }.connection == ConnectionState.CONNECTED

    override fun disconnect() {
        disconnects++
        state.update { it.copy(connection = ConnectionState.DISCONNECTED) }
    }

    /** Out of range, or switched off. */
    fun drop() = state.update { it.copy(connection = ConnectionState.DISCONNECTED) }

    override fun toString() = address
}

class FakeScanner : ScanPort {
    override val results = MutableStateFlow<Map<String, Advertisement>>(emptyMap())
    var scanning = false
    var starts = 0
    val forgotten = mutableListOf<String>()

    override fun start() {
        starts++
        scanning = true
    }

    override fun stop() {
        scanning = false
    }

    override fun forget(address: String) {
        forgotten += address
        results.update { it - address }
    }

    fun find(vararg ads: Advertisement) = results.update { r -> r + ads.associateBy { it.address } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListTest {
    private fun ad(address: String, family: Family = Family.LIGHT) = Advertisement(address, address, -50, family, null, null, null)

    private val lightA = ad("A")
    private val lightB = ad("B")
    private val speaker = ad("S", Family.BIFROST)

    private class Setup(val scanner: FakeScanner, val list: DeviceList<FakeDevice>, val held: MutableStateFlow<Map<FakeDevice, Hold>>) {
        val devices = mutableMapOf<String, FakeDevice>()
        /** Each scene light the list chose, and whether the one it replaced was still connected then. */
        val scene = mutableListOf<Pair<FakeDevice?, Boolean?>>()
        var sceneLight: FakeDevice? = null
        operator fun get(address: String) = devices.getValue(address)
        fun listed() = list.nearby.value.keys
    }

    private fun TestScope.setup(foreground: Boolean = true): Setup {
        val scanner = FakeScanner()
        val held = MutableStateFlow<Map<FakeDevice, Hold>>(emptyMap())
        lateinit var s: Setup
        val list = DeviceList<FakeDevice>(
            backgroundScope,
            scanner,
            make = { ad -> s.devices.getOrPut(ad.address) { FakeDevice(ad.address, ad.family) } },
            held = held,
            onSceneLight = { next ->
                s.scene += next to s.sceneLight?.let { it.state.value.connection == ConnectionState.CONNECTED }
                s.sceneLight = next
            },
        )
        s = Setup(scanner, list, held)
        list.setForeground(foreground)
        runCurrent()
        return s
    }

    private fun TestScope.after(ms: Long = 0) {
        runCurrent()
        if (ms > 0) {
            advanceTimeBy(ms)
            runCurrent()
        }
    }

    @Test
    fun whatTheScanFindsIsListedAndConnected() = runTest {
        val s = setup()
        s.scanner.find(lightA, speaker)
        after()
        assertEquals(setOf("A", "S"), s.listed())
        assertEquals(1, s["A"].connects)
        assertEquals(1, s["S"].connects)
    }

    @Test
    fun aDroppedDeviceGetsOneTryThenLeavesTheListAndAScanLooksForIt() = runTest {
        val s = setup()
        s.scanner.find(lightA)
        after()
        val starts = s.scanner.starts
        s["A"].answers = false
        s["A"].drop()
        after()
        assertEquals(2, s["A"].connects)
        assertEquals(emptySet<String>(), s.listed())
        assertEquals(listOf("A"), s.scanner.forgotten)
        assertEquals(1, s["A"].disconnects)
        assertEquals(starts + 1, s.scanner.starts)
    }

    @Test
    fun aDroppedDeviceThatComesBackStaysListed() = runTest {
        val s = setup()
        s.scanner.find(lightA)
        after()
        s["A"].drop()
        after()
        assertEquals(setOf("A"), s.listed())
        assertEquals(ConnectionState.CONNECTED, s["A"].state.value.connection)
    }

    @Test
    fun inTheBackgroundOnlyTheOpenDeviceAndHeldDevicesStayConnected() = runTest {
        val s = setup()
        s.scanner.find(lightA, lightB, speaker)
        after()
        s.list.open(speaker)
        s.held.value = mapOf(s["B"] to Hold(null))
        s.list.setForeground(false)
        after()
        assertEquals(setOf("B", "S"), s.listed())
        assertEquals(1, s["A"].disconnects)
        assertEquals(0, s["B"].disconnects)
        assertEquals(0, s["S"].disconnects)
    }

    @Test
    fun whenTheHoldEndsInTheBackgroundTheLightIsLetGo() = runTest {
        val s = setup()
        s.scanner.find(lightA)
        after()
        s.held.value = mapOf(s["A"] to CYCLE)
        s.list.setForeground(false)
        after()
        assertEquals(setOf("A"), s.listed())
        s.held.value = emptyMap()
        after()
        assertEquals(emptySet<String>(), s.listed())
        assertEquals(1, s["A"].disconnects)
    }

    @Test
    fun theBridgesLightSurvivesClosingItsScreenAndGoesOnceTheBridgeStops() = runTest {
        val s = setup()
        s.scanner.find(lightA)
        after()
        s.list.open(lightA)
        s.held.value = mapOf(s["A"] to Hold(null))
        s.list.setForeground(false)
        s.list.close()
        after()
        assertEquals(setOf("A"), s.listed())
        assertEquals(0, s["A"].disconnects)
        s.held.value = emptyMap()
        after()
        assertEquals(emptySet<String>(), s.listed())
        assertEquals(1, s["A"].disconnects)
    }

    @Test
    fun aHeldLightKeepsReconnectingAndCycleGivesUpWhereTheBridgeDoesNot() = runTest {
        val s = setup()
        s.scanner.find(lightA, lightB)
        after()
        s.held.value = mapOf(s["A"] to CYCLE, s["B"] to Hold(null))
        s["A"].answers = false
        s["B"].answers = false
        s["A"].drop()
        s["B"].drop()
        after(4 * 60_000)
        assertEquals(setOf("A", "B"), s.listed())
        assertTrue(s["A"].connects > 10)
        after(2 * 60_000)
        assertEquals(setOf("B"), s.listed())
        after(60 * 60_000)
        assertEquals(setOf("B"), s.listed())
        // Backing off: a couple of hundred tries an hour at most, not one every moment.
        assertTrue(s["B"].connects in 200..400)
        s["B"].answers = true
        after(DeviceList.RECONNECT_MAX_MS)
        assertEquals(ConnectionState.CONNECTED, s["B"].state.value.connection)
        val connects = s["B"].connects
        after(60_000)
        assertEquals(connects, s["B"].connects)
    }

    @Test
    fun aDeviceNoLongerHeldStopsReconnecting() = runTest {
        val s = setup()
        s.scanner.find(lightA)
        after()
        s.held.value = mapOf(s["A"] to Hold(null))
        s["A"].answers = false
        s["A"].drop()
        after(60_000)
        s.held.value = emptyMap()
        after()
        val connects = s["A"].connects
        after(10 * 60_000)
        assertEquals(connects, s["A"].connects)
    }

    @Test
    fun anOpenDeviceThatDropsIsLeftToItsScreenUnlessHeld() = runTest {
        val s = setup()
        s.scanner.find(lightA, lightB)
        after()
        s.list.open(lightA)
        s["A"].answers = false
        s["A"].drop()
        after(60_000)
        assertEquals(1, s["A"].connects)
        assertEquals(setOf("A", "B"), s.listed())
        s.list.close()
        s.list.open(lightB)
        s.held.value = mapOf(s["B"] to Hold(null))
        s["B"].answers = false
        s["B"].drop()
        after(60_000)
        assertTrue(s["B"].connects > 3)
    }

    @Test
    fun theSceneLightIsTheOpenLightboksOrElseTheOneThereWhileListedOrElseTheFirstListed() = runTest {
        val s = setup()
        s.scanner.find(speaker, lightA, lightB)
        after()
        assertEquals(s["A"], s.sceneLight)
        s.list.open(lightB)
        assertEquals(s["B"], s.sceneLight)
        s.list.close()
        assertEquals(s["B"], s.sceneLight)
        s.list.open(speaker)
        assertEquals(s["B"], s.sceneLight)
        s.list.close()
        // B goes: the first listed Lightboks takes over.
        s["B"].answers = false
        s["B"].drop()
        after()
        assertEquals(s["A"], s.sceneLight)
        assertEquals(listOf(s["A"], s["B"], s["A"]), s.scene.map { it.first })
    }

    @Test
    fun aLightHeldAsTheSceneLightStaysItWhileAnotherIsOpen() = runTest {
        val s = setup()
        s.scanner.find(lightA, lightB)
        after()
        s.list.open(lightA)
        s.held.value = mapOf(s["A"] to Hold(null, sceneLight = true))
        after()
        s.list.close()
        s.list.open(lightB)
        assertEquals(s["A"], s.sceneLight)
        // Let go: the open Lightboks takes over.
        s.held.value = emptyMap()
        after()
        assertEquals(s["B"], s.sceneLight)
    }

    @Test
    fun theSceneLightIsChosenBeforeALeavingLightIsDisconnected() = runTest {
        val s = setup()
        s.scanner.find(lightA, lightB)
        after()
        s.list.open(lightA)
        s.list.setForeground(false)
        after()
        // B was let go in the background; A, open, is the scene light.
        assertEquals(setOf("A"), s.listed())
        assertEquals(s["A"], s.sceneLight)
        s.list.close()
        after()
        // A left the list; the scene moved off it while it was still connected.
        assertFalse("A" in s.listed())
        assertEquals(null to true, s.scene.last())
    }

    @Test
    fun aDemoDeviceOpenedAndClosedIsNeverListedNorDisconnected() = runTest {
        val s = setup()
        s.scanner.find(lightA)
        after()
        val demo = FakeDevice("demo", Family.LIGHT, demo = true).apply { connect() }
        s.list.open(demo)
        assertEquals(demo, s.list.open.value)
        // A dummy's scene is its own set's, not the user's.
        assertEquals(s["A"], s.sceneLight)
        s.list.setForeground(false)
        s.list.close()
        after()
        assertEquals(0, demo.disconnects)
        assertFalse("demo" in s.listed())
        assertNull(s.list.open.value)
    }

    @Test
    fun theListScansWhileItShowsAndOpeningADeviceStopsIt() = runTest {
        val s = setup(foreground = false)
        assertFalse(s.scanner.scanning)
        s.list.setForeground(true)
        after()
        assertTrue(s.scanner.scanning)
        s.scanner.find(lightA)
        after()
        s.list.open(lightA)
        after()
        assertFalse(s.scanner.scanning)
        s.list.close()
        after()
        assertTrue(s.scanner.scanning)
        s.list.setForeground(false)
        after()
        assertFalse(s.scanner.scanning)
    }

    private companion object {
        val CYCLE = Hold(5 * 60_000L)
    }
}
