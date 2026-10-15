package dev.boksremote.dmx

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dev.boksremote.speaker.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

enum class DmxProtocol(val label: String, val universes: IntRange) {
    ARTNET("Art-Net", 0..32767), // 15-bit Port-Address
    SACN("sACN", 1..63999); // E1.31 data universes

    val firstUniverse get() = universes.first
}

data class DmxConfig(
    val protocol: DmxProtocol = DmxProtocol.ARTNET,
    val universe: Int = DmxProtocol.ARTNET.firstUniverse,
    val startAddress: Int = 1,
    val personality: LightboksPersonality = LightboksPersonality.FULL,
)

data class DmxStatus(
    val running: Boolean = false,
    val packetsPerSecond: Int = 0,
    val source: String? = null,
    val look: LightLook? = null,
    /** Where a desk can send to; empty means the phone is on no LAN (Wi-Fi, hotspot or tether). */
    val addresses: List<String> = emptyList(),
    val reconnecting: Boolean = false,
    /** Stays until the next start or re-bind: the socket couldn't be opened (e.g. another app holds the port). */
    val networkError: String? = null,
    /** Cleared by the next successful write to the light. */
    val lightError: String? = null,
)

/**
 * Receives one DMX universe over the LAN and drives a Lightboks, lent by its [Stage.Lease], through a
 * [LookSender]. It answers a desk's ArtPoll so the phone shows up in its node list.
 */
class DmxBridge(private val context: Context, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow(DmxStatus())
    val status: StateFlow<DmxStatus> = _status

    @Volatile private var config = DmxConfig()
    @Volatile private var lastFrame: DmxFrame? = null
    /** Changes whenever the socket must be re-opened: protocol, or the sACN multicast group. */
    private val binding = MutableStateFlow(Binding(DmxProtocol.ARTNET, null))
    private var session: Job? = null
    @Volatile private var lease: Stage.Lease? = null
    @Volatile private var sender: LookSender? = null
    private val packetCount = AtomicInteger()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private data class Binding(val protocol: DmxProtocol, val sacnUniverse: Int?)

    fun start(config: DmxConfig, lease: Stage.Lease) {
        stop()
        val sender = LookSender(lease.output, lease.light.state)
        this.lease = lease
        this.sender = sender
        this.config = config
        binding.value = bindingFor(config)
        lastFrame = null
        _status.value = DmxStatus(running = true, addresses = LocalNetwork.addresses().map { it.text })
        acquireWifiLocks()
        session = scope.launch {
            // A protocol or sACN-universe change re-opens the socket. Not collectLatest: it starts each block
            // undispatched, and receive() blocks without ever suspending, so the next value was never collected.
            launch {
                var receiver: Job? = null
                binding.collect { b ->
                    receiver?.cancelAndJoin() // its socket closes within RECEIVE_TIMEOUT_MS
                    receiver = launch(Dispatchers.IO) { receive(b) }
                }
            }
            launch { sender.run() }
            launch { sender.sending.collect { s -> _status.update { it.copy(reconnecting = s.reconnecting, lightError = s.error) } } }
            launch { measureRate() }
        }
    }

    /** Applies a patch change without dropping the link; only re-binds the socket when it has to. */
    fun reconfigure(new: DmxConfig) {
        config = new
        binding.value = bindingFor(new)
        // Re-map the last frame now, so an address or personality change shows without waiting for the desk.
        lastFrame?.takeIf { it.universe == new.universe }?.let { onLook(new.personality.map(it, new.startAddress)) }
    }

    fun stop() {
        session?.cancel()
        session = null
        // An effect left running is stopped as the lease ends.
        lease = null
        sender = null
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        _status.update { it.copy(running = false, packetsPerSecond = 0, reconnecting = false) }
    }

    private fun bindingFor(c: DmxConfig) = Binding(c.protocol, c.universe.takeIf { c.protocol == DmxProtocol.SACN })

    // --- network side ------------------------------------------------------------------------

    private suspend fun receive(b: Binding) {
        val socket = try {
            open(b)
        } catch (e: Exception) {
            Log.w(TAG, "DMX socket failed", e)
            _status.update { it.copy(networkError = "Can't listen for ${b.protocol.label}: ${e.message}") }
            return
        }
        _status.update { it.copy(networkError = null) }
        Log.d(TAG, "listening: $b")
        val joined = mutableSetOf<String>()
        var interfacesCheckedAt = 0L
        try {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            while (true) {
                // The blocking receive can't be cancelled, so it times out regularly to check for cancellation.
                currentCoroutineContext().ensureActive()
                val now = System.currentTimeMillis()
                if (now - interfacesCheckedAt >= INTERFACE_CHECK_MS) {
                    interfacesCheckedAt = now
                    refreshInterfaces(socket, b, joined)
                }
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                onPacket(socket, b.protocol, buf, packet)
            }
        } finally {
            socket.close()
            Log.d(TAG, "closed: $b")
        }
    }

    private fun open(b: Binding): DatagramSocket = when (b.protocol) {
        DmxProtocol.ARTNET -> DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(DmxPackets.ARTNET_PORT))
        }
        DmxProtocol.SACN -> MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(DmxPackets.SACN_PORT))
        }
    }.apply { soTimeout = RECEIVE_TIMEOUT_MS }

    /**
     * Publishes the current LAN addresses and, for sACN, joins the universe's multicast group on any
     * interface that appeared since the last check (Wi-Fi reconnect, hotspot turned on). Unicast sACN
     * needs no group and arrives regardless.
     */
    private fun refreshInterfaces(socket: DatagramSocket, b: Binding, joined: MutableSet<String>) {
        val addresses = LocalNetwork.addresses()
        val texts = addresses.map { it.text }
        if (texts != _status.value.addresses) _status.update { it.copy(addresses = texts) }
        if (socket !is MulticastSocket || b.sacnUniverse == null) return
        val current = addresses.map { it.interfaceName }.toSet()
        joined.retainAll(current) // a vanished interface took its membership with it
        val group = InetSocketAddress(InetAddress.getByName(DmxPackets.sacnGroup(b.sacnUniverse)), DmxPackets.SACN_PORT)
        for (name in current - joined) {
            try {
                socket.joinGroup(group, NetworkInterface.getByName(name))
                joined += name
                Log.d(TAG, "joined ${group.address} on $name")
            } catch (e: Exception) {
                Log.d(TAG, "join ${group.address} on $name: ${e.message}")
            }
        }
    }

    private fun onPacket(socket: DatagramSocket, protocol: DmxProtocol, buf: ByteArray, packet: DatagramPacket) {
        val c = config
        val frame = when (protocol) {
            DmxProtocol.ARTNET -> {
                if (DmxPackets.isArtPoll(buf, packet.length)) {
                    replyToPoll(socket, packet.address, c)
                    return
                }
                DmxPackets.parseArtNet(buf, packet.length)
            }
            DmxProtocol.SACN -> DmxPackets.parseSacn(buf, packet.length)
        } ?: return
        if (frame.universe != c.universe) return
        packetCount.incrementAndGet()
        lastFrame = frame
        val source = packet.address?.hostAddress
        if (_status.value.source != source) _status.update { it.copy(source = source) }
        onLook(c.personality.map(frame, c.startAddress))
    }

    private fun onLook(look: LightLook) {
        sender?.want(look)
        if (_status.value.look != look) _status.update { it.copy(look = look) }
    }

    /** Answers a desk's ArtPoll so the phone shows up in its node list, patched on our universe. */
    private fun replyToPoll(socket: DatagramSocket, from: InetAddress?, c: DmxConfig) {
        val peer = from as? Inet4Address ?: return
        val local = LocalNetwork.facing(peer, LocalNetwork.addresses()) ?: return
        if (local.ip == peer) return // our own broadcast poll, if any
        val lightName = lease?.light?.state?.value?.name ?: "Lightboks"
        val s = _status.value
        val report = when {
            s.reconnecting -> "Reconnecting to $lightName"
            s.packetsPerSecond > 0 -> "Bridging to $lightName"
            else -> "Waiting for DMX"
        }
        val reply = DmxPackets.artPollReply(
            ip = local.ip.address,
            universe = c.universe,
            shortName = "Boks Remote",
            longName = "Boks Remote - $lightName, ${c.personality.footprint} ch at ${c.startAddress}",
            report = "#0001 [0000] $report",
            outputting = s.packetsPerSecond > 0,
        )
        try {
            socket.send(DatagramPacket(reply, reply.size, InetSocketAddress(peer, DmxPackets.ARTNET_PORT)))
        } catch (e: Exception) {
            Log.d(TAG, "ArtPollReply to $peer: ${e.message}")
        }
    }

    private suspend fun measureRate() {
        while (currentCoroutineContext().isActive) {
            delay(1000)
            val rate = packetCount.getAndSet(0)
            if (rate != _status.value.packetsPerSecond) _status.update { it.copy(packetsPerSecond = rate) }
        }
    }

    private fun acquireWifiLocks() {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        // Android filters multicast and broadcast packets unless an app holds this lock.
        multicastLock = wifi.createMulticastLock("boks-remote-dmx").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION") // still the only way to stop Wi-Fi power save delaying packets
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "boks-remote-dmx").apply { setReferenceCounted(false); acquire() }
    }

    companion object {
        private const val TAG = "DmxBridge"
        private const val RECEIVE_TIMEOUT_MS = 250
        private const val INTERFACE_CHECK_MS = 3_000L
    }
}
