package dev.boksremote.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class GattException(message: String) : Exception(message)

data class Notification(val characteristic: UUID, val value: ByteArray)

/**
 * A Bluetooth link to one device: [GattConnection] for a real one, a simulated device for demo mode
 * and tests. Operations run one at a time, each waiting for its answer; one started is waited out
 * even if its caller is cancelled, and the cancellation lands after. Characteristics are looked up
 * by UUID alone, whichever service holds them.
 */
interface Gatt {
    enum class State { DISCONNECTED, CONNECTING, READY }

    val state: StateFlow<State>
    val notifications: SharedFlow<Notification>
    /** What [requestMtu] agreed on; 23 until then. */
    val mtu: Int

    /** Connects and discovers the services; throws if that fails or takes longer than [timeoutMs]. */
    suspend fun connect(timeoutMs: Long = 15_000)
    fun disconnect()
    fun hasService(service: UUID): Boolean
    fun hasCharacteristic(characteristic: UUID): Boolean
    suspend fun requestMtu(desired: Int): Int
    suspend fun read(characteristic: UUID): ByteArray
    suspend fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean = true)
    suspend fun enableNotifications(characteristic: UUID)
}
