package com.ustadmobile.meshrabiya.testapp.server

import com.google.android.gms.nearby.connection.Payload
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ChatMessage(val timestamp: Long, val message: String)
class ChatServer(
    private val nearbyVirtualNetwork: NearbyVirtualNetwork
) {

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> get() = _chatMessages

    init {
        nearbyVirtualNetwork.setOnMessageReceivedListener { endpointId, payload ->
            handleIncomingPayload(endpointId, payload)
        }
    }

    private fun handleIncomingPayload(endpointId: String, payload: Payload) {
        if (payload.type == Payload.Type.BYTES) {
            val bytes = payload.asBytes() ?: return
            val message = String(bytes, Charsets.UTF_8)
            val chatMessage = ChatMessage(timestamp = System.currentTimeMillis(), message = message)
            _chatMessages.value = _chatMessages.value + chatMessage
        }
    }

    fun sendMessage(message: String, endpointId: String? = null) {
        val chatMessage = ChatMessage(timestamp = System.currentTimeMillis(), message = message)
        _chatMessages.value = _chatMessages.value + chatMessage

        if (endpointId != null) {
            nearbyVirtualNetwork.sendMessage(endpointId, message)
        } else {
            nearbyVirtualNetwork.endpointStatusFlow.value.values
                .filter { it.status == NearbyVirtualNetwork.EndpointStatus.CONNECTED }
                .forEach { endpoint ->
                    nearbyVirtualNetwork.sendMessage(endpoint.endpointId, message)
                }
        }
    }

    fun close() {
        nearbyVirtualNetwork.close()
    }
}
