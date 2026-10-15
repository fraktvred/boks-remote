package dev.boksremote.speaker

import dev.boksremote.proto.ProtoMessage
import dev.boksremote.proto.proto
import dev.boksremote.protocol.Family
import dev.boksremote.protocol.Uuids
import dev.boksremote.protocol.versionAtLeast
import java.util.UUID

/** What a device can do beyond its basic controls, for the screens to show it or not. */
enum class Feature {
    RENAME,
    RESTART,
    /** Scenes, palettes, Cycle, Sleep fade, effects and the DMX bridge. */
    LIGHT_CONTENT,
    EQ,
}

/** What a [FamilyProtocol] reads, writes and calls with: the [SpeakerController]'s link. */
internal interface Wire {
    val rpc: Boolean
    fun has(c: UUID): Boolean
    /** Null when the device lacks [c] or the read fails. */
    suspend fun read(c: UUID): ByteArray?
    suspend fun write(c: UUID, value: ByteArray)
    /**
     * Checks that RPC answers, with a short timeout; a silent channel is dropped, and [rpc] goes false,
     * rather than stall every later call.
     */
    suspend fun probeRpc()
    /** Throws without RPC, or when the call fails. */
    suspend fun call(service: String, method: String, request: ByteArray = ByteArray(0)): ProtoMessage
    /** Runs an optional step, which older firmware may lack: a failure is logged and passed over. */
    suspend fun optional(block: suspend () -> Unit)
    fun update(change: (SpeakerState) -> SpeakerState)
}

private suspend fun Wire.byte(c: UUID): Int? = read(c)?.firstOrNull()?.toInt()?.and(0xFF)
private suspend fun Wire.text(c: UUID): String? = read(c)?.let { String(it, Charsets.UTF_8).trim('\u0000', ' ').ifBlank { null } }

/**
 * How one family of devices is spoken to: which characteristics hold what, how its state is read and
 * followed, and how its controls are written. The [SpeakerController] keeps the connection, the
 * order of unlocking and reading, and [SpeakerState]; this keeps the wire.
 */
internal sealed class FamilyProtocol {
    /** The characteristic pair RPC runs on, (write, notify); null where the firmware has none. */
    abstract fun rpcPair(firmware: String?): Pair<UUID, UUID>?
    /** Where the pincode and lock flags are read. */
    abstract val security: UUID
    abstract val mtu: Int
    abstract fun features(wire: Wire): Set<Feature>
    /** Everything there is to read once unlocked. */
    abstract suspend fun readState(wire: Wire)
    abstract val subscriptions: List<UUID>
    abstract fun decode(s: SpeakerState, c: UUID, v: ByteArray): SpeakerState
    abstract suspend fun setVolume(wire: Wire, v: Int)
    abstract suspend fun setStereoRole(wire: Wire, role: StereoRole)
    abstract suspend fun setTeamUp(wire: Wire, mode: TeamUpMode)
    abstract suspend fun setEqMode(wire: Wire, mode: EqMode)
    abstract suspend fun setCustomEq(wire: Wire, bandsDb: List<Int>)
    /** The firmware's verdict on [pin]: 0 in field 1 if it took it, else any lockout in ms in field 2. */
    abstract suspend fun authenticate(wire: Wire, pin: String): ProtoMessage
    /** Power off for a device without RPC, which takes it as a characteristic write. */
    abstract suspend fun powerOff(wire: Wire)

    companion object {
        fun of(family: Family): FamilyProtocol = when (family) {
            Family.LEGACY -> Legacy
            Family.BIFROST -> Bifrost
            Family.LIGHT -> Light
        }
    }

    /** SOUNDBOKS Gen 3, Gen 4 and Go: a characteristic per setting, and RPC on the UART pair from firmware 3. */
    object Legacy : FamilyProtocol() {
        // Pre-3.0 firmware speaks an ASCII serial protocol on the same pair instead of RPC.
        override fun rpcPair(firmware: String?) =
            (Uuids.LEGACY_UART_TX to Uuids.LEGACY_UART_RX).takeIf { firmware == null || versionAtLeast(firmware, 3) }
        override val security: UUID = Uuids.LEGACY_SECURITY_LEVEL
        override val mtu = 96

        override fun features(wire: Wire) = buildSet {
            if (wire.rpc) { add(Feature.RENAME); add(Feature.RESTART) }
            if (wire.has(Uuids.LEGACY_EQ_MODE)) add(Feature.EQ)
        }

        override suspend fun readState(wire: Wire) {
            wire.text(Uuids.LEGACY_NAME)?.let { s -> wire.update { it.copy(name = s) } }
            wire.text(Uuids.LEGACY_FIRMWARE)?.let { s -> wire.update { it.copy(firmware = s) } }
            wire.byte(Uuids.LEGACY_VOLUME)?.let { b -> wire.update { it.copy(volume = b) } }
            wire.byte(Uuids.LEGACY_STEREO_ROLE)?.let { b -> wire.update { it.copy(stereoRole = StereoRole.entries.getOrNull(b)) } }
            // Mode byte, followed by the bands when the mode is user-defined.
            wire.read(Uuids.LEGACY_EQ_MODE)?.let { raw ->
                val mode = raw.firstOrNull()?.toInt()?.and(0xFF)?.let { EqMode.entries.getOrNull(it) }
                val bands = if (mode == EqMode.USER) EqCurve.fromWire(raw.copyOfRange(1, raw.size)) else null
                wire.update { it.copy(eq = mode, eqBands = bands) }
            }
            wire.byte(Uuids.LEGACY_SOUND_STATUS)?.let { b -> wire.update { it.copy(activeInputs = SpeakerController.legacyInputs(b)) } }
            wire.text(Uuids.LEGACY_SKAA_MODE)?.let { s -> wire.update { it.copy(teamUp = SpeakerController.skaaMode(s)) } }
        }

        override val subscriptions = listOf(Uuids.LEGACY_VOLUME, Uuids.LEGACY_STEREO_ROLE, Uuids.LEGACY_SOUND_STATUS, Uuids.LEGACY_SKAA_MODE, Uuids.LEGACY_POWER_MODE)

        override fun decode(s: SpeakerState, c: UUID, v: ByteArray): SpeakerState {
            val b = v[0].toInt() and 0xFF
            return when (c) {
                Uuids.LEGACY_VOLUME -> s.copy(volume = b)
                Uuids.LEGACY_STEREO_ROLE -> s.copy(stereoRole = StereoRole.entries.getOrNull(b))
                Uuids.LEGACY_SOUND_STATUS -> s.copy(activeInputs = SpeakerController.legacyInputs(b))
                Uuids.LEGACY_SKAA_MODE -> s.copy(teamUp = SpeakerController.skaaMode(String(v, Charsets.US_ASCII)))
                Uuids.LEGACY_POWER_MODE -> if (b == 0) s.copy(message = "Speaker is powering off") else s
                else -> s
            }
        }

        override suspend fun setVolume(wire: Wire, v: Int) = wire.write(Uuids.LEGACY_VOLUME, byteArrayOf(v.toByte()))
        override suspend fun setStereoRole(wire: Wire, role: StereoRole) = wire.write(Uuids.LEGACY_STEREO_ROLE, byteArrayOf(role.ordinal.toByte()))
        override suspend fun setTeamUp(wire: Wire, mode: TeamUpMode) = wire.write(Uuids.LEGACY_SKAA_MODE, mode.name.lowercase().toByteArray(Charsets.US_ASCII))
        override suspend fun setEqMode(wire: Wire, mode: EqMode) = wire.write(Uuids.LEGACY_EQ_MODE, byteArrayOf(mode.ordinal.toByte()))
        override suspend fun setCustomEq(wire: Wire, bandsDb: List<Int>) =
            wire.write(Uuids.LEGACY_EQ_MODE, byteArrayOf(EqMode.USER.ordinal.toByte()) + EqCurve.toWire(bandsDb))
        override suspend fun authenticate(wire: Wire, pin: String) =
            wire.call("LegacySpeakerSecurityService", "Authenticate", proto { bytes(1, pin.toByteArray()) })
        override suspend fun powerOff(wire: Wire) = wire.write(Uuids.LEGACY_POWER_MODE, byteArrayOf(0))
    }

    /** Bifrost speakers and the Lightboks: settings on their own service, most of the rest over RPC. */
    sealed class Modern : FamilyProtocol() {
        override fun rpcPair(firmware: String?) = Uuids.RPC_TX to Uuids.RPC_RX
        override val security: UUID = Uuids.SYSTEM_STATE

        override suspend fun readState(wire: Wire) {
            wire.byte(Uuids.BATTERY_LEVEL)?.let { b -> wire.update { it.copy(battery = b.coerceIn(0, 100)) } }
            wire.byte(Uuids.TEAMUP_MODE)?.let { b -> wire.update { it.copy(teamUp = TeamUpMode.entries.getOrNull(b)) } }
            readOwn(wire)
            if (wire.rpc) wire.probeRpc()
            if (wire.rpc) readRpcInfo(wire) else wire.text(Uuids.FIRMWARE_REVISION)?.let { s -> wire.update { it.copy(firmware = s) } }
        }

        /** The characteristics of its own service. */
        protected abstract suspend fun readOwn(wire: Wire)

        override fun decode(s: SpeakerState, c: UUID, v: ByteArray): SpeakerState {
            val b = v[0].toInt() and 0xFF
            return when (c) {
                Uuids.BATTERY_LEVEL -> s.copy(battery = b.coerceIn(0, 100))
                Uuids.TEAMUP_MODE -> s.copy(teamUp = TeamUpMode.entries.getOrNull(b))
                else -> decodeOwn(s, c, b)
            }
        }

        protected abstract fun decodeOwn(s: SpeakerState, c: UUID, b: Int): SpeakerState

        override suspend fun setVolume(wire: Wire, v: Int) = wire.write(Uuids.VOLUME, byteArrayOf(v.toByte()))
        override suspend fun setStereoRole(wire: Wire, role: StereoRole) = wire.write(Uuids.STEREO_ROLE, byteArrayOf(role.ordinal.toByte()))
        override suspend fun setTeamUp(wire: Wire, mode: TeamUpMode) = wire.write(Uuids.TEAMUP_MODE, byteArrayOf(mode.ordinal.toByte()))

        override suspend fun setEqMode(wire: Wire, mode: EqMode) {
            wire.call("AudioControlService", "SetEqMode", proto { int32(1, mode.ordinal) })
        }

        override suspend fun setCustomEq(wire: Wire, bandsDb: List<Int>) {
            // Same order as the official app: switch to the user curve, then load its bands.
            wire.call("AudioControlService", "SetEqMode", proto { int32(1, EqMode.USER.ordinal) })
            wire.call("AudioControlService", "SetEqBands", proto { uint32(1, EqCurve.BANDS); bytes(2, EqCurve.toWire(bandsDb)) })
        }

        override suspend fun authenticate(wire: Wire, pin: String) =
            wire.call("SecurityService", "AuthenticatePincode", proto { string(1, pin) })

        override suspend fun powerOff(wire: Wire) = wire.write(Uuids.SYSTEM_POWER, byteArrayOf(0, 0)) // [OFF, no reboot]
    }

    object Bifrost : Modern() {
        override val mtu = 500

        override fun features(wire: Wire) =
            if (wire.rpc) setOf(Feature.RENAME, Feature.RESTART, Feature.EQ) else emptySet()

        override suspend fun readOwn(wire: Wire) {
            wire.byte(Uuids.VOLUME)?.let { b -> wire.update { it.copy(volume = b) } }
            wire.byte(Uuids.STEREO_ROLE)?.let { b -> wire.update { it.copy(stereoRole = StereoRole.entries.getOrNull(b)) } }
            wire.byte(Uuids.SOUND_STATUS)?.let { b -> wire.update { it.copy(activeInputs = SpeakerController.bifrostInputs(b)) } }
        }

        override suspend fun readState(wire: Wire) {
            super.readState(wire)
            if (!wire.rpc) return
            wire.optional { wire.call("AudioControlService", "GetEqMode").int(1).let { e -> wire.update { it.copy(eq = EqMode.entries.getOrNull(e)) } } }
            wire.optional {
                val bands = wire.call("AudioControlService", "GetEqBands").bytes(2)
                wire.update { it.copy(eqBands = EqCurve.fromWire(bands)) }
            }
        }

        override val subscriptions = listOf(Uuids.VOLUME, Uuids.STEREO_ROLE, Uuids.SOUND_STATUS, Uuids.BATTERY_LEVEL, Uuids.TEAMUP_MODE)

        override fun decodeOwn(s: SpeakerState, c: UUID, b: Int) = when (c) {
            Uuids.VOLUME -> s.copy(volume = b)
            Uuids.STEREO_ROLE -> s.copy(stereoRole = StereoRole.entries.getOrNull(b))
            Uuids.SOUND_STATUS -> s.copy(activeInputs = SpeakerController.bifrostInputs(b))
            else -> s
        }
    }

    object Light : Modern() {
        override val mtu = 247

        override fun features(wire: Wire) =
            if (wire.rpc) setOf(Feature.RENAME, Feature.RESTART, Feature.LIGHT_CONTENT) else emptySet()

        override suspend fun readOwn(wire: Wire) {
            wire.byte(Uuids.LIGHT_BRIGHTNESS)?.let { b -> wire.update { it.copy(brightness = b) } }
            wire.byte(Uuids.LIGHT_ENERGY_LEVEL)?.let { b -> wire.update { it.copy(energyLevel = EnergyLevel.entries.getOrNull(b)) } }
        }

        override suspend fun readState(wire: Wire) {
            super.readState(wire)
            if (!wire.rpc) return
            wire.optional {
                val p = LightContentCodec.parsePalette(wire.call("LightContentService", "GetActivePalette"))
                wire.update { it.copy(palette = p, paletteIsDefault = p == null) }
            }
        }

        override val subscriptions = listOf(Uuids.LIGHT_BRIGHTNESS, Uuids.LIGHT_ENERGY_LEVEL, Uuids.BATTERY_LEVEL, Uuids.TEAMUP_MODE)

        override fun decodeOwn(s: SpeakerState, c: UUID, b: Int) = when (c) {
            Uuids.LIGHT_BRIGHTNESS -> s.copy(brightness = b)
            Uuids.LIGHT_ENERGY_LEVEL -> s.copy(energyLevel = EnergyLevel.entries.getOrNull(b))
            else -> s
        }
    }
}

/** Device information and settings that only exist as RPCs. Each is optional: older firmware may lack a method. */
private suspend fun readRpcInfo(wire: Wire) {
    wire.optional { wire.call("DeviceInformationService", "GetFirmwareVersion").string(1).let { v -> wire.update { it.copy(firmware = v) } } }
    wire.optional { wire.call("DeviceInformationService", "GetName").string(1).takeIf { it.isNotBlank() }?.let { n -> wire.update { it.copy(name = n) } } }
    wire.optional { wire.call("SystemService", "GetAutoOff").int(1).let { m -> wire.update { it.copy(autoOffMinutes = m) } } }
}
