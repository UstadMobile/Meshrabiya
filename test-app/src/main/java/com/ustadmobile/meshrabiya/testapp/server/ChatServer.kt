package com.ustadmobile.meshrabiya.testapp.server

import com.google.android.gms.nearby.connection.Payload
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.ext.asInetAddress
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ChatMessage(val timestamp: Long, val message: String)

class ChatServer(
    private val nearbyVirtualNetwork: NearbyVirtualNetwork
) {
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

    init {
        nearbyVirtualNetwork.setOnMessageReceivedListener { endpointId, payload ->
            handleIncomingPayload(endpointId, payload)
        }
    }

    private fun handleIncomingPayload(endpointId: String, payload: Payload) {
        payload.asBytes()?.let { bytes ->
            val message = String(bytes, Charsets.UTF_8).trim()
            val chatMessage = ChatMessage(System.currentTimeMillis(), message)
            _chatMessages.update { it + chatMessage }
        }
    }


    fun sendMessage(message: String) {
        val chatMessage = ChatMessage(System.currentTimeMillis(), message)
        _chatMessages.update { it + chatMessage }

        val messageBytes = message.toByteArray(Charsets.UTF_8)
        if (messageBytes.size > VirtualPacket.MAX_PAYLOAD_SIZE) {
            println("Error: Message size exceeds ${VirtualPacket.MAX_PAYLOAD_SIZE} bytes")
            return
        }

        val header = VirtualPacketHeader(
            toAddr = VirtualPacket.ADDR_BROADCAST,
            toPort = 0,
            fromAddr = nearbyVirtualNetwork.getVirtualIpAddress(),
            fromPort = 0,
            lastHopAddr = 0,
            hopCount = 0,
            maxHops = 1,
            payloadSize = messageBytes.size
        )

        val data = ByteArray(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)
        System.arraycopy(messageBytes, 0, data, VirtualPacketHeader.HEADER_SIZE, messageBytes.size)

        val virtualPacket = VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = data,
            payloadOffset = VirtualPacketHeader.HEADER_SIZE
        )

        // Send the virtual packet to all connected endpoints
        nearbyVirtualNetwork.send(virtualPacket, nearbyVirtualNetwork.getVirtualIpAddress().asInetAddress())
    }

    fun close() {
        nearbyVirtualNetwork.close()
    }
}