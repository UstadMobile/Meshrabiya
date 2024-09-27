package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.nearby.connection.Payload
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.server.ChatMessage
import com.ustadmobile.meshrabiya.testapp.server.ChatServer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.random.Random

class NearbyTestViewModel(application: Application) : AndroidViewModel(application) {
    private val _isNetworkRunning = MutableStateFlow(false)
    val isNetworkRunning: StateFlow<Boolean> = _isNetworkRunning.asStateFlow()

    private val _endpoints = MutableStateFlow<List<NearbyVirtualNetwork.EndpointInfo>>(emptyList())
    val endpoints: StateFlow<List<NearbyVirtualNetwork.EndpointInfo>> = _endpoints.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()

    private lateinit var nearbyNetwork: NearbyVirtualNetwork
    private lateinit var chatServer: ChatServer
    private var isNetworkInitialized = false

    private val logger = object : MNetLogger() {
        override fun invoke(priority: Int, message: String, exception: Exception?) {
            val logMessage = "${MNetLogger.priorityLabel(priority)}: $message"
            viewModelScope.launch(Dispatchers.Main) {
                _logs.update { it + logMessage }
            }
        }

        override fun invoke(priority: Int, message: () -> String, exception: Exception?) {
            invoke(priority, message(), exception)
        }
    }

    init {
        initializeNearbyNetwork()
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

        chatServer = ChatServer(nearbyNetwork)

        viewModelScope.launch {
            chatServer.chatMessages.collect { messages ->
                _messages.update { messages.map { it.message } }
            }
        }
    }

    private fun ipToInt(ipAddress: String): Int {
        val inetAddress = InetAddress.getByName(ipAddress)
        return ByteBuffer.wrap(inetAddress.address).int
    }

    fun startNetwork() {
        if (!isNetworkInitialized) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    nearbyNetwork.start()
                    _isNetworkRunning.value = true
                    isNetworkInitialized = true
                    observeEndpoints()
                    logger.invoke(Log.INFO, "Network started successfully")
                } catch (e: IllegalStateException) {
                    logger.invoke(Log.ERROR, "Failed to start network: ${e.message}")
                }
            }
        } else {
            logger.invoke(Log.INFO, "Network is already running.")
        }
    }

    fun stopNetwork() {
        if (isNetworkInitialized) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    nearbyNetwork.close()
                    chatServer.close()
                    resetState()
                    _isNetworkRunning.value = false
                    isNetworkInitialized = false
                    logger.invoke(Log.INFO, "Network stopped successfully")
                } catch (e: Exception) {
                    logger.invoke(Log.ERROR, "Failed to stop network: ${e.message}")
                }
            }
        } else {
            logger.invoke(Log.INFO, "Network is not running.")
        }
    }

    private fun resetState() {
        _logs.value = emptyList()
        _messages.value = emptyList()
        _endpoints.value = emptyList()
    }

    private fun observeEndpoints() {
        viewModelScope.launch {
            nearbyNetwork.endpointStatusFlow.collect { endpointMap ->
                _endpoints.value = endpointMap.values
                    .filter { it.status == NearbyVirtualNetwork.EndpointStatus.CONNECTED }
                    .distinctBy { it.endpointId }
            }
        }
    }

    fun sendMessage(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    chatServer.sendMessage(trimmedMessage)
                    logger.invoke(Log.INFO, "Message sent: $trimmedMessage")
                } catch (e: IllegalStateException) {
                    logger.invoke(Log.ERROR, "Failed to send message: ${e.message}")
                }
            }
        } else {
            logger.invoke(Log.INFO, "Empty message not sent.")
        }
    }

    private fun handleIncomingPayload(endpointId: String, payload: Payload) {
        if (payload.type == Payload.Type.BYTES) {
            val bytes = payload.asBytes() ?: return
            val message = String(bytes, Charsets.UTF_8).trim() // Trim the incoming message
            viewModelScope.launch(Dispatchers.Main) {
                _messages.update { it + message }
            }
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
