package dev.boksremote.protocol

import dev.boksremote.ble.Gatt
import dev.boksremote.proto.ProtoMessage
import dev.boksremote.proto.ProtoWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

class RpcException(message: String, val status: Int = -1) : Exception(message)

/**
 * Unary RPC over an HDLC-framed characteristic pair. The firmware answers
 * one call at a time, so calls are serialized and matched to responses by call_id.
 */
class RpcClient(
    private val gatt: Gatt,
    private val txCharacteristic: UUID,
    private val rxCharacteristic: UUID,
    scope: CoroutineScope,
) {
    private val callLock = Mutex()
    private val decoder = Hdlc.Decoder()
    private var nextCallId = 1
    private var pending: CompletableDeferred<ProtoMessage>? = null
    /** The call [pending] waits for; a late answer to a call given up on carries another id and is dropped. */
    private var pendingId = 0
    private val receiver: Job

    init {
        receiver = scope.launch {
            gatt.notifications.collect { n ->
                if (n.characteristic != rxCharacteristic) return@collect
                for (frame in decoder.feed(n.value)) {
                    val waiter = pending ?: continue
                    frame.fold(
                        onSuccess = { bytes ->
                            runCatching { ProtoMessage.parse(bytes) }.fold(
                                { packet -> if (packet.int(FIELD_CALL_ID) == pendingId) waiter.complete(packet) },
                                waiter::completeExceptionally,
                            )
                        },
                        onFailure = { waiter.completeExceptionally(RpcException("bad frame: ${it.message}")) },
                    )
                }
            }
        }
    }

    suspend fun start() = gatt.enableNotifications(rxCharacteristic)

    fun close() = receiver.cancel()

    /** Calls `service/method` with an encoded request; returns the decoded response message. */
    suspend fun call(service: String, method: String, request: ByteArray = ByteArray(0), timeoutMs: Long = 10_000): ProtoMessage =
        callLock.withLock {
            val callId = nextCallId++
            val response = CompletableDeferred<ProtoMessage>()
            pending = response
            pendingId = callId
            try {
                val frame = Hdlc.frame(encodeRequest(service, method, request, callId))
                val chunk = (gatt.mtu - 3).coerceAtLeast(20)
                // A frame cut off halfway would garble the next one, so a cancelled call still sends all of it.
                withContext(NonCancellable) {
                    var offset = 0
                    while (offset < frame.size) {
                        val end = minOf(offset + chunk, frame.size)
                        gatt.write(txCharacteristic, frame.copyOfRange(offset, end))
                        offset = end
                    }
                }
                currentCoroutineContext().ensureActive()
                val packet = withTimeout(timeoutMs) { response.await() }
                val status = packet.int(FIELD_STATUS)
                if (status != 0) throw RpcException("$service/$method failed with status $status", status)
                ProtoMessage.parse(packet.bytes(FIELD_PAYLOAD))
            } finally {
                pending = null
            }
        }

    companion object {
        private const val FIELD_SERVICE_ID = 3
        private const val FIELD_METHOD_ID = 4
        private const val FIELD_PAYLOAD = 5
        private const val FIELD_STATUS = 6
        private const val FIELD_CALL_ID = 7

        /** Ids are the CRC-32 of the bare service name and of the PascalCase method name. */
        fun encodeRequest(service: String, method: String, request: ByteArray, callId: Int): ByteArray {
            val w = ProtoWriter()
                .fixed32(FIELD_SERVICE_ID, Hdlc.crc32(service.toByteArray(Charsets.US_ASCII)))
                .fixed32(FIELD_METHOD_ID, Hdlc.crc32(method.toByteArray(Charsets.US_ASCII)))
            if (request.isNotEmpty()) w.bytes(FIELD_PAYLOAD, request)
            w.uint32(FIELD_CALL_ID, callId)
            // The official client always ends an empty request with an explicit empty payload field;
            // matched here in case firmware relies on its presence.
            if (request.isEmpty()) w.bytes(FIELD_PAYLOAD, ByteArray(0))
            return w.toByteArray()
        }
    }
}
