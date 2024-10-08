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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress


import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets



data class ChatMessage(
    val timestamp: Long,
    val sender: String,
    val message: String
)

class ChatServer(
    private val nearbyVirtualNetwork: NearbyVirtualNetwork,
    private val logger: MNetLogger
) {
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages = _chatMessages.asStateFlow()

    private val udpSocket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(UDP_PORT))
        broadcast = true
    }
    private val localVirtualIp = nearbyVirtualNetwork.virtualAddress.hostAddress
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        listenForUdpMessages()
    }

    private fun listenForUdpMessages() {
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udpSocket.receive(packet)
                    processReceivedPacket(packet)
                } catch (e: SocketException) {
                    if (!udpSocket.isClosed) {
                        logger.invoke(Log.WARN, "SocketException while receiving UDP message: ${e.message}")
                    }
                } catch (e: Exception) {
                    logger.invoke(Log.ERROR, "Unexpected error while receiving UDP message: ${e.message}", e)
                }
            }
        }
    }

    private fun processReceivedPacket(packet: DatagramPacket) {
        val receivedData = packet.data.copyOf(packet.length)
        val buffer = ByteBuffer.wrap(receivedData)

        try {
            val senderVirtualIp = extractVirtualIp(buffer)
            val message = extractMessage(buffer)

            if (senderVirtualIp != localVirtualIp) {
                val chatMessage = ChatMessage(System.currentTimeMillis(), senderVirtualIp, message)
                _chatMessages.update { it + chatMessage }
                logger.invoke(Log.INFO, "Received message: \"$message\" from virtual IP: $senderVirtualIp")
            }
        } catch (e: Exception) {
            logger.invoke(Log.ERROR, "Error processing received packet: ${e.message}", e)
        }
    }

    fun sendMessage(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) {
            logger.invoke(Log.INFO, "Error: Empty message not sent.")
            return
        }

        val chatMessage = ChatMessage(System.currentTimeMillis(), localVirtualIp, trimmedMessage)
        _chatMessages.update { it + chatMessage } // Add to local messages

        val messageBytes = trimmedMessage.toByteArray(StandardCharsets.UTF_8)
        if (messageBytes.size > MAX_MESSAGE_SIZE) {
            logger.invoke(Log.INFO, "Error: Message size exceeds $MAX_MESSAGE_SIZE bytes.")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val packet = buildDatagramPacket(messageBytes)
                udpSocket.send(packet)

                logger.invoke(Log.INFO, "Sent message: \"$trimmedMessage\" to broadcast address from virtual IP: $localVirtualIp")
            } catch (e: Exception) {
                logger.invoke(Log.ERROR, "Error sending message: ${e.message}", e)
            }
        }
    }

    private fun buildDatagramPacket(messageBytes: ByteArray): DatagramPacket {
        val buffer = ByteBuffer.allocate(4 + messageBytes.size)
        buffer.put(nearbyVirtualNetwork.virtualAddress.address)
        buffer.put(messageBytes)

        val broadcastAddress = InetAddress.getByName(BROADCAST_IP)
        return DatagramPacket(buffer.array(), buffer.position(), broadcastAddress, UDP_PORT)
    }

    private fun extractVirtualIp(buffer: ByteBuffer): String {
        val virtualIpBytes = ByteArray(4)
        buffer.get(virtualIpBytes)
        return InetAddress.getByAddress(virtualIpBytes).hostAddress
    }

    private fun extractMessage(buffer: ByteBuffer): String {
        val messageBytes = ByteArray(buffer.remaining())
        buffer.get(messageBytes)
        return String(messageBytes, StandardCharsets.UTF_8).trim()
    }

    fun close() {
        scope.cancel()
        udpSocket.close()
    }

    companion object {
        const val UDP_PORT = 8888
        const val MAX_UDP_PACKET_SIZE = 65507 // Maximum theoretical UDP packet size
        const val MAX_MESSAGE_SIZE = MAX_UDP_PACKET_SIZE - 4 // 4 bytes for virtual IP
        const val BROADCAST_IP = "255.255.255.255"
    }
}

