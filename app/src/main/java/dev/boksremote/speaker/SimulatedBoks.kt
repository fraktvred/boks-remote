package dev.boksremote.speaker

import dev.boksremote.ble.Gatt
import dev.boksremote.ble.GattException
import dev.boksremote.ble.Notification
import dev.boksremote.proto.ProtoMessage
import dev.boksremote.proto.proto
import dev.boksremote.protocol.Family
import dev.boksremote.protocol.Hdlc
import dev.boksremote.protocol.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A SOUNDBOKS speaker or Lightboks in software, behind the same [Gatt] seam as a real one: it keeps
 * its settings as characteristics and answers HDLC-framed RPC calls the way the firmware does, in
 * notifications no longer than the MTU allows. Demo mode's devices are these, starting from
 * [DemoDevice.initial]; tests script them further.
 *
 * What [device] leaves null the device lacks: no characteristic for it, or an RPC method it doesn't
 * know. With a [pincode] it refuses every read and call but the security ones until unlocked.
 */
class SimulatedBoks(
    initial: SpeakerState,
    private val scope: CoroutineScope,
    var pincode: String? = null,
    /** RPC on its characteristic pair; false for firmware without it. */
    private val rpc: Boolean = true,
    private val maxMtu: Int = initial.model?.mtuCap ?: 247,
) : Gatt {
    /** What the device is set to now, as it would report it. */
    var device: SpeakerState = initial
        private set
    private val family = checkNotNull(initial.family) { "a simulated device needs a family" }

    private val _state = MutableStateFlow(Gatt.State.DISCONNECTED)
    override val state: StateFlow<Gatt.State> = _state
    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val notifications: SharedFlow<Notification> = _notifications
    override var mtu = DEFAULT_MTU
        private set

    // --- scripting, for tests ---------------------------------------------------------------------

    /** Reads, writes and calls in the order they came: `read NAME`, `write NAME`, `call Service/Method`. */
    val log = mutableListOf<String>()
    /** The size of each piece an RPC request arrived in. */
    val rpcChunks = mutableListOf<Int>()
    /** Connecting fails while false: out of range, or booting. */
    var reachable = true
    /** Methods it takes but never answers, until [answerSilent]. */
    val silent = mutableSetOf<String>()
    /** Methods answered with this RPC status instead of their answer. */
    val failing = mutableMapOf<String, Int>()
    /** A wrong pincode locks it out for this long, as the firmware reports it. */
    var lockoutMs = 0L

    /** The link is lost: out of range, or switched off. */
    fun drop() {
        _state.value = Gatt.State.DISCONNECTED
    }

    /** A change on the device itself, say from its buttons, notified if subscribed to. */
    fun notify(characteristic: UUID, value: ByteArray) {
        if (characteristic in enabled) _notifications.tryEmit(Notification(characteristic, value))
    }

    /** Answers the calls held by [silent] now, late, with the call ids they came with. */
    fun answerSilent() {
        val replies = unanswered.toList()
        unanswered.clear()
        replies.forEach(::reply)
    }

    // --- the link -------------------------------------------------------------------------------

    private val enabled = mutableSetOf<UUID>()
    private val decoder = Hdlc.Decoder()
    private var unlocked = false
    private val unanswered = mutableListOf<ByteArray>()
    private var autoOff: Job? = null

    override suspend fun connect(timeoutMs: Long) {
        _state.value = Gatt.State.CONNECTING
        if (!reachable) {
            _state.value = Gatt.State.DISCONNECTED
            throw GattException("connection failed (status 133)")
        }
        mtu = DEFAULT_MTU
        enabled.clear()
        unanswered.clear()
        unlocked = pincode == null
        _state.value = Gatt.State.READY
        scheduleAutoOff()
    }

    override fun disconnect() {
        _state.value = Gatt.State.DISCONNECTED
    }

    override fun hasService(service: UUID) = when (family) {
        Family.BIFROST -> service == Uuids.SPEAKER_CONTROL_SERVICE || (rpc && service == Uuids.RPC_SERVICE)
        Family.LIGHT -> service == Uuids.LIGHT_CONTROL_SERVICE || (rpc && service == Uuids.RPC_SERVICE)
        Family.LEGACY -> false
    }

    override fun hasCharacteristic(characteristic: UUID) = when (characteristic) {
        txRx.first, txRx.second -> rpc
        Uuids.SYSTEM_POWER, Uuids.LEGACY_POWER_MODE -> true
        else -> value(characteristic) != null
    }

    override suspend fun requestMtu(desired: Int): Int {
        ready()
        mtu = minOf(desired, maxMtu)
        return mtu
    }

    override suspend fun read(characteristic: UUID): ByteArray {
        ready()
        log += "read ${nameOf(characteristic)}"
        if (!unlocked && characteristic != securityChar) throw GattException("read ${nameOf(characteristic)} failed (5)")
        return value(characteristic) ?: throw GattException("characteristic $characteristic missing")
    }

    override suspend fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean) {
        ready()
        if (rpc && characteristic == txRx.first) {
            rpcChunks += value.size
            for (frame in decoder.feed(value)) frame.onSuccess(::onRequest)
            return
        }
        log += "write ${nameOf(characteristic)}"
        if (!hasCharacteristic(characteristic)) throw GattException("characteristic $characteristic missing")
        if (!unlocked) throw GattException("write ${nameOf(characteristic)} failed (5)")
        onWrite(characteristic, value)
    }

    override suspend fun enableNotifications(characteristic: UUID) {
        ready()
        if (!hasCharacteristic(characteristic)) throw GattException("characteristic $characteristic missing")
        enabled += characteristic
    }

    private fun ready() {
        if (_state.value != Gatt.State.READY) throw GattException("not connected")
    }

    private val txRx get() = if (family == Family.LEGACY) Uuids.LEGACY_UART_TX to Uuids.LEGACY_UART_RX else Uuids.RPC_TX to Uuids.RPC_RX
    private val securityChar get() = if (family == Family.LEGACY) Uuids.LEGACY_SECURITY_LEVEL else Uuids.SYSTEM_STATE

    // --- characteristics --------------------------------------------------------------------------

    private fun byte(v: Int?) = v?.let { byteArrayOf(it.toByte()) }
    private fun text(v: String?) = v?.toByteArray(Charsets.UTF_8)

    private fun value(c: UUID): ByteArray? {
        val d = device
        val security = byte((if (pincode != null) 1 else 0) or (if (unlocked) 0 else 2))
        return when (family) {
            Family.LEGACY -> when (c) {
                Uuids.LEGACY_SECURITY_LEVEL -> security
                Uuids.LEGACY_NAME -> text(d.name)
                Uuids.LEGACY_FIRMWARE -> text(d.firmware)
                Uuids.LEGACY_VOLUME -> byte(d.volume)
                Uuids.LEGACY_STEREO_ROLE -> byte(d.stereoRole?.ordinal)
                Uuids.LEGACY_EQ_MODE -> d.eq?.let { eq -> byteArrayOf(eq.ordinal.toByte()) + if (eq == EqMode.USER) EqCurve.toWire(d.eqBands ?: EqCurve.FLAT) else ByteArray(0) }
                Uuids.LEGACY_SOUND_STATUS -> byte(mask(d.activeInputs, SpeakerController::legacyInputs))
                Uuids.LEGACY_SKAA_MODE -> text(d.teamUp?.name?.lowercase())
                else -> null
            }
            else -> when (c) {
                Uuids.SYSTEM_STATE -> security
                Uuids.BATTERY_LEVEL -> byte(d.battery)
                Uuids.TEAMUP_MODE -> byte(d.teamUp?.ordinal)
                Uuids.FIRMWARE_REVISION -> text(d.firmware)
                Uuids.VOLUME -> byte(d.volume.takeIf { family == Family.BIFROST })
                Uuids.STEREO_ROLE -> byte(d.stereoRole?.ordinal.takeIf { family == Family.BIFROST })
                Uuids.SOUND_STATUS -> byte(mask(d.activeInputs, SpeakerController::bifrostInputs).takeIf { family == Family.BIFROST })
                Uuids.LIGHT_BRIGHTNESS -> byte(d.brightness.takeIf { family == Family.LIGHT })
                Uuids.LIGHT_ENERGY_LEVEL -> byte(d.energyLevel?.ordinal.takeIf { family == Family.LIGHT })
                else -> null
            }
        }
    }

    private fun mask(inputs: List<String>, names: (Int) -> List<String>) = (0 until 8).map { 1 shl it }.filter { bit -> names(bit).any { it in inputs } }.sum()

    private fun onWrite(c: UUID, v: ByteArray) {
        val b = v.firstOrNull()?.toInt()?.and(0xFF) ?: return
        val d = device
        device = when (c) {
            Uuids.VOLUME, Uuids.LEGACY_VOLUME -> d.copy(volume = b)
            Uuids.STEREO_ROLE, Uuids.LEGACY_STEREO_ROLE -> d.copy(stereoRole = StereoRole.entries.getOrNull(b))
            Uuids.TEAMUP_MODE -> d.copy(teamUp = TeamUpMode.entries.getOrNull(b))
            Uuids.LEGACY_SKAA_MODE -> d.copy(teamUp = SpeakerController.skaaMode(String(v, Charsets.US_ASCII)))
            Uuids.LEGACY_EQ_MODE -> EqMode.entries.getOrNull(b).let { eq ->
                d.copy(eq = eq, eqBands = if (eq == EqMode.USER && v.size > 1) EqCurve.fromWire(v.copyOfRange(1, v.size)) else d.eqBands)
            }
            Uuids.LIGHT_BRIGHTNESS -> d.copy(brightness = b)
            Uuids.LIGHT_ENERGY_LEVEL -> d.copy(energyLevel = EnergyLevel.entries.getOrNull(b))
            Uuids.SYSTEM_POWER, Uuids.LEGACY_POWER_MODE -> {
                if (b == 0) powerOff(reboot = false)
                d
            }
            else -> d
        }
        if (c == Uuids.SOUND_STATUS || c == Uuids.LEGACY_SOUND_STATUS) scheduleAutoOff()
    }

    // --- RPC --------------------------------------------------------------------------------------

    private fun onRequest(bytes: ByteArray) {
        val request = ProtoMessage.parse(bytes)
        val callId = request.int(FIELD_CALL_ID)
        val name = METHODS[request.int(FIELD_SERVICE_ID) to request.int(FIELD_METHOD_ID)] ?: "?/?"
        log += "call $name"
        val method = name.substringAfter('/')
        val status = failing[method] ?: when {
            !unlocked && method !in UNLOCKING -> STATUS_PERMISSION_DENIED
            else -> 0
        }
        val payload = if (status == 0) answer(method, ProtoMessage.parse(request.bytes(FIELD_PAYLOAD))) else ByteArray(0)
        // Off or rebooting before it could answer.
        if (_state.value != Gatt.State.READY) return
        val response = proto {
            if (payload != null) bytes(FIELD_PAYLOAD, payload)
            if (status != 0 || payload == null) uint32(FIELD_STATUS, if (status != 0) status else STATUS_UNIMPLEMENTED)
            uint32(FIELD_CALL_ID, callId)
        }
        if (method in silent) unanswered += response else reply(response)
    }

    /** Sends [response] back in pieces the MTU allows, as notifications, after the write that asked for it. */
    private fun reply(response: ByteArray) {
        val frame = Hdlc.frame(response)
        val chunk = mtu - 3
        scope.launch {
            if (_state.value != Gatt.State.READY) return@launch
            for (offset in frame.indices step chunk) {
                _notifications.emit(Notification(txRx.second, frame.copyOfRange(offset, minOf(offset + chunk, frame.size))))
            }
        }
    }

    /** The method's answer, or null for one this device doesn't know. */
    private fun answer(method: String, r: ProtoMessage): ByteArray? {
        val d = device
        val ok = ByteArray(0)
        return when (method) {
            "GetProductModel" -> d.model?.let { proto { uint32(1, it.id) } }
            "GetFirmwareVersion" -> d.firmware?.let { proto { string(1, it) } }
            "GetName" -> d.name?.let { proto { string(1, it) } }
            "SetName" -> ok.also { device = d.copy(name = r.string(1)) }
            "GetAutoOff" -> d.autoOffMinutes?.let { proto { uint32(1, it) } }
            "SetAutoOff" -> d.autoOffMinutes?.let { ok.also { device = d.copy(autoOffMinutes = r.int(1)); scheduleAutoOff() } }
            "PowerOff" -> ok.also { powerOff(reboot = r.int(1) == 1) }
            "GetEqMode" -> d.eq?.let { proto { int32(1, it.ordinal) } }
            "SetEqMode" -> d.eq?.let { ok.also { device = d.copy(eq = EqMode.entries.getOrNull(r.int(1))) } }
            "GetEqBands" -> d.eqBands?.let { proto { uint32(1, EqCurve.BANDS); bytes(2, EqCurve.toWire(it)) } }
            "SetEqBands" -> d.eqBands?.let { ok.also { device = d.copy(eqBands = EqCurve.fromWire(r.bytes(2))) } }
            "GetActivePalette" -> if (d.paletteIsDefault || d.palette == null) proto { int32(1, 0) } else LightContentCodec.palette(d.palette, UUID(0, 1))
            "SetActivePalette" -> lightOnly { LightContentCodec.parsePalette(r)?.let { device = d.copy(palette = it, paletteIsDefault = false) } }
            "ResetActiveColorPalette" -> lightOnly { device = d.copy(palette = null, paletteIsDefault = true) }
            "SetAnimation", "SetCustomProgram" -> lightOnly { }
            "AuthenticatePincode" -> authenticate(r.string(1)).takeIf { family != Family.LEGACY }
            "Authenticate" -> authenticate(String(r.bytes(1))).takeIf { family == Family.LEGACY }
            else -> null
        }
    }

    private fun lightOnly(change: () -> Unit): ByteArray? = if (family == Family.LIGHT) ByteArray(0).also { change() } else null

    private fun authenticate(pin: String): ByteArray {
        if (pincode == null || pin == pincode) {
            unlocked = true
            return proto { int32(1, 0) }
        }
        return proto {
            int32(1, 1)
            if (lockoutMs > 0) varint(2, lockoutMs)
        }
    }

    private fun powerOff(reboot: Boolean) {
        drop()
        if (!reboot) return
        reachable = false
        scope.launch {
            delay(REBOOT_MS)
            reachable = true
        }
    }

    /** Like a speaker, it turns itself off once nothing has played for its auto power-off time. */
    private fun scheduleAutoOff() {
        autoOff?.cancel()
        val minutes = device.autoOffMinutes?.takeIf { it > 0 && family == Family.BIFROST } ?: return
        if (device.activeInputs.isNotEmpty() || _state.value != Gatt.State.READY) return
        autoOff = scope.launch {
            delay(minutes * 60_000L)
            if (_state.value == Gatt.State.READY) drop()
        }
    }

    companion object {
        private const val DEFAULT_MTU = 23
        const val REBOOT_MS = 3_000L
        private const val FIELD_SERVICE_ID = 3
        private const val FIELD_METHOD_ID = 4
        private const val FIELD_PAYLOAD = 5
        private const val FIELD_STATUS = 6
        private const val FIELD_CALL_ID = 7
        private const val STATUS_PERMISSION_DENIED = 7
        private const val STATUS_UNIMPLEMENTED = 12
        private val UNLOCKING = setOf("AuthenticatePincode", "Authenticate")

        /** Every method the app calls, by the ids a request carries. */
        private val METHODS: Map<Pair<Int, Int>, String> = listOf(
            "DeviceInformationService/GetProductModel", "DeviceInformationService/GetFirmwareVersion",
            "DeviceInformationService/GetName", "DeviceInformationService/SetName",
            "SystemService/GetAutoOff", "SystemService/SetAutoOff", "SystemService/PowerOff",
            "AudioControlService/GetEqMode", "AudioControlService/SetEqMode", "AudioControlService/GetEqBands",
            "AudioControlService/SetEqBands",
            "LightContentService/GetActivePalette", "LightContentService/SetActivePalette",
            "LightContentService/ResetActiveColorPalette", "LightContentService/SetAnimation",
            "LightContentService/SetCustomProgram",
            "SecurityService/AuthenticatePincode", "LegacySpeakerSecurityService/Authenticate",
        ).associateBy { name ->
            val (service, method) = name.split('/')
            Hdlc.crc32(service.toByteArray(Charsets.US_ASCII)) to Hdlc.crc32(method.toByteArray(Charsets.US_ASCII))
        }

        private val NAMES: Map<UUID, String> = Uuids::class.java.declaredFields
            .filter { it.type == UUID::class.java }
            .associate { f -> f.isAccessible = true; f.get(Uuids) as UUID to f.name }

        /** A characteristic's name in [Uuids], for [log]. */
        fun nameOf(c: UUID) = NAMES[c] ?: c.toString()
    }
}
