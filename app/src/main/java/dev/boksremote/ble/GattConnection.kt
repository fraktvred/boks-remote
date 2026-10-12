package dev.boksremote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattException(message: String) : Exception(message)

data class Notification(val characteristic: UUID, val value: ByteArray)

/**
 * Coroutine wrapper over [BluetoothGatt]. Android drops a GATT operation issued while
 * another is in flight, so every operation runs under [opLock] and waits for its own
 * callback before the next one starts.
 */
@SuppressLint("MissingPermission") // callers obtain BLUETOOTH_CONNECT before constructing this
class GattConnection(private val context: Context, private val device: BluetoothDevice) {

    enum class State { DISCONNECTED, CONNECTING, READY }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _notifications = MutableSharedFlow<Notification>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<Notification> = _notifications

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var pending: CompletableDeferred<Any?>? = null
    private var connectResult: CompletableDeferred<Unit>? = null

    var mtu: Int = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Connected with an error status: fail now rather than wait out the connect timeout.
                connectResult?.completeExceptionally(GattException("connection failed (status $status)"))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _state.value = State.DISCONNECTED
                connectResult?.completeExceptionally(GattException("connection failed (status $status)"))
                pending?.completeExceptionally(GattException("disconnected"))
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) connectResult?.complete(Unit)
            else connectResult?.completeExceptionally(GattException("service discovery failed ($status)"))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@GattConnection.mtu = mtu
            pending?.complete(mtu)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(value)
            else pending?.completeExceptionally(GattException("read ${c.uuid} failed ($status)"))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
            else pending?.completeExceptionally(GattException("write ${c.uuid} failed ($status)"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
            else pending?.completeExceptionally(GattException("descriptor write failed ($status)"))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(Notification(c.uuid, value))
        }
    }

    suspend fun connect(timeoutMs: Long = 15_000) {
        // A stale handle from an earlier session would otherwise stay registered with the stack.
        gatt?.let { it.disconnect(); it.close() }
        _state.value = State.CONNECTING
        val result = CompletableDeferred<Unit>()
        connectResult = result
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMs) { result.await() }
        } catch (e: Exception) {
            disconnect()
            throw e
        }
        _state.value = State.READY
    }

    fun disconnect() {
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        _state.value = State.DISCONNECTED
    }

    fun hasService(service: UUID) = gatt?.getService(service) != null
    fun hasCharacteristic(characteristic: UUID) = find(characteristic) != null

    suspend fun requestMtu(desired: Int): Int = op { gatt().requestMtu(desired) } as Int

    suspend fun read(characteristic: UUID): ByteArray {
        val c = require(characteristic)
        return op { gatt().readCharacteristic(c) } as ByteArray
    }

    suspend fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean = true) {
        val c = require(characteristic)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op { gatt().writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS }
    }

    suspend fun enableNotifications(characteristic: UUID) {
        val c = require(characteristic)
        val g = gatt()
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op { g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS }
    }

    private fun gatt() = gatt ?: throw GattException("not connected")

    // Characteristic UUIDs are unique across the speaker's services, and the legacy UART pair
    // lives under a service whose UUID differs between firmware lines, so look up by UUID alone.
    private fun find(characteristic: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { it.getCharacteristic(characteristic) }

    private fun require(characteristic: UUID) =
        find(characteristic) ?: throw GattException("characteristic $characteristic missing")

    /** Runs one GATT operation; [start] returns false if Android refused to queue it. */
    private suspend fun op(timeoutMs: Long = 5_000, start: () -> Boolean): Any? = opLock.withLock {
        val d = CompletableDeferred<Any?>()
        pending = d
        if (!start()) {
            pending = null
            throw GattException("operation rejected by stack")
        }
        try {
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pending = null
        }
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
