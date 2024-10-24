
package com.ustadmobile.meshrabiya.testapp.server


import android.util.Log
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress

class ChatServer(
    private val nearbyNetwork: NearbyVirtualNetwork,
    private val logger: MNetLogger
) {
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages = _chatMessages.asStateFlow()

    private val socket: MeshrabiyaDatagramSocket by lazy {
        MeshrabiyaDatagramSocket(nearbyNetwork).apply {
            bind(InetSocketAddress(UDP_PORT))
            setMessageHandler { message ->
                processReceivedMessage(message.toByteArray())
            }
        }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        try {
            // Format: "senderIP|message"
            val messageText = "${nearbyNetwork.virtualAddress.hostAddress}|$message"
            val data = messageText.toByteArray()

            // Create broadcast packet
            val packet = DatagramPacket(
                data,
                data.size,
                InetAddress.getByName("255.255.255.255"),
                UDP_PORT
            )

            // Add message to local chat immediately
            val chatMessage = ChatMessage(
                timestamp = System.currentTimeMillis(),
                sender = nearbyNetwork.virtualAddress.hostAddress,
                message = message
            )
            _chatMessages.update { it + chatMessage }

            // Send via socket
            socket.send(packet)
            logger(Log.INFO, "Message sent: $message")
        } catch (e: Exception) {
            logger(Log.ERROR, "Failed to send message", e)
        }
    }

    fun processReceivedMessage(messageData: ByteArray) {
        try {
            val message = String(messageData, Charsets.UTF_8)
            val parts = message.split("|")

            if (parts.size == 2) {
                val (sender, messageText) = parts

                // Only process messages from others (we already added our own in sendMessage)
                if (sender != nearbyNetwork.virtualAddress.hostAddress) {
                    val chatMessage = ChatMessage(
                        timestamp = System.currentTimeMillis(),
                        sender = sender,
                        message = messageText
                    )
                    _chatMessages.update { it + chatMessage }
                    logger(Log.INFO, "Message received from $sender: $messageText")
                }
            }
        } catch (e: Exception) {
            logger(Log.ERROR, "Error processing received message", e)
        }
    }

    fun close() {
        socket.close()
    }

    companion object {
        const val UDP_PORT = 8888
    }
}

data class ChatMessage(
    val sender: String,
    val message: String,
    val timestamp: Long
)