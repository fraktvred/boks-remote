package dev.boksremote

import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Family
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.SpeakerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A device the [DeviceList] keeps connected: a speaker or Lightboks, or a dummy, which it never lets go of. */
interface Device {
    val state: StateFlow<SpeakerState>
    val demo: Boolean
    fun connect()
    /** Connects again and waits for the outcome. */
    suspend fun reconnect(): Boolean
    /** Waits for an in-flight connect to finish; true if it ended connected. */
    suspend fun awaitConnection(): Boolean
    fun disconnect()
}

/** What the [DeviceList] finds devices with. */
interface ScanPort {
    val results: StateFlow<Map<String, Advertisement>>
    fun start()
    fun stop()
    /** Drops [address] from [results], so a new scan finds it afresh. */
    fun forget(address: String)
}

/**
 * Why a device is held: after a drop it keeps reconnecting for [retryForMs], then the list lets it go;
 * null keeps it trying for as long as it is held. A [sceneLight] hold keeps it the scene light, whatever
 * else is open.
 */
data class Hold(val retryForMs: Long?, val sceneLight: Boolean = false)

/** A device in the list: what it last advertised, and its connection for the quick settings. */
data class NearbyDevice<D : Device>(val ad: Advertisement, val device: D) {
    val address get() = ad.address
}

/**
 * The device list: what the scan finds nearby, each connected while the app is on screen so the list can
 * show its quick settings, and the open device. In the background only the open device and the held ones
 * stay connected. A listed device that drops gets one try to come back, as from a moment out of range,
 * then leaves the list and a new scan looks for it; a held one keeps trying, backing off, for as long as
 * its [Hold] says; the open one is left to its screen, which offers Reconnect. It scans while it shows.
 *
 * It also picks the scene light, which [onSceneLight] hears of: one held as the scene light, or else the
 * open Lightboks, or else the one there while it is still listed, or else the first listed. A light leaving the list stops being the
 * scene light before it is disconnected, so the scene in use is saved from it while it still answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceList<D : Device>(
    private val scope: CoroutineScope,
    private val scanner: ScanPort,
    private val make: (Advertisement) -> D,
    /** What the DMX bridge and Cycle drive, and how long each keeps trying after a drop. */
    private val held: StateFlow<Map<D, Hold>>,
    private val onSceneLight: (D?) -> Unit,
) {
    private val _nearby = MutableStateFlow<Map<String, NearbyDevice<D>>>(emptyMap())
    /**
     * The devices in the list by address, in first-seen order. One that is connected stays listed when a
     * new scan no longer finds it, since a device may stop advertising once connected.
     */
    val nearby: StateFlow<Map<String, NearbyDevice<D>>> = _nearby
    private val _open = MutableStateFlow<D?>(null)
    val open: StateFlow<D?> = _open
    private val foreground = MutableStateFlow(false)
    private var sceneLight: D? = null
    /** A reconnect under way, after a drop, per device; one at a time. */
    private val retrying = mutableMapOf<D, Job>()

    init {
        // In the foreground the list follows the scan; in the background it lets go of what isn't open or held.
        scope.launch {
            var seen = emptySet<String>()
            combine(scanner.results, foreground, held) { results, shown, _ -> results to shown }.collect { (results, shown) ->
                if (shown) list(results, appeared = results.keys - seen) else release()
                seen = if (shown) results.keys else emptySet()
            }
        }
        // Seen again when the open device closes or a hold changes, so a drop left to the screen gets its try then.
        scope.launch {
            combine(_nearby, _open, held) { nearby, _, _ -> nearby }
                .flatMapLatest { nearby ->
                    nearby.values.map { d -> d.device.state.filter { it.connection == ConnectionState.DISCONNECTED }.map { d } }.merge()
                }
                .collect(::dropped)
        }
        scope.launch { held.collect { chooseSceneLight() } }
        // Scanning slows a device's connection, so only while the list shows.
        scope.launch {
            combine(foreground, _open) { shown, open -> shown && open == null }.distinctUntilChanged().collect { showing ->
                if (showing) scanner.start() else scanner.stop()
            }
        }
    }

    /** The app comes to the foreground, or leaves it. */
    fun setForeground(shown: Boolean) {
        foreground.value = shown
    }

    /** Looks again, say on pull to refresh. */
    fun scan() = scanner.start()

    /** Opens the device [ad] advertises, the listed one or a new one. */
    fun open(ad: Advertisement): D {
        val device = _nearby.value[ad.address]?.device ?: make(ad).also { _nearby.value += ad.address to NearbyDevice(ad, it) }
        open(device)
        return device
    }

    /** Opens [device], which a dummy is never listed. */
    fun open(device: D) {
        scanner.stop()
        close()
        _open.value = device
        chooseSceneLight()
        if (!device.live) device.connect()
    }

    /**
     * Back to the list, which keeps the device connected while the app is in the foreground; a held one
     * stays connected in any case.
     */
    fun close() {
        val closing = _open.value ?: return
        val stays = closing.demo || (foreground.value && _nearby.value.values.any { it.device === closing })
        _open.value = null
        if (stays || closing in held.value) return
        _nearby.value = _nearby.value.filterValues { it.device !== closing }
        chooseSceneLight()
        closing.disconnect()
    }

    /**
     * Brings the list up to date with the scan: what it finds for the first time connects, and one it
     * finds again after failing or dropping tries again; one that is neither found nor connected leaves.
     */
    private fun list(results: Map<String, Advertisement>, appeared: Set<String>) {
        val open = _open.value
        val next = LinkedHashMap(_nearby.value.filter { (address, d) -> address in results || d.device === open || d.device in held.value || d.device.live })
        for ((address, ad) in results) {
            val d = next[address]
            if (d == null) {
                next[address] = NearbyDevice(ad, make(ad).also { it.connect() })
            } else {
                next[address] = d.copy(ad = ad)
                if (address in appeared && !d.device.live) d.device.connect()
            }
        }
        val gone = _nearby.value.values.filter { it.address !in next }
        _nearby.value = next
        chooseSceneLight()
        gone.forEach { it.device.disconnect() }
    }

    /** In the background only the open device and the held ones stay connected. */
    private fun release() {
        fun kept(d: NearbyDevice<D>) = d.device === _open.value || d.device in held.value
        val gone = _nearby.value.values.filterNot(::kept)
        if (gone.isEmpty()) return
        _nearby.value = _nearby.value.filterValues(::kept)
        chooseSceneLight()
        gone.forEach { it.device.disconnect() }
    }

    /** [d] dropped: a held one keeps trying, the open one is left to its screen, others try once. */
    private fun dropped(d: NearbyDevice<D>) {
        val device = d.device
        // Let go of already, or trying now.
        if (_nearby.value[d.address]?.device !== device || retrying[device]?.isActive == true) return
        val hold = held.value[device]
        if (hold == null && device === _open.value) return
        retrying[device] = scope.launch {
            val back = if (hold != null) keepConnected(device, hold) else device.reconnect()
            retrying.remove(device)
            if (!back && device !== _open.value && (hold != null || device !in held.value)) lose(d)
        }
    }

    /**
     * Reconnects a held device, backing off between tries, until it is back, no longer held, or its
     * [Hold] runs out; false only then.
     */
    private suspend fun keepConnected(device: D, hold: Hold): Boolean {
        suspend fun retry() {
            var backoff = RECONNECT_MIN_MS
            while (device.state.value.connection != ConnectionState.CONNECTED && device in held.value) {
                // A connect already under way (the user tapped Reconnect) is awaited, not doubled.
                val ok = if (device.state.value.connection == ConnectionState.CONNECTING) device.awaitConnection() else device.reconnect()
                if (ok) return
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
        }
        val limit = hold.retryForMs
        if (limit == null) {
            retry()
            return true
        }
        return withTimeoutOrNull(limit) { retry() } != null
    }

    /**
     * [d] has gone: off, or out of range. It leaves the list and the scan's results, and a new scan looks
     * for it; one still there, or back in range meanwhile, comes back.
     */
    private fun lose(d: NearbyDevice<D>) {
        if (_nearby.value[d.address]?.device !== d.device) return
        _nearby.value -= d.address
        scanner.forget(d.address)
        chooseSceneLight()
        d.device.disconnect()
        if (foreground.value && _open.value == null) scanner.start()
    }

    /**
     * One held as the scene light, or else the open Lightboks, or else the scene light there while it is
     * still listed, or else the first listed.
     */
    private fun chooseSceneLight() {
        val open = _open.value?.takeUnless { it.demo }
        val listed = _nearby.value.values.map { it.device }
        val current = sceneLight
        val kept = held.value.entries.firstOrNull { (d, hold) -> hold.sceneLight && (d === open || d in listed) }?.key
        val next = when {
            kept != null -> kept
            open != null && open.light -> open
            current != null && (current === open || current in listed) -> current
            else -> listed.firstOrNull { it.light }
        }
        if (next === current) return
        sceneLight = next
        onSceneLight(next)
    }

    private val Device.light get() = state.value.family == Family.LIGHT

    /** Connected, or on the way there. */
    private val Device.live get() = state.value.connection.let { it == ConnectionState.CONNECTED || it == ConnectionState.CONNECTING }

    companion object {
        const val RECONNECT_MIN_MS = 2_000L
        const val RECONNECT_MAX_MS = 15_000L
    }
}
