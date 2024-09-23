package com.ustadmobile.meshrabiya.testapp.server

import com.google.android.gms.nearby.connection.Payload
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ChatMessage(val timestamp: Long, val message: String)
class ChatServer(
    private val nearbyVirtualNetwork: NearbyVirtualNetwork
) {

    companion object {
        private val DEFAULT_ENCODING = Charsets.UTF_8
    }
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> get() = _chatMessages

    init {
        nearbyVirtualNetwork.setOnMessageReceivedListener { endpointId, payload ->
            handleIncomingPayload(endpointId, payload)
        }
    }

    // Function to process the incoming payload, convert it to a chat message, and update the chat log
    private fun handleIncomingPayload(endpointId: String, payload: Payload) {
        if (payload.type == Payload.Type.BYTES) {
            // Extract bytes from the payload, and if it's null, log a warning and return
            val bytes = payload.asBytes() ?: run {
                println("Received null bytes payload from $endpointId")
                return
            }

            // Convert the byte array to a String using the default encoding
            val message = String(bytes, DEFAULT_ENCODING)

            val chatMessage = ChatMessage(
                timestamp = currentTime(),
                message = message
            )
            _chatMessages.value += chatMessage
        }
    }

    // Function to send a message. Optionally, you can specify a target endpoint
    fun sendMessage(message: String, endpointId: String? = null) {
        val chatMessage = ChatMessage(
            timestamp = currentTime(),
            message = message
        )

        _chatMessages.value += chatMessage

        // If an endpoint is specified, send the message to that particular endpoint
        endpointId?.let {
            nearbyVirtualNetwork.sendMessage(it, message)
        } ?: run {
            // Otherwise, send the message to all connected endpoints
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

    private fun currentTime() = System.currentTimeMillis()
}
