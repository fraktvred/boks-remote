package dev.boksremote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
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
            _scanning.value = false
        }
    }

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        _results.value = emptyMap()
        // Filters are OR-ed, so one per service UUID matches any speaker family.
        val filters = Uuids.SCAN_SERVICES.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        _scanning.value = true
    }

    fun stop() {
        if (!_scanning.value) return
        adapter?.bluetoothLeScanner?.stopScan(callback)
        _scanning.value = false
    }

    fun device(address: String) = adapter.getRemoteDevice(address)
}
