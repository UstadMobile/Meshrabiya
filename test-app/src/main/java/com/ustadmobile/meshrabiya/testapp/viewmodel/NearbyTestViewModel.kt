package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.nearby.connection.Payload
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.server.ChatServer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.random.Random

class NearbyTestViewModel(application: Application) : AndroidViewModel(application) {
    private val _isNetworkRunning = MutableStateFlow(false)
    val isNetworkRunning: StateFlow<Boolean> = _isNetworkRunning

    private val _endpoints = MutableStateFlow<List<NearbyVirtualNetwork.EndpointInfo>>(emptyList())
    val endpoints: StateFlow<List<NearbyVirtualNetwork.EndpointInfo>> = _endpoints

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages

    private lateinit var nearbyNetwork: NearbyVirtualNetwork
    private lateinit var chatServer: ChatServer

    private val logger = object : MNetLogger() {
        override fun invoke(priority: Int, message: String, exception: Exception?) {
            val logMessage = "${MNetLogger.priorityLabel(priority)}: $message"
            _logs.value = _logs.value + logMessage
        }

        override fun invoke(priority: Int, message: () -> String, exception: Exception?) {
            invoke(priority, message(), exception)
        }
    }

    init {
        initializeNearbyNetwork()
        initializeChatServer()
    }

    private fun initializeNearbyNetwork() {
        val virtualIpAddress = "169.254.${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}"
        val broadcastAddress = "169.254.255.255"

        nearbyNetwork = NearbyVirtualNetwork(
            context = getApplication(),
            name = "Device-${Random.nextInt(1000)}",
            serviceId = "com.ustadmobile.meshrabiya.test",
            virtualIpAddress = ipToInt(virtualIpAddress),
            broadcastAddress = ipToInt(broadcastAddress),
            logger = logger
        )

        nearbyNetwork.setOnMessageReceivedListener { endpointId, payload ->
            handleIncomingPayload(endpointId, payload)
        }
    }

    private fun initializeChatServer() {
        chatServer = ChatServer(nearbyNetwork)

        viewModelScope.launch {
            chatServer.chatMessages.collect { messages ->
                _messages.value = messages.map { it.message }
            }
        }
    }

    fun ipToInt(ipAddress: String): Int {
        val inetAddress = InetAddress.getByName(ipAddress)
        val byteArray = inetAddress.address
        return ByteBuffer.wrap(byteArray).int
    }

    fun startNetwork() {
        try {
            nearbyNetwork.start()
            chatServer
            _isNetworkRunning.value = true
            observeEndpoints()
        } catch (e: IllegalStateException) {
            logger.invoke(Log.ERROR, "Failed to start network: ${e.message}")
        }
    }

    fun stopNetwork() {
        nearbyNetwork.close()
        chatServer.close()
        _isNetworkRunning.value = false
    }

    private fun observeEndpoints() {
        viewModelScope.launch {
            nearbyNetwork.endpointStatusFlow.collect { endpointMap ->
                _endpoints.value = endpointMap.values.toList()
            }
        }
    }

    fun sendMessage(message: String) {
        viewModelScope.launch {
            try {
                chatServer.sendMessage(message)
                logger.invoke(Log.INFO, "Message sent: $message")
            } catch (e: IllegalStateException) {
                logger.invoke(Log.ERROR, "Failed to send message: ${e.message}")
            }
        }
    }


    private fun handleIncomingPayload(endpointId: String, payload: Payload) {
        if (payload.type == Payload.Type.BYTES) {
            val bytes = payload.asBytes() ?: return
            val message = String(bytes, Charsets.UTF_8)
            _messages.value = _messages.value + message
            logger.invoke(Log.INFO, "Received message from $endpointId: $message")
        } else {
            logger.invoke(Log.INFO, "Received unsupported payload type from $endpointId")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopNetwork()
    }
}
