package dev.boksremote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Uuids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

@SuppressLint("MissingPermission") // the UI starts scanning only after BLUETOOTH_SCAN is granted
class Scanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    private val _results = MutableStateFlow<Map<String, Advertisement>>(emptyMap())
    val results: StateFlow<Map<String, Advertisement>> = _results

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stop() }
    private val begin = Runnable { begin() }
    /** Whether startScan is in effect; [scanning] is also true while a start waits out the rate limit. */
    private var started = false
    /** When recent scans started (elapsed realtime, oldest first), to stay under Android's start limit. */
    private val starts = ArrayDeque<Long>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val mfr = record?.manufacturerSpecificData?.let { if (it.size() > 0) it.valueAt(0) else null }
            val ad = Advertisement.parse(
                address = result.device.address,
                name = record?.deviceName ?: result.device.name,
                rssi = result.rssi,
                serviceUuids = record?.serviceUuids?.map { it.uuid }.orEmpty(),
                manufacturerData = mfr,
            ) ?: return
            _results.update { it + (ad.address to ad) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.removeCallbacks(timeout)
            started = false
            _scanning.value = false
        }
    }

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /**
     * Starts a scan with an empty list. While one is already running it is restarted in place: the
     * list clears and the time limit begins again, without another startScan. Android silently
     * ignores an app's sixth start within 30 s, which would end in "nothing found", so a start
     * that would exceed [MAX_STARTS] waits until the oldest one leaves the window.
     */
    fun start() {
        if (adapter?.bluetoothLeScanner == null) return
        _results.value = emptyMap()
        if (_scanning.value) {
            if (started) {
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, SCAN_MS)
            }
            return
        }
        _scanning.value = true
        val now = SystemClock.elapsedRealtime()
        while (starts.isNotEmpty() && now - starts.first() >= WINDOW_MS) starts.removeFirst()
        val wait = if (starts.size < MAX_STARTS) 0L else starts.first() + WINDOW_MS - now
        handler.postDelayed(begin, wait)
    }

    private fun begin() {
        val scanner = adapter?.bluetoothLeScanner ?: run { _scanning.value = false; return }
        // Filters are OR-ed, so one per service UUID matches any speaker family.
        val filters = Uuids.SCAN_SERVICES.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        starts.addLast(SystemClock.elapsedRealtime())
        scanner.startScan(filters, settings, callback)
        started = true
        handler.postDelayed(timeout, SCAN_MS)
    }

    fun stop() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(begin)
        if (!_scanning.value) return
        if (started) adapter?.bluetoothLeScanner?.stopScan(callback)
        started = false
        _scanning.value = false
    }

    fun device(address: String) = adapter.getRemoteDevice(address)

    private companion object {
        /** Devices that are on show up within a few seconds; after this a low-latency scan only costs battery. */
        const val SCAN_MS = 20_000L
        /** Android allows 5 starts per 30 s; one fewer keeps a margin. */
        const val MAX_STARTS = 4
        const val WINDOW_MS = 30_000L
    }
}
