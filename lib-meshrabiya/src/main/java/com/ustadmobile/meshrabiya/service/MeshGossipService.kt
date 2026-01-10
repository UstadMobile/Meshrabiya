package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * MeshGossipService: Generic request-response correlation service for mesh-wide messaging.
 * 
 * Refactored to use constructor injection with VirtualNode parameter for UDP broadcast access.
 * 
 * This service provides:
 * - Request ID generation
 * - Response collection with timeouts
 * - Generic handler registration for correlated responses
 * - UDP broadcast capability via VirtualNode's OriginatingMessageManager
 * 
 * Architecture:
 * - Instantiated by VirtualNode with `this` reference (constructor injection)
 * - Uses virtualNode.originatingMessageManager.neighbors() for socket access
 * - CoreGossipBroadcastService uses this service for broadcasts
 * - MeshEcosystemListener routes messages to domain managers
 * - Domain managers (DistributedStorageManager, ComputeService) prepare their own messages
 * - Singleton instance available via getInstance() for backward compatibility
 */
class MeshGossipService(
    private val virtualNode: VirtualNode
) {

    companion object {
        @Volatile
        private var instance: MeshGossipService? = null

        /**
         * Get the singleton instance of MeshGossipService.
         * Must be initialized by VirtualNode before use.
         */
        fun getInstance(): MeshGossipService {
            return instance ?: throw IllegalStateException(
                "MeshGossipService not initialized. VirtualNode must call initialize() first."
            )
        }

        /**
         * Initialize the singleton instance with a VirtualNode.
         * Called by VirtualNode during instantiation.
         */
        internal fun initialize(virtualNode: VirtualNode): MeshGossipService {
            return instance ?: synchronized(this) {
                instance ?: MeshGossipService(virtualNode).also { instance = it }
            }
        }

        /**
         * Clear the singleton instance (for testing).
         */
        internal fun clearInstance() {
            instance = null
        }
    }

    // Generic pending request tracking - keyed by requestId, value is collector
    private val pendingRequests = ConcurrentHashMap<String, ResponseCollector<Any>>()
    
    private val logger: MNetLogger = MNetLoggerStdout()

    /**
     * Helper class to collect multiple responses for a single request with timeout.
     * Generic to support any response type.
     */
    class ResponseCollector<T>(
        val requestId: String,
        val timeoutMs: Long
    ) {
        private val responses = mutableListOf<T>()
        private val deferred = CompletableDeferred<List<T>>()
        private var timeoutJob: Job? = null

        fun addResponse(response: T) {
            synchronized(responses) {
                responses.add(response)
            }
        }

        suspend fun awaitResponses(scope: CoroutineScope): List<T> {
            timeoutJob = scope.launch {
                delay(timeoutMs)
                complete()
            }
            return deferred.await()
        }

        fun complete() {
            synchronized(responses) {
                if (!deferred.isCompleted) {
                    deferred.complete(responses.toList())
                    timeoutJob?.cancel()
                }
            }
        }

        fun cancel() {
            timeoutJob?.cancel()
            if (!deferred.isCompleted) {
                deferred.cancel()
            }
        }
    }

    // === Generic Request-Response Correlation API ===

    /**
     * Register a pending request and return a collector for responses.
     * Caller is responsible for sending the actual broadcast message via CoreGossipBroadcastService.
     * 
     * Example usage in DistributedStorageManager:
     * ```
     * val requestId = meshGossipService.generateRequestId()
     * val collector = meshGossipService.registerPendingRequest<StorageNodeResponse>(requestId, timeoutMs)
     * val message = MeshEcosystemMessage.StorageNodeRequestMessage(request, requestId)
     * coreGossipBroadcastService.sendBroadcast(message)
     * return collector.awaitResponses(coroutineScope)
     * ```
     */
    fun <T> registerPendingRequest(requestId: String, timeoutMs: Long): ResponseCollector<T> {
        val collector = ResponseCollector<T>(requestId, timeoutMs)
        @Suppress("UNCHECKED_CAST")
        pendingRequests[requestId] = collector as ResponseCollector<Any>
        return collector
    }

    /**
     * Handle an incoming response for a pending request.
     * Called by MeshEcosystemListener or domain managers when they receive correlated responses.
     * 
     * Example: When MeshEcosystemListener receives a StorageNodeResponse, it calls:
     * ```
     * meshGossipService.handleResponse(requestId, response)
     * ```
     */
    fun <T> handleResponse(requestId: String, response: T) {
        @Suppress("UNCHECKED_CAST")
        (pendingRequests[requestId] as? ResponseCollector<T>)?.addResponse(response)
    }

    /**
     * Complete a pending request (stops waiting for more responses).
     * Automatically called by ResponseCollector timeout, but can be called manually.
     */
    fun completePendingRequest(requestId: String) {
        pendingRequests[requestId]?.complete()
        pendingRequests.remove(requestId)
    }

    /**
     * Remove a pending request (cleanup after completion or cancellation).
     */
    fun removePendingRequest(requestId: String) {
        pendingRequests.remove(requestId)
    }

    /**
     * Check if a request is pending.
     */
    fun hasPendingRequest(requestId: String): Boolean {
        return pendingRequests.containsKey(requestId)
    }

    /**
     * Get count of pending requests (useful for diagnostics/monitoring).
     */
    fun pendingRequestCount(): Int = pendingRequests.size

    /**
     * Generate a unique request ID using UUID.
     */
    fun generateRequestId(): String = UUID.randomUUID().toString()

    /**
     * Broadcast a message payload to all direct neighbors via UDP.
     * 
     * Uses VirtualNode's OriginatingMessageManager to access neighbor sockets.
     * Creates VirtualPacket with proper header (ecosystem gossip port, node addresses).
     * 
     * @param payload The serialized message bytes to broadcast
     * @return Number of neighbors the message was sent to
     */
    fun broadcastMessage(payload: ByteArray): Int {
        val neighbors = virtualNode.neighbors() // Use public accessor

        if (neighbors.isEmpty()) {
            logger(android.util.Log.WARN, "broadcastMessage: No neighbors available for broadcast")
            return 0
        }

        val ecosystemPort = com.ustadmobile.meshrabiya.MeshrabiyaConstants.getEcosystemGossipPort()
        val fromAddr = virtualNode.addressAsInt // Use mesh node address as Int
        var successCount = 0

        logger(android.util.Log.DEBUG, "broadcastMessage: Broadcasting ${payload.size} bytes to ${neighbors.size} neighbors")
        neighbors.forEach { (neighborAddr, lastMsg) ->
            try {
                // Create buffer with space for header
                val buffer = ByteArray(VirtualPacketHeader.HEADER_SIZE + payload.size)

                // Copy payload after header space
                System.arraycopy(payload, 0, buffer, VirtualPacketHeader.HEADER_SIZE, payload.size)

                // Create header
                val header = VirtualPacketHeader(
                    toAddr = neighborAddr,
                    toPort = ecosystemPort,
                    fromAddr = fromAddr,
                    fromPort = ecosystemPort,
                    lastHopAddr = fromAddr,
                    hopCount = 1,
                    maxHops = 1, // Direct neighbor only
                    gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE, //V3: Gossip is mesh-local
                    payloadSize = payload.size
                )

                // Create packet
                val packet = VirtualPacket.fromHeaderAndPayloadData(
                    header = header,
                    data = buffer,
                    payloadOffset = VirtualPacketHeader.HEADER_SIZE
                )

                // Send via neighbor's socket
                lastMsg.receivedFromSocket.send(
                    virtualNode.getInetAddressFor(neighborAddr),      // The InetAddress of the neighbor
                    ecosystemPort,     // The gossip port (Int)
                    packet             // The VirtualPacket to send
                )
                successCount++

                logger(android.util.Log.VERBOSE, "broadcastMessage: Sent to neighbor ${neighborAddr}")
            } catch (ex: Exception) {
                logger(android.util.Log.ERROR, "broadcastMessage: Failed to send to neighbor ${neighborAddr}", ex)
            }
        }
        logger(android.util.Log.DEBUG, "broadcastMessage: Successfully sent to $successCount/${neighbors.size} neighbors")
        return successCount
    }

    /**
     * Get the local node's mesh address as Int.
     * Used by CoreGossipBroadcastService to identify sender.
     */
    fun getLocalNodeAddress(): Int {
        return virtualNode.addressAsInt
    }

    /**
     * Cleanup method to cancel all pending requests (call on shutdown).
     */
    fun shutdown() {
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
    }
    fun getNodeAddressAsInt(): Int {
        return virtualNode.addressAsInt
    }
}

// Helper extension if needed
// Only use this for InetAddress, not mesh node addresses (which are Int)
// InetAddress.hostAddress is guaranteed non-null for valid instances
fun java.net.InetAddress.addressToDotNotation(): String = this.hostAddress!!