
package com.ustadmobile.meshrabiya.testapp.server


import android.util.Log
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

class ChatServer(
    private val nearbyNetwork: NearbyVirtualNetwork,
    private val logger: MNetLogger
) {
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages = _chatMessages.asStateFlow()

    private val localVirtualIp = nearbyNetwork.virtualAddress.hostAddress
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Sends a chat message to all connected endpoints in the network.
     */
    fun sendMessage(message: String) {
        if (message.isBlank()) {
            logger.invoke(Log.INFO, "Empty message not sent.")
            return
        }

        // Create chat message
        val chatMessage = ChatMessage(
            timestamp = System.currentTimeMillis(),
            sender = localVirtualIp,
            message = message.trim()
        )

        // Add to local history first
        _chatMessages.update { it + chatMessage }

        // Format message for sending: "senderIP|message"
        val messageText = "$localVirtualIp|${message.trim()}"
        val messageData = messageText.toByteArray(StandardCharsets.UTF_8)

        // Check message size
        if (messageData.size > MAX_UDP_PACKET_SIZE) {
            logger.invoke(Log.INFO, "Message too large, not sent")
            return
        }

        // Send to all connected endpoints
        scope.launch(Dispatchers.IO) {
            try {
                val connectedEndpoints = nearbyNetwork.endpointStatusFlow.value
                    .filter { it.value.status == NearbyVirtualNetwork.EndpointStatus.CONNECTED }

                logger.invoke(Log.DEBUG, "Found ${connectedEndpoints.size} endpoints to send to")

                // Send to each endpoint
                connectedEndpoints.forEach { (endpointId, _) ->
                    try {
                        nearbyNetwork.sendUdpPacket(
                            endpointId = endpointId,
                            sourcePort = UDP_PORT,
                            destinationPort = UDP_PORT,
                            data = messageData
                        )
                        logger.invoke(Log.DEBUG, "Message sent to endpoint: $endpointId")
                    } catch (e: Exception) {
                        logger.invoke(Log.ERROR, "Failed to send to endpoint: $endpointId", e)
                    }
                }
            } catch (e: Exception) {
                logger.invoke(Log.ERROR, "Failed to send message", e)
            }
        }
    }

    /**
     * Adds a received message to the chat history
     */
    fun processReceivedMessage(messageBytes: ByteArray) {
        try {
            val message = String(messageBytes, StandardCharsets.UTF_8)
            val parts = message.split("|")

            if (parts.size == 2) {
                val (sender, messageText) = parts
                if (sender != localVirtualIp) {  // Skip own messages
                    val chatMessage = ChatMessage(
                        timestamp = System.currentTimeMillis(),
                        sender = sender,
                        message = messageText
                    )
                    _chatMessages.update { it + chatMessage }
                    logger.invoke(Log.INFO, "Message received from $sender")
                }
            }
        } catch (e: Exception) {
            logger.invoke(Log.ERROR, "Error processing received message", e)
        }
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        const val UDP_PORT = 8888
        const val MAX_UDP_PACKET_SIZE = 65507
    }
}
/**
 * Data class representing a chat message
 */
data class ChatMessage(
    val timestamp: Long,
    val sender: String,
    val message: String
)