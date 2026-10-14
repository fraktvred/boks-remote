package dev.boksremote.speaker

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import dev.boksremote.ble.GattConnection
import dev.boksremote.proto.ProtoMessage
import dev.boksremote.proto.proto
import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Family
import dev.boksremote.protocol.ProductModel
import dev.boksremote.protocol.RpcClient
import dev.boksremote.protocol.SecurityFlags
import dev.boksremote.protocol.Uuids
import dev.boksremote.protocol.versionAtLeast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val rpcAvailable: Boolean = false,
    val pincodeRequired: Boolean = false,
    val locked: Boolean = false,
    val message: String? = null,
)

/**
 * One connected speaker. Routes each control to the right characteristic or RPC for the
 * product family and keeps [state] in sync with speaker notifications.
 *
 * Without a [gatt] it is a demo device (see [demo]): every control lands in [state] and goes nowhere else.
 */
class SpeakerController private constructor(
    private val gatt: GattConnection?,
    private val address: String,
    private val advert: Advertisement?,
    initial: SpeakerState,
    private val scope: CoroutineScope,
    private val pincodes: PincodeStore,
) : Light {
    constructor(context: Context, device: BluetoothDevice, advert: Advertisement?, scope: CoroutineScope, pincodes: PincodeStore) : this(
        GattConnection(context, device),
        device.address,
        advert,
        SpeakerState(family = advert?.family, model = advert?.model, name = advert?.name, firmware = advert?.firmware),
        scope,
        pincodes,
    )

    val demo get() = gatt == null
    /** The Bluetooth link, for what only a real device does: connecting, reading and subscribing. */
    private val ble get() = checkNotNull(gatt) { "a demo device has no Bluetooth link" }
    private var rpc: RpcClient? = null
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<SpeakerState> = _state

    // Slider drags produce many values; only the latest matters, so a conflated channel drops stale ones.
    private val volumeWrites = Channel<Int>(Channel.CONFLATED)
    private val brightnessWrites = Channel<Int>(Channel.CONFLATED)
    private val fxWrites = Channel<UUID>(Channel.CONFLATED)
    // Presets and curves share one queue so a late curve from a drag can never override a newer preset.
    private val eqWrites = Channel<EqWrite>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    private val family get() = _state.value.family

    private val link = object : LightLink {
        override suspend fun call(service: String, method: String, request: ByteArray) = this@SpeakerController.call(service, method, request)
        override suspend fun write(characteristic: UUID, value: ByteArray) = this@SpeakerController.write(characteristic, value)
    }
    /** The light's content: palette, mode and effects. Only a Lightboks has any. */
    val lightboks = Lightboks(link, _state, scope, ::report)

    fun connect() {
        // Reconnect reuses this controller, so drop the previous session's collectors first.
        stopSession()
        if (demo) {
            // There is nothing to reach: the demo device is just as it was left.
            _state.update { it.copy(connection = ConnectionState.CONNECTED, message = null) }
        } else {
            connectLink()
        }
        jobs += scope.launch {
            _state.map { it.connection == ConnectionState.CONNECTED && it.activeInputs.isEmpty() }.distinctUntilChanged().collect { idle ->
                _state.update { it.copy(idleSince = if (idle) Clock.ELAPSED.now() else null) }
            }
        }
        if (demo) jobs += scope.launch { demoAutoOff() }
        jobs += scope.launch { for (v in volumeWrites) attempt("volume") { writeVolume(v) } }
        jobs += scope.launch {
            for (id in fxWrites) attempt("effect") { lightboks.applyFx(id) }
        }
        jobs += scope.launch { for (v in brightnessWrites) attempt("brightness") { lightboks.applyBrightness(v) } }
        jobs += scope.launch { for (w in eqWrites) attempt("EQ") { if (w.bands != null) writeCustomEq(w.bands) else writeEqMode(w.mode) } }
    }

    private fun connectLink() {
        // Set before launching, so a caller awaiting the outcome never sees the previous FAILED/DISCONNECTED.
        _state.update { it.copy(connection = ConnectionState.CONNECTING, message = null) }
        jobs += scope.launch {
            try {
                ble.connect()
                setUp()
                _state.update { it.copy(connection = ConnectionState.CONNECTED) }
            } catch (e: Exception) {
                // Superseded by a newer connect(): rethrow, since this disconnect would tear down the new
                // attempt. A timeout is not a cancellation of this job and is reported as a failure.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "connect failed", e)
                ble.disconnect()
                _state.update { it.copy(connection = ConnectionState.FAILED, message = e.message ?: "Connection failed") }
            }
        }
        jobs += scope.launch {
            ble.state.collect { s ->
                if (s == GattConnection.State.DISCONNECTED && _state.value.connection == ConnectionState.CONNECTED) dropped()
            }
        }
    }

    /** A demo speaker turns itself off like a real one once its auto power-off time is up. */
    private suspend fun demoAutoOff() {
        _state.map { s -> s.idleSince?.let { since -> s.autoOffMinutes?.takeIf { it > 0 }?.let { since + it * 60_000L } } }
            .distinctUntilChanged()
            .collectLatest { offAt ->
                if (offAt == null) return@collectLatest
                delay(offAt - Clock.ELAPSED.now())
                dropped()
            }
    }

    private fun dropped() =
        _state.update { it.copy(connection = ConnectionState.DISCONNECTED, message = if (family == Family.LIGHT) "Lightboks disconnected" else "Speaker disconnected") }

    /** Connects again and waits for the outcome; for callers that keep the link alive (the DMX bridge). */
    suspend fun reconnect(): Boolean {
        connect()
        return awaitConnection()
    }

    /** Waits for an in-flight connect to finish; true if it ended connected. */
    suspend fun awaitConnection(): Boolean =
        state.first { it.connection != ConnectionState.CONNECTING }.connection == ConnectionState.CONNECTED

    fun disconnect() {
        stopSession()
        gatt?.disconnect()
    }

    private fun stopSession() {
        rpc?.close()
        rpc = null
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun setUp() {
        val family = detectFamily()
        _state.update { it.copy(family = family) }
        val model = _state.value.model
        ble.requestMtu(model?.mtuCap ?: if (family == Family.LEGACY) 96 else 247)

        jobs += scope.launch { ble.notifications.collect { onNotify(it.characteristic, it.value) } }

        setUpRpc(family)
        readInitialState(family)
        subscribe(family)
        checkSecurity(family)
    }

    private fun detectFamily(): Family = when {
        ble.hasService(Uuids.SPEAKER_CONTROL_SERVICE) -> Family.BIFROST
        ble.hasService(Uuids.LIGHT_CONTROL_SERVICE) -> Family.LIGHT
        ble.hasCharacteristic(Uuids.LEGACY_VOLUME) -> Family.LEGACY
        else -> advert?.family ?: throw IllegalStateException("Not a supported SOUNDBOKS device")
    }

    private suspend fun setUpRpc(family: Family) {
        val (tx, rx) = when (family) {
            Family.BIFROST, Family.LIGHT -> Uuids.RPC_TX to Uuids.RPC_RX
            // Pre-3.0 legacy firmware speaks an ASCII serial protocol on the same pair instead of RPC.
            Family.LEGACY -> if (versionAtLeast(_state.value.firmware, 3) || _state.value.firmware == null) Uuids.LEGACY_UART_TX to Uuids.LEGACY_UART_RX else return
        }
        if (!ble.hasCharacteristic(tx) || !ble.hasCharacteristic(rx)) return
        val client = RpcClient(ble, tx, rx, scope)
        try {
            client.start()
            rpc = client
            _state.update { it.copy(rpcAvailable = true) }
        } catch (e: Exception) {
            Log.w(TAG, "RPC unavailable", e)
            client.close()
        }
    }

    private suspend fun readInitialState(family: Family) {
        when (family) {
            Family.BIFROST, Family.LIGHT -> {
                readByte(Uuids.BATTERY_LEVEL)?.let { b -> _state.update { it.copy(battery = b.coerceIn(0, 100)) } }
                readByte(Uuids.TEAMUP_MODE)?.let { b -> _state.update { it.copy(teamUp = TeamUpMode.entries.getOrNull(b)) } }
                if (family == Family.BIFROST) {
                    readByte(Uuids.VOLUME)?.let { b -> _state.update { it.copy(volume = b) } }
                    readByte(Uuids.STEREO_ROLE)?.let { b -> _state.update { it.copy(stereoRole = StereoRole.entries.getOrNull(b)) } }
                    readByte(Uuids.SOUND_STATUS)?.let { b -> _state.update { it.copy(activeInputs = bifrostInputs(b)) } }
                } else {
                    readByte(Uuids.LIGHT_BRIGHTNESS)?.let { b -> _state.update { it.copy(brightness = b) } }
                    readByte(Uuids.LIGHT_ENERGY_LEVEL)?.let { b -> _state.update { it.copy(energyLevel = EnergyLevel.entries.getOrNull(b)) } }
                }
                readRpcInfo(family)
            }
            Family.LEGACY -> {
                readString(Uuids.LEGACY_NAME)?.let { s -> _state.update { it.copy(name = s) } }
                readString(Uuids.LEGACY_FIRMWARE)?.let { s -> _state.update { it.copy(firmware = s) } }
                readByte(Uuids.LEGACY_VOLUME)?.let { b -> _state.update { it.copy(volume = b) } }
                readByte(Uuids.LEGACY_STEREO_ROLE)?.let { b -> _state.update { it.copy(stereoRole = StereoRole.entries.getOrNull(b)) } }
                readLegacyEq()
                readByte(Uuids.LEGACY_SOUND_STATUS)?.let { b -> _state.update { it.copy(activeInputs = legacyInputs(b)) } }
                readString(Uuids.LEGACY_SKAA_MODE)?.let { s -> _state.update { it.copy(teamUp = skaaMode(s)) } }
            }
        }
    }

    /** Device-information and settings that only exist as RPCs. Each is optional: older firmware may lack a method. */
    private suspend fun readRpcInfo(family: Family) {
        val rpc = rpc ?: run {
            readString(Uuids.FIRMWARE_REVISION)?.let { s -> _state.update { it.copy(firmware = s) } }
            return
        }
        // Probe with a short timeout: if the channel is silent, drop RPC rather than stall every later call.
        try {
            val id = rpc.call("DeviceInformationService", "GetProductModel", timeoutMs = 4_000).int(1)
            ProductModel.fromId(id)?.let { m -> _state.update { it.copy(model = m) } }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "RPC channel not answering; continuing without it")
            rpc.close()
            this.rpc = null
            _state.update { it.copy(rpcAvailable = false) }
            return
        } catch (e: Exception) {
            Log.d(TAG, "GetProductModel: ${e.message}")
        }
        attemptQuietly { rpc.call("DeviceInformationService", "GetFirmwareVersion").string(1).let { v -> _state.update { it.copy(firmware = v) } } }
        attemptQuietly { rpc.call("DeviceInformationService", "GetName").string(1).takeIf { it.isNotBlank() }?.let { n -> _state.update { it.copy(name = n) } } }
        attemptQuietly { rpc.call("SystemService", "GetAutoOff").int(1).let { m -> _state.update { it.copy(autoOffMinutes = m) } } }
        if (family == Family.LIGHT) {
            attemptQuietly {
                val raw = rpc.call("LightContentService", "GetActivePalette")
                val p = LightContentCodec.parsePalette(raw)
                _state.update { it.copy(palette = p, paletteIsDefault = p == null) }
            }
        }
        if (family == Family.BIFROST) {
            attemptQuietly { rpc.call("AudioControlService", "GetEqMode").int(1).let { e -> _state.update { it.copy(eq = EqMode.entries.getOrNull(e)) } } }
            attemptQuietly {
                val bands = rpc.call("AudioControlService", "GetEqBands").bytes(2)
                _state.update { it.copy(eqBands = EqCurve.fromWire(bands)) }
            }
        }
    }

    private suspend fun subscribe(family: Family) {
        val chars = when (family) {
            Family.BIFROST -> listOf(Uuids.VOLUME, Uuids.STEREO_ROLE, Uuids.SOUND_STATUS, Uuids.BATTERY_LEVEL, Uuids.TEAMUP_MODE)
            Family.LIGHT -> listOf(Uuids.LIGHT_BRIGHTNESS, Uuids.LIGHT_ENERGY_LEVEL, Uuids.BATTERY_LEVEL, Uuids.TEAMUP_MODE)
            Family.LEGACY -> listOf(Uuids.LEGACY_VOLUME, Uuids.LEGACY_STEREO_ROLE, Uuids.LEGACY_SOUND_STATUS, Uuids.LEGACY_SKAA_MODE, Uuids.LEGACY_POWER_MODE)
        }
        for (c in chars) if (ble.hasCharacteristic(c)) attemptQuietly { ble.enableNotifications(c) }
    }

    private fun onNotify(c: UUID, v: ByteArray) {
        if (v.isEmpty()) return
        val b = v[0].toInt() and 0xFF
        _state.update {
            when (c) {
                Uuids.VOLUME, Uuids.LEGACY_VOLUME -> it.copy(volume = b)
                Uuids.BATTERY_LEVEL -> it.copy(battery = b.coerceIn(0, 100))
                Uuids.STEREO_ROLE, Uuids.LEGACY_STEREO_ROLE -> it.copy(stereoRole = StereoRole.entries.getOrNull(b))
                Uuids.TEAMUP_MODE -> it.copy(teamUp = TeamUpMode.entries.getOrNull(b))
                Uuids.SOUND_STATUS -> it.copy(activeInputs = bifrostInputs(b))
                Uuids.LEGACY_SOUND_STATUS -> it.copy(activeInputs = legacyInputs(b))
                Uuids.LEGACY_SKAA_MODE -> it.copy(teamUp = skaaMode(String(v, Charsets.US_ASCII)))
                Uuids.LIGHT_BRIGHTNESS -> it.copy(brightness = b)
                Uuids.LIGHT_ENERGY_LEVEL -> it.copy(energyLevel = EnergyLevel.entries.getOrNull(b))
                Uuids.LEGACY_POWER_MODE -> if (b == 0) it.copy(message = "Speaker is powering off") else it
                else -> it
            }
        }
    }

    // --- security ----------------------------------------------------------------------------

    private suspend fun checkSecurity(family: Family) {
        val flagsChar = if (family == Family.LEGACY) Uuids.LEGACY_SECURITY_LEVEL else Uuids.SYSTEM_STATE
        val flags = readByte(flagsChar)?.let(SecurityFlags::from) ?: advert?.security ?: return
        _state.update { it.copy(locked = flags.locked) }
        if (!flags.pincodeEnabled) return
        val saved = pincodes.get(address)
        if (saved != null && authenticate(saved)) return
        _state.update { it.copy(pincodeRequired = true) }
    }

    fun submitPincode(pin: String) = scope.launch {
        if (authenticate(pin)) {
            pincodes.put(address, pin)
            _state.update { it.copy(pincodeRequired = false, message = "Unlocked") }
            readInitialState(family ?: return@launch)
        } else {
            // Keep a more specific reason (lockout, unsupported firmware) that authenticate() already set.
            _state.update { it.copy(message = it.message ?: "Wrong pincode") }
        }
    }

    private suspend fun authenticate(pin: String): Boolean {
        val rpc = rpc ?: run {
            _state.update { it.copy(message = "This firmware needs the official app for pincode entry") }
            return false
        }
        return try {
            val response = if (family == Family.LEGACY) {
                rpc.call("LegacySpeakerSecurityService", "Authenticate", proto { bytes(1, pin.toByteArray()) })
            } else {
                rpc.call("SecurityService", "AuthenticatePincode", proto { string(1, pin) })
            }
            val ok = response.int(1) == 0
            if (!ok) {
                val lockoutMs = response.first(2)?.varint ?: 0
                if (lockoutMs > 0) _state.update { it.copy(message = "Too many attempts; locked for ${lockoutMs / 1000}s") }
                else pincodes.remove(address)
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "authenticate failed", e)
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

    private suspend fun writeVolume(v: Int) {
        val c = if (family == Family.LEGACY) Uuids.LEGACY_VOLUME else Uuids.VOLUME
        write(c, byteArrayOf(v.toByte()))
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
        write(if (family == Family.LEGACY) Uuids.LEGACY_STEREO_ROLE else Uuids.STEREO_ROLE, byteArrayOf(role.ordinal.toByte()))
        _state.update { it.copy(stereoRole = role) }
    }

    fun setTeamUp(mode: TeamUpMode) = control("team-up") {
        if (family == Family.LEGACY) {
            write(Uuids.LEGACY_SKAA_MODE, mode.name.lowercase().toByteArray(Charsets.US_ASCII))
        } else {
            write(Uuids.TEAMUP_MODE, byteArrayOf(mode.ordinal.toByte()))
        }
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

    private suspend fun writeEqMode(mode: EqMode) {
        if (family == Family.LEGACY) {
            write(Uuids.LEGACY_EQ_MODE, byteArrayOf(mode.ordinal.toByte()))
        } else {
            call("AudioControlService", "SetEqMode", proto { int32(1, mode.ordinal) })
        }
    }

    private suspend fun writeCustomEq(bandsDb: List<Int>) {
        val wire = EqCurve.toWire(bandsDb)
        if (family == Family.LEGACY) {
            write(Uuids.LEGACY_EQ_MODE, byteArrayOf(EqMode.USER.ordinal.toByte()) + wire)
        } else {
            // Same order as the official app: switch to the user curve, then load its bands.
            call("AudioControlService", "SetEqMode", proto { int32(1, EqMode.USER.ordinal) })
            call("AudioControlService", "SetEqBands", proto { uint32(1, EqCurve.BANDS); bytes(2, wire) })
        }
    }

    /** Legacy EQ characteristic: mode byte, followed by the bands when the mode is user-defined. */
    private suspend fun readLegacyEq() {
        if (!ble.hasCharacteristic(Uuids.LEGACY_EQ_MODE)) return
        val raw = try { ble.read(Uuids.LEGACY_EQ_MODE) } catch (e: Exception) { Log.d(TAG, "read EQ: ${e.message}"); return }
        val mode = raw.firstOrNull()?.toInt()?.and(0xFF)?.let { EqMode.entries.getOrNull(it) }
        val bands = if (mode == EqMode.USER) EqCurve.fromWire(raw.copyOfRange(1, raw.size)) else null
        _state.update { it.copy(eq = mode, eqBands = bands) }
    }

    /** AUX input gain in dB (AudioControlService.SetInputConfig). Accepted by the Mix, effect unverified. */
    suspend fun setAuxGain(db: Float) = setAuxInput { message(2) { message(1) { float(1, db) } } }

    /** AUX input EQ, bands in dB. The band count isn't reported by the speaker. */
    suspend fun setAuxEq(bandsDb: List<Float>) = setAuxInput {
        val packed = java.nio.ByteBuffer.allocate(4 * bandsDb.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bandsDb.forEach { packed.putFloat(it) }
        message(3) { message(1) { bytes(1, packed.array()) } }
    }

    private suspend fun setAuxInput(config: dev.boksremote.proto.ProtoWriter.() -> Unit) {
        // InputConfigMessage{input: {type: AUX, index: 0}, configs: [config]}
        val r = call("AudioControlService", "SetInputConfig", proto { message(1) { int32(1, 0); uint32(2, 0) }; message(2, config) })
        if (r.int(1) != 0) throw IllegalStateException("speaker rejected the input setting")
    }

    fun setName(name: String) = control("name") {
        call("DeviceInformationService", "SetName", proto { string(1, name) })
        _state.update { it.copy(name = name) }
    }

    fun setAutoOff(minutes: Int) = control("auto-off") {
        val r = call("SystemService", "SetAutoOff", proto { uint32(1, minutes) })
        if (r.int(1) != 0) throw IllegalStateException("speaker rejected the value")
        // A new setting starts the timer over.
        _state.update { it.copy(autoOffMinutes = minutes, idleSince = it.idleSince?.let { Clock.ELAPSED.now() }) }
    }

    /** SystemService.PowerOff{type: REBOOT}. Only RPC devices can restart; the link drops while it boots. */
    fun restart() = scope.launch {
        if (demo) {
            // Gone while it boots, then back by itself.
            dropped()
            delay(DEMO_RESTART_MS)
            if (_state.value.connection == ConnectionState.DISCONNECTED) connect()
            return@launch
        }
        val rpc = rpc ?: return@launch
        _state.update { it.copy(message = "Restarting…") }
        // The light reboots before it can answer, so a timeout or dropped link here means it worked.
        attemptQuietly { rpc.call("SystemService", "PowerOff", proto { int32(1, 1) }, timeoutMs = 3_000) }
    }

    override fun powerOff() {
        control("power off") {
            if (family == Family.LEGACY) write(Uuids.LEGACY_POWER_MODE, byteArrayOf(0))
            else write(Uuids.SYSTEM_POWER, byteArrayOf(0, 0)) // [OFF, no reboot]
            if (demo) dropped()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // --- helpers -------------------------------------------------------------------------------

    private fun requireRpc() = rpc ?: throw IllegalStateException("not supported on this speaker")

    /** A characteristic write; a demo device takes it without a word. */
    private suspend fun write(c: UUID, value: ByteArray) {
        gatt?.write(c, value)
    }

    /** An RPC call; a demo device answers every one with an empty message, which reads as success. */
    private suspend fun call(service: String, method: String, request: ByteArray = ByteArray(0)): ProtoMessage =
        if (demo) ProtoMessage(emptyList()) else requireRpc().call(service, method, request)

    private fun control(what: String, block: suspend () -> Unit) = scope.launch { attempt(what, block) }

    private suspend fun attempt(what: String?, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // a cancelled session isn't a failed control
            report(what, e)
        }
    }

    private suspend fun attemptQuietly(block: suspend () -> Unit) = attempt(null, block)

    /** A failed control, or with [what] null an optional step older firmware may lack. */
    private fun report(what: String?, e: Exception) {
        if (what == null) {
            Log.d(TAG, "optional step failed: ${e.message}")
            return
        }
        Log.w(TAG, "$what failed", e)
        _state.update { it.copy(message = "Couldn't change $what: ${e.message}") }
    }

    private suspend fun readByte(c: UUID): Int? {
        if (!ble.hasCharacteristic(c)) return null
        return try { ble.read(c).firstOrNull()?.toInt()?.and(0xFF) } catch (e: Exception) { Log.d(TAG, "read $c: ${e.message}"); null }
    }

    private suspend fun readString(c: UUID): String? {
        if (!ble.hasCharacteristic(c)) return null
        return try { String(ble.read(c), Charsets.UTF_8).trim('\u0000', ' ').ifBlank { null } } catch (e: Exception) { null }
    }

    companion object {
        private const val TAG = "SpeakerController"
        /** About 30 %, a safe level to unmute to when the previous one is unknown. */
        private const val UNMUTE_DEFAULT = 77
        private const val DEMO_RESTART_MS = 3_000L

        /** [device]'s dummy for demo mode, in the state it starts in. */
        fun demo(device: DemoDevice, scope: CoroutineScope, pincodes: PincodeStore) =
            SpeakerController(null, device.advert.address, device.advert, device.initial, scope, pincodes)

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
