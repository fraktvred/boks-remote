package dev.boksremote.speaker

import android.util.Log
import dev.boksremote.Device
import dev.boksremote.ble.Gatt
import dev.boksremote.proto.ProtoMessage
import dev.boksremote.proto.proto
import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Family
import dev.boksremote.protocol.ProductModel
import dev.boksremote.protocol.RpcClient
import dev.boksremote.protocol.SecurityFlags
import dev.boksremote.protocol.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, FAILED }
enum class TeamUpMode(val label: String) { SOLO("Solo"), HOST("Host"), JOIN("Join") }
enum class StereoRole(val label: String) { MONO("Mono"), LEFT("Left"), RIGHT("Right") }
/** EQ mode by wire value. What the presets are called depends on the model; see [SoundProfiles]. */
enum class EqMode { DEFAULT, LOUD, QUIET, USER }
/**
 * Lightboks energy level, by wire value. CUSTOM is a status, not a writable mode: the light
 * reports it while a custom program runs. The official app labels the program it ships as "Uplight".
 */
enum class EnergyLevel(val label: String) { LOW("Chill"), MEDIUM("Dance"), HIGH("Rave"), CUSTOM("Uplight") }

/** A queued EQ change: a built-in [mode], or the user curve when [bands] is set. */
private class EqWrite(val mode: EqMode, val bands: List<Int>?)

/** Snapshot of everything the UI shows. Null means "not supported or not read yet". */
data class SpeakerState(
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val family: Family? = null,
    val model: ProductModel? = null,
    val name: String? = null,
    val firmware: String? = null,
    val battery: Int? = null,
    val volume: Int? = null, // 0..255 on the wire
    val stereoRole: StereoRole? = null,
    val teamUp: TeamUpMode? = null,
    val eq: EqMode? = null,
    val eqBands: List<Int>? = null, // custom curve in dB, see EqCurve; kept while a preset plays
    val activeInputs: List<String> = emptyList(),
    val brightness: Int? = null, // 0..255
    val energyLevel: EnergyLevel? = null,
    val autoOffMinutes: Int? = null,
    /**
     * [Clock.ELAPSED] time since when no input has been active, which is when the auto power-off timer
     * runs; null while one is. The speaker doesn't report its timer, so this is the app's own estimate,
     * counted from no earlier than the connection.
     */
    val idleSince: Long? = null,
    val palette: LightPalette? = null,
    val paletteIsDefault: Boolean = false,
    val features: Set<Feature> = emptySet(),
    val pincodeRequired: Boolean = false,
    val locked: Boolean = false,
    val message: String? = null,
)

/**
 * One speaker or Lightboks over its [Gatt] link: connects, unlocks and reads it, keeps [state] in step
 * with what it notifies, and routes each control to its family's characteristic or RPC (see
 * [FamilyProtocol]). [SimulatedBoks] stands in for the device in demo mode and in tests.
 */
class SpeakerController(
    private val gatt: Gatt,
    advert: Advertisement,
    private val scope: CoroutineScope,
    private val pincodes: PincodeStore,
    /** A dummy of demo mode, which the device list never lists. */
    override val demo: Boolean = false,
    private val clock: Clock = Clock.ELAPSED,
) : Light, Device {
    private val address = advert.address
    private val advertised = advert
    private var rpc: RpcClient? = null
    private val _state = MutableStateFlow(SpeakerState(family = advert.family, model = advert.model, name = advert.name, firmware = advert.firmware))
    override val state: StateFlow<SpeakerState> = _state

    // Slider drags produce many values; only the latest matters, so a conflated channel drops stale ones.
    private val volumeWrites = Channel<Int>(Channel.CONFLATED)
    private val brightnessWrites = Channel<Int>(Channel.CONFLATED)
    private val fxWrites = Channel<UUID>(Channel.CONFLATED)
    // Presets and curves share one queue so a late curve from a drag can never override a newer preset.
    private val eqWrites = Channel<EqWrite>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    private val family get() = _state.value.family
    private val protocol get() = FamilyProtocol.of(family ?: advertised.family)

    private val wire = object : Wire {
        override val rpc get() = this@SpeakerController.rpc != null
        override fun has(c: UUID) = gatt.hasCharacteristic(c)

        override suspend fun read(c: UUID): ByteArray? {
            if (!gatt.hasCharacteristic(c)) return null
            return try {
                gatt.read(c)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Log.d(TAG, "read $c: ${e.message}")
                null
            }
        }

        override suspend fun write(c: UUID, value: ByteArray) = gatt.write(c, value)
        override suspend fun probeRpc() = this@SpeakerController.probeRpc()
        override suspend fun call(service: String, method: String, request: ByteArray) = this@SpeakerController.call(service, method, request)
        override suspend fun optional(block: suspend () -> Unit) = attempt(null, block)
        override fun update(change: (SpeakerState) -> SpeakerState) = _state.update(change)
    }

    private val link = object : LightLink {
        override suspend fun call(service: String, method: String, request: ByteArray) = this@SpeakerController.call(service, method, request)
        override suspend fun write(characteristic: UUID, value: ByteArray) = gatt.write(characteristic, value)
    }
    /** The light's content: palette, mode and effects. Only a Lightboks has any. */
    private val lightboks = Lightboks(link, _state, scope, ::report, clock::now)
    override val output: LightOutput get() = lightboks

    override fun connect() {
        // Reconnect reuses this controller, so drop the previous session's collectors first.
        stopSession()
        // Set before launching, so a caller awaiting the outcome never sees the previous FAILED/DISCONNECTED.
        _state.update { it.copy(connection = ConnectionState.CONNECTING, message = null) }
        jobs += scope.launch {
            try {
                gatt.connect()
                setUp()
                _state.update { it.copy(connection = ConnectionState.CONNECTED) }
            } catch (e: Exception) {
                // Superseded by a newer connect(): rethrow, since this disconnect would tear down the new
                // attempt. A timeout is not a cancellation of this job and is reported as a failure.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "connect failed", e)
                gatt.disconnect()
                _state.update { it.copy(connection = ConnectionState.FAILED, message = e.message ?: "Connection failed") }
            }
        }
        jobs += scope.launch {
            gatt.state.collect { s ->
                if (s == Gatt.State.DISCONNECTED && _state.value.connection == ConnectionState.CONNECTED) dropped()
            }
        }
        jobs += scope.launch {
            _state.map { it.connection == ConnectionState.CONNECTED && it.activeInputs.isEmpty() }.distinctUntilChanged().collect { idle ->
                _state.update { it.copy(idleSince = if (idle) clock.now() else null) }
            }
        }
        jobs += scope.launch { for (v in volumeWrites) attempt("volume") { protocol.setVolume(wire, v) } }
        jobs += scope.launch { for (id in fxWrites) attempt("effect") { lightboks.applyFx(id) } }
        jobs += scope.launch { for (v in brightnessWrites) attempt("brightness") { lightboks.applyBrightness(v) } }
        jobs += scope.launch {
            for (w in eqWrites) attempt("EQ") { if (w.bands != null) protocol.setCustomEq(wire, w.bands) else protocol.setEqMode(wire, w.mode) }
        }
    }

    private fun dropped() =
        _state.update { it.copy(connection = ConnectionState.DISCONNECTED, message = if (family == Family.LIGHT) "Lightboks disconnected" else "Speaker disconnected") }

    /** Connects again and waits for the outcome; for the device list, which keeps a held light connected. */
    override suspend fun reconnect(): Boolean {
        connect()
        return awaitConnection()
    }

    /** Waits for an in-flight connect to finish; true if it ended connected. */
    override suspend fun awaitConnection(): Boolean =
        state.first { it.connection != ConnectionState.CONNECTING }.connection == ConnectionState.CONNECTED

    override fun disconnect() {
        stopSession()
        gatt.disconnect()
    }

    private fun stopSession() {
        setRpc(null)
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun setRpc(client: RpcClient?) {
        rpc?.close()
        rpc = client
        _state.update { it.copy(features = protocol.features(wire)) }
    }

    private suspend fun setUp() {
        val family = detectFamily()
        _state.update { it.copy(family = family) }
        gatt.requestMtu(_state.value.model?.mtuCap ?: protocol.mtu)

        jobs += scope.launch { gatt.notifications.collect { onNotify(it.characteristic, it.value) } }

        setUpRpc()
        // Unlock before anything else, as the official app does: a pincode-protected speaker may refuse or drop
        // other reads and calls until then. Still locked, the state loads once the pincode is entered.
        if (checkSecurity()) loadState()
    }

    private suspend fun loadState() {
        protocol.readState(wire)
        for (c in protocol.subscriptions) if (gatt.hasCharacteristic(c)) attempt(null) { gatt.enableNotifications(c) }
    }

    private fun detectFamily(): Family = when {
        gatt.hasService(Uuids.SPEAKER_CONTROL_SERVICE) -> Family.BIFROST
        gatt.hasService(Uuids.LIGHT_CONTROL_SERVICE) -> Family.LIGHT
        gatt.hasCharacteristic(Uuids.LEGACY_VOLUME) -> Family.LEGACY
        else -> advertised.family
    }

    private suspend fun setUpRpc() {
        val (tx, rx) = protocol.rpcPair(_state.value.firmware) ?: return setRpc(null)
        if (!gatt.hasCharacteristic(tx) || !gatt.hasCharacteristic(rx)) return setRpc(null)
        val client = RpcClient(gatt, tx, rx, scope)
        try {
            client.start()
            setRpc(client)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "RPC unavailable", e)
            client.close()
            setRpc(null)
        }
    }

    private suspend fun probeRpc() {
        val rpc = rpc ?: return
        try {
            val id = rpc.call("DeviceInformationService", "GetProductModel", timeoutMs = RPC_PROBE_MS).int(1)
            ProductModel.fromId(id)?.let { m -> _state.update { it.copy(model = m) } }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "RPC channel not answering; continuing without it")
            setRpc(null)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.d(TAG, "GetProductModel: ${e.message}")
        }
    }

    private fun onNotify(c: UUID, v: ByteArray) {
        if (v.isEmpty()) return
        _state.update { protocol.decode(it, c, v) }
    }

    // --- security ----------------------------------------------------------------------------

    /** True when the device is usable now: it has no pincode, or the saved one unlocked it. */
    private suspend fun checkSecurity(): Boolean {
        val flags = wire.read(protocol.security)?.firstOrNull()?.toInt()?.and(0xFF)?.let(SecurityFlags::from) ?: advertised.security ?: return true
        _state.update { it.copy(locked = flags.locked) }
        if (!flags.pincodeEnabled) return true
        val saved = pincodes.get(address)
        if (saved != null && authenticate(saved)) return true
        _state.update { it.copy(pincodeRequired = true) }
        return false
    }

    fun submitPincode(pin: String) = scope.launch {
        if (authenticate(pin)) {
            pincodes.put(address, pin)
            _state.update { it.copy(pincodeRequired = false, message = "Unlocked") }
            loadState()
        } else {
            // Keep a more specific reason (lockout, unsupported firmware, no answer) that authenticate() already set.
            _state.update { it.copy(message = it.message ?: "Wrong pincode") }
        }
    }

    private suspend fun authenticate(pin: String): Boolean {
        if (rpc == null) {
            _state.update { it.copy(message = "This firmware needs the official app for pincode entry") }
            return false
        }
        return try {
            val response = protocol.authenticate(wire, pin)
            val ok = response.int(1) == 0
            if (!ok) {
                val lockoutMs = response.first(2)?.varint ?: 0
                if (lockoutMs > 0) _state.update { it.copy(message = "Too many attempts; locked for ${lockoutMs / 1000}s") }
                else pincodes.remove(address)
            }
            ok
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            // The speaker never judged the pincode, so it is not "wrong" and the saved one is kept.
            Log.w(TAG, "authenticate failed", e)
            _state.update { it.copy(message = "${if (family == Family.LIGHT) "The Lightboks" else "The speaker"} didn't answer (${e.message ?: "no reply"}); try again") }
            false
        }
    }

    // --- controls ------------------------------------------------------------------------------

    fun setVolume(value: Int) {
        val v = value.coerceIn(0, 255)
        _state.update { it.copy(volume = v) }
        volumeWrites.trySend(v)
    }

    /** Where unmuting returns to. The speaker has no mute of its own, so muted means volume 0. */
    private var unmuteVolume: Int? = null

    fun toggleMute() {
        val v = _state.value.volume ?: return
        if (v > 0) {
            unmuteVolume = v
            setVolume(0)
        } else {
            // Muted from the speaker's own buttons or by dragging to 0, there is nothing to return to.
            setVolume(unmuteVolume ?: UNMUTE_DEFAULT)
        }
    }

    override fun setBrightness(value: Int) {
        val v = value.coerceIn(0, 255)
        _state.update { it.copy(brightness = v) }
        brightnessWrites.trySend(v)
    }

    override fun setEnergyLevel(level: EnergyLevel) {
        control("light mode") { lightboks.applyEnergyLevel(level) }
    }

    override fun setPalette(palette: LightPalette) {
        control("palette") { lightboks.applyPalette(palette) }
    }

    override suspend fun fadePalette(from: LightPalette, to: LightPalette, durationMs: Long) = lightboks.fadePalette(from, to, durationMs)

    override fun resetPalette() {
        control("palette") { lightboks.resetPalette() }
    }

    /** FX pad: an animation plays while held; [fx] null releases it. Latest press wins. */
    fun holdFx(fx: LightFx?) {
        fxWrites.trySend(fx?.id ?: LightContentCodec.NO_ID)
    }

    override val dropping: Boolean get() = lightboks.dropping

    fun holdDrop(held: Boolean) = lightboks.holdDrop(held)

    fun setStereoRole(role: StereoRole) = control("stereo role") {
        protocol.setStereoRole(wire, role)
        _state.update { it.copy(stereoRole = role) }
    }

    fun setTeamUp(mode: TeamUpMode) = control("team-up") {
        protocol.setTeamUp(wire, mode)
        _state.update { it.copy(teamUp = mode) }
    }

    /** Switches to a built-in profile; the speaker keeps its custom curve for later. */
    fun setEq(mode: EqMode) {
        _state.update { it.copy(eq = mode) }
        eqWrites.trySend(EqWrite(mode, null))
    }

    /** Plays [bandsDb] as the user-defined EQ. Streams while dragging; only the latest curve is sent. */
    fun setCustomEq(bandsDb: List<Int>) {
        _state.update { it.copy(eq = EqMode.USER, eqBands = bandsDb) }
        eqWrites.trySend(EqWrite(EqMode.USER, bandsDb))
    }

    fun setName(name: String) = control("name") {
        call("DeviceInformationService", "SetName", proto { string(1, name) })
        _state.update { it.copy(name = name) }
    }

    fun setAutoOff(minutes: Int) = control("auto-off") {
        val r = call("SystemService", "SetAutoOff", proto { uint32(1, minutes) })
        if (r.int(1) != 0) throw IllegalStateException("speaker rejected the value")
        // A new setting starts the timer over.
        _state.update { it.copy(autoOffMinutes = minutes, idleSince = it.idleSince?.let { clock.now() }) }
    }

    /**
     * SystemService.PowerOff{type: REBOOT}. Only RPC devices can restart. The link drops while it
     * boots, and the device is connected again once it is back, for up to [RESTART_BACK_MS].
     */
    fun restart() = scope.launch {
        val rpc = rpc ?: return@launch
        _state.update { it.copy(message = "Restarting…") }
        // The device reboots before it can answer, so a timeout or dropped link here means it worked.
        attempt(null) { rpc.call("SystemService", "PowerOff", proto { int32(1, 1) }, timeoutMs = 3_000) }
        withTimeoutOrNull(RESTART_BACK_MS) {
            state.first { it.connection != ConnectionState.CONNECTED }
            while (true) {
                // A connect already under way (the user tapped Reconnect, or the device list) is awaited, not doubled.
                val back = if (state.value.connection == ConnectionState.CONNECTING) awaitConnection() else reconnect()
                if (back) break
                delay(RESTART_RETRY_MS)
            }
        }
    }

    /**
     * SystemService.PowerOff{type: POWEROFF} on RPC devices, as the official app does; they ignore a
     * write to SYSTEM_POWER. Like a restart, the link drops before the answer, so that means it worked.
     */
    override fun powerOff() {
        val rpc = rpc
        if (rpc != null) {
            scope.launch { attempt(null) { rpc.call("SystemService", "PowerOff", proto { int32(1, 0) }, timeoutMs = 3_000) } }
            return
        }
        control("power off") { protocol.powerOff(wire) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // --- helpers -------------------------------------------------------------------------------

    private suspend fun call(service: String, method: String, request: ByteArray = ByteArray(0)): ProtoMessage =
        (rpc ?: throw IllegalStateException("not supported on this ${if (family == Family.LIGHT) "Lightboks" else "speaker"}")).call(service, method, request)

    private fun control(what: String, block: suspend () -> Unit) = scope.launch { attempt(what, block) }

    private suspend fun attempt(what: String?, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // a cancelled session isn't a failed control
            report(what, e)
        }
    }

    /** A failed control, or with [what] null an optional step older firmware may lack. */
    private fun report(what: String?, e: Exception) {
        if (what == null) {
            Log.d(TAG, "optional step failed: ${e.message}")
            return
        }
        Log.w(TAG, "$what failed", e)
        _state.update { it.copy(message = "Couldn't change $what: ${e.message}") }
    }

    companion object {
        private const val TAG = "SpeakerController"
        /** About 30 %, a safe level to unmute to when the previous one is unknown. */
        private const val UNMUTE_DEFAULT = 77
        private const val RPC_PROBE_MS = 4_000L
        /** Long enough for a reboot and a connection timed out on the way. */
        const val RESTART_BACK_MS = 60_000L
        private const val RESTART_RETRY_MS = 2_000L

        /** [device]'s dummy for demo mode, a [SimulatedBoks] in the state it starts in. */
        fun demo(device: DemoDevice, scope: CoroutineScope, pincodes: PincodeStore) =
            SpeakerController(SimulatedBoks(device.initial, scope), device.advert, scope, pincodes, demo = true)

        fun bifrostInputs(mask: Int) = listOf(1 to "Bluetooth", 2 to "Team-up", 4 to "AUX", 8 to "XLR 1", 16 to "XLR 2")
            .filter { mask and it.first != 0 }.map { it.second }

        fun legacyInputs(mask: Int) = listOf(1 to "Channel 1", 2 to "Channel 2", 4 to "AUX", 8 to "Bluetooth", 16 to "SKAA")
            .filter { mask and it.first != 0 }.map { it.second }

        fun skaaMode(text: String) = when (text.trim('\u0000', ' ').lowercase()) {
            "solo" -> TeamUpMode.SOLO
            "host" -> TeamUpMode.HOST
            "join" -> TeamUpMode.JOIN
            else -> null
        }
    }
}
