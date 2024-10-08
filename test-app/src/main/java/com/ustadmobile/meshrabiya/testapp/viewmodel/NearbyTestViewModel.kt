package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.nearby.connection.Payload
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.ext.asInetAddress
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.server.ChatMessage
import com.ustadmobile.meshrabiya.testapp.server.ChatServer
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
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

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private lateinit var nearbyNetwork: NearbyVirtualNetwork
    private lateinit var chatServer: ChatServer
    private var isNetworkInitialized = false

    private val logger = object : MNetLogger() {
        override fun invoke(priority: Int, message: String, exception: Exception?) {
            val logMessage = "${MNetLogger.priorityLabel(priority)}: $message"
            viewModelScope.launch {
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
        val virtualIpAddress = generateVirtualIpAddress()
        val broadcastAddress = BROADCAST_IP_ADDRESS

        nearbyNetwork = NearbyVirtualNetwork(
            context = getApplication(),
            name = generateDeviceName(),
            serviceId = NETWORK_SERVICE_ID,
            virtualIpAddress = ipToInt(virtualIpAddress),
            broadcastAddress = ipToInt(broadcastAddress),
            logger = logger
        ) { packet ->
            // Handle received packet if needed
        }

        chatServer = ChatServer(nearbyNetwork, logger)

        observeChatMessages()
    }

    private fun generateVirtualIpAddress(): String =
        "169.254.${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}"

    private fun generateDeviceName(): String =
        "Device-${Random.nextInt(DEVICE_NAME_SUFFIX_LIMIT)}"

    private fun ipToInt(ipAddress: String): Int {
        val inetAddress = InetAddress.getByName(ipAddress)
        return ByteBuffer.wrap(inetAddress.address).int
    }

    fun startNetwork() {
        if (isNetworkInitialized) {
            logger.invoke(Log.INFO, "Network is already running.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                nearbyNetwork.start()
                _isNetworkRunning.value = true
                isNetworkInitialized = true
                observeEndpoints()
                logger.invoke(Log.INFO, "Network started successfully with IP: ${nearbyNetwork.virtualAddress.hostAddress}")
            } catch (e: IllegalStateException) {
                logger.invoke(Log.ERROR, "Failed to start network: ${e.message}", e)
            }
        }
    }

    fun stopNetwork() {
        if (!isNetworkInitialized) {
            logger.invoke(Log.INFO, "Network is not running.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                nearbyNetwork.close()
                chatServer.close()
                resetState()
                _isNetworkRunning.value = false
                isNetworkInitialized = false
                logger.invoke(Log.INFO, "Network stopped successfully")
            } catch (e: Exception) {
                logger.invoke(Log.ERROR, "Failed to stop network: ${e.message}", e)
            }
        }
    }

    private fun resetState() {
        _logs.value = emptyList()
        _messages.value = emptyList()
        _endpoints.value = emptyList()
    }

    private fun observeEndpoints() {
        viewModelScope.launch {
            nearbyNetwork.endpointStatusFlow
                .distinctUntilChangedBy { endpointMap ->
                    endpointMap.values.filter { it.status == NearbyVirtualNetwork.EndpointStatus.CONNECTED }
                        .distinctBy { it.endpointId }
                }
                .collect { endpointMap ->
                    val connectedEndpoints = endpointMap.values
                        .filter { it.status == NearbyVirtualNetwork.EndpointStatus.CONNECTED }
                        .distinctBy { it.endpointId }

                    _endpoints.value = connectedEndpoints

                    logger.invoke(
                        Log.INFO,
                        "Connected endpoints: ${connectedEndpoints.joinToString { "${it.endpointId}: ${it.ipAddress?.hostAddress}" }}"
                    )
                }
        }
    }

    private fun observeChatMessages() {
        viewModelScope.launch {
            chatServer.chatMessages.collect { messages ->
                _messages.value = messages
                messages.lastOrNull()?.let { lastMessage ->
                    logger.invoke(Log.INFO, "Received message from ${lastMessage.sender}: ${lastMessage.message}")
                }
            }
        }
    }

    fun sendMessage(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    chatServer.sendMessage(trimmedMessage)
                    logger.invoke(Log.INFO, "Sent message: $trimmedMessage")
                } catch (e: Exception) {
                    logger.invoke(Log.ERROR, "Failed to send message: ${e.message}", e)
                }
            }
        } else {
            logger.invoke(Log.INFO, "Empty message not sent.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopNetwork()
    }

    companion object {
        private const val BROADCAST_IP_ADDRESS = "255.255.255.255"
        private const val NETWORK_SERVICE_ID = "com.ustadmobile.meshrabiya.test"
        private const val DEVICE_NAME_SUFFIX_LIMIT = 1000
    }
}
