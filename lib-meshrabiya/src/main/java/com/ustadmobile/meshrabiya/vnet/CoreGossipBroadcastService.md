package com.ustadmobile.meshrabiya.vnet

import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

/**
 * CoreGossipBroadcastService
 *
 * Generic, production-ready broadcast service for mesh gossip/flooding.
 * Handles sending, deduplication, neighbor-aware forwarding, and listener notification.
 * Integrates with OriginatingMessageManager for neighbor discovery and message sending.
 * Optimized for low latency, low overhead, and fast mesh-wide delivery.
 */
class CoreGossipBroadcastService(
    private val originatingMessageManager: OriginatingMessageManager,
    private val sendToNode: (Int, ByteArray) -> Unit,
    private val broadcastTtlMs: Long = 60_000L // TTL for deduplication cache
) {

    // Deduplication cache: broadcastId -> timestamp
    private val seenBroadcasts = ConcurrentHashMap<String, Long>()

    // Listener registry: messageType -> list of listeners
    private val listeners = ConcurrentHashMap<String, MutableList<(Int, ByteArray, String, Any?) -> Unit>>()

    // Coroutine scope for cleanup and async ops
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Periodic cleanup of deduplication cache
        scope.launch {
            while (isActive) {
                val cutoff = System.currentTimeMillis() - broadcastTtlMs
                seenBroadcasts.entries.removeIf { it.value < cutoff }
                delay(broadcastTtlMs / 2)
            }
        }
    }

    /**
     * BroadcastMessage: includes broadcastId, payload, senderId, and recipientIds.
     * This enables neighbor-aware flooding and optimized rebroadcast.
     */
    data class BroadcastMessage(
        val broadcastId: String,
        val payload: ByteArray,
        val senderId: Int,
        val recipientIds: Set<Int>
    ) {
        fun toBytes(): ByteArray {
            // Simple serialization: broadcastId + senderId + recipientIds + payload
            // Use a canonical format for production (e.g., protobuf, CBOR, etc.)
            val idBytes = broadcastId.toByteArray(Charsets.UTF_8)
            val senderBytes = senderId.toString().toByteArray(Charsets.UTF_8)
            val recipientsBytes = recipientIds.joinToString(",").toByteArray(Charsets.UTF_8)
            val delimiter = "|".toByteArray(Charsets.UTF_8)
            return idBytes + delimiter + senderBytes + delimiter + recipientsBytes + delimiter + payload
        }

        companion object {
            fun fromBytes(bytes: ByteArray): BroadcastMessage {
                val parts = bytes.toString(Charsets.UTF_8).split("|", limit = 4)
                val broadcastId = parts[0]
                val senderId = parts[1].toInt()
                val recipientIds = if (parts[2].isEmpty()) emptySet() else parts[2].split(",").map { it.toInt() }.toSet()
                val payload = parts[3].toByteArray(Charsets.UTF_8)
                return BroadcastMessage(broadcastId, payload, senderId, recipientIds)
            }
        }
    }

    /**
     * Send a broadcast message to all direct neighbors.
     * Includes a recipient list for neighbor-aware flooding.
     */
    fun sendBroadcast(payload: ByteArray, messageType: String, broadcastId: String? = null) {
        val myNeighbors = originatingMessageManager.neighbors().map { it.first }.toSet()
        val senderId = originatingMessageManager.getCurrentState().pendingMessages.keys.firstOrNull() ?: -1
        val id = broadcastId ?: computeBroadcastId(messageType, payload)
        val msg = BroadcastMessage(
            broadcastId = id,
            payload = payload,
            senderId = senderId,
            recipientIds = myNeighbors
        )
        seenBroadcasts[id] = System.currentTimeMillis()
        myNeighbors.forEach { neighborId ->
            sendToNode(neighborId, msg.toBytes())
        }
    }

    /**
     * Handle an incoming broadcast message.
     * Dedupes and forwards if unseen, notifies listeners.
     * Uses neighbor-aware flooding: excludes sender and all sender's recipients.
     */
    fun onReceiveBroadcast(rawBytes: ByteArray, messageType: String, messageObj: Any? = null) {
        val msg = BroadcastMessage.fromBytes(rawBytes)
        val id = msg.broadcastId
        val now = System.currentTimeMillis()
        val prev = seenBroadcasts.putIfAbsent(id, now)
        if (prev == null) {
            // Neighbor-aware forwarding: forward only to neighbors not in recipientIds and not sender
            val myNeighbors = originatingMessageManager.neighbors().map { it.first }.toSet()
            val newRecipients = myNeighbors - msg.recipientIds - msg.senderId
            val updatedMsg = msg.copy(recipientIds = msg.recipientIds + myNeighbors)
            newRecipients.forEach { neighborId ->
                sendToNode(neighborId, updatedMsg.toBytes())
            }
            // Notify listeners
            listeners[messageType]?.forEach { listener ->
                listener(msg.senderId, msg.payload, messageType, messageObj)
            }
        }
        // If already seen, drop for rebroadcast purposes
    }

    /**
     * Register a listener for a specific message type.
     * Listener receives (senderId, payload, messageType, messageObj).
     */
    fun registerListener(messageType: String, listener: (Int, ByteArray, String, Any?) -> Unit) {
        listeners.computeIfAbsent(messageType) { mutableListOf() }.add(listener)
    }

    /**
     * Unregister a listener for a specific message type.
     */
    fun unregisterListener(messageType: String, listener: (Int, ByteArray, String, Any?) -> Unit) {
        listeners[messageType]?.remove(listener)
    }

    /**
     * Compute a broadcast ID for deduplication.
     * Uses messageType + SHA-256 hash of bytes.
     */
    private fun computeBroadcastId(messageType: String, messageBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(messageBytes)
        val hex = hash.joinToString("") { "%02x".format(it) }
        return "$messageType:$hex"
    }

    /**
     * Shutdown the broadcast service and cleanup resources.
     */
    fun shutdown() {
        scope.cancel()
        seenBroadcasts.clear()
        listeners.clear()
    }
}