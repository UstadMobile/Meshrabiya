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

    private val connectedUsers = mutableSetOf<String>()

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
        val message = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
        val parts = message.split("|")
        if (parts.size == 2) {
            val senderIp = parts[0]
            val chatMessage = parts[1]

            if (senderIp != localVirtualIp) {
                connectedUsers.add(senderIp)
                val newMessage = ChatMessage(System.currentTimeMillis(), senderIp, chatMessage)
                _chatMessages.value = _chatMessages.value + newMessage
                logger.invoke(Log.INFO, "Received message: \"$chatMessage\" from: $senderIp")
            }
        }
    }

    fun sendMessage(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) {
            logger.invoke(Log.INFO, "Error: Empty message not sent.")
            return
        }

        val chatMessage = ChatMessage(System.currentTimeMillis(), localVirtualIp, trimmedMessage)
        _chatMessages.value = _chatMessages.value + chatMessage

        val fullMessage = "$localVirtualIp|$trimmedMessage"
        val messageBytes = fullMessage.toByteArray(StandardCharsets.UTF_8)

        if (messageBytes.size > MAX_UDP_PACKET_SIZE) {
            logger.invoke(Log.INFO, "Error: Message size exceeds $MAX_UDP_PACKET_SIZE bytes.")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val packet = DatagramPacket(messageBytes, messageBytes.size, InetAddress.getByName(BROADCAST_IP), UDP_PORT)
                udpSocket.send(packet)
                logger.invoke(Log.INFO, "Sent message: \"$trimmedMessage\" to all connected users")
            } catch (e: Exception) {
                logger.invoke(Log.ERROR, "Error sending message: ${e.message}", e)
            }
        }
    }

    fun close() {
        scope.cancel()
        udpSocket.close()
    }

    companion object {
        const val UDP_PORT = 8888
        const val MAX_UDP_PACKET_SIZE = 65507 // Maximum theoretical UDP packet size
        const val BROADCAST_IP = "255.255.255.255"
    }
}

