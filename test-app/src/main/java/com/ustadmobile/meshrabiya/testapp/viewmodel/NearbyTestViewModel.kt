package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.server.ChatMessage
import com.ustadmobile.meshrabiya.testapp.server.ChatServer
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.random.Random


data class NearbyTestUiState(
    val isNetworkRunning: Boolean = false,
    val endpoints: List<NearbyVirtualNetwork.EndpointInfo> = emptyList(),
    val logs: List<String> = emptyList(),
    val messages: List<ChatMessage> = emptyList()
)

class NearbyTestViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NearbyTestUiState())
    val uiState: StateFlow<NearbyTestUiState> = _uiState.asStateFlow()

    private lateinit var nearbyNetwork: NearbyVirtualNetwork
    private lateinit var chatServer: ChatServer
    private var isNetworkInitialized = false

    private val logger = object : MNetLogger() {
        override fun invoke(priority: Int, message: String, exception: Exception?) {
            val logMessage = "${MNetLogger.priorityLabel(priority)}: $message"
            viewModelScope.launch {
                _uiState.update { currentState ->
                    currentState.copy(logs = currentState.logs + logMessage)
                }
            }
            if (exception != null) {
                Log.e(TAG_NEARBY_TEST, message, exception)
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
        val virtualIpAddress = "$IP_PREFIX${Random.nextInt(IP_START, IP_END)}.${Random.nextInt(IP_START, IP_END)}"

        nearbyNetwork = NearbyVirtualNetwork(
            context = getApplication(),
            name = "Device-${Random.nextInt(DEVICE_NAME_SUFFIX_LIMIT)}",
            serviceId = NETWORK_SERVICE_ID,
            virtualIpAddress = ipToInt(virtualIpAddress),
            broadcastAddress = ipToInt(BROADCAST_IP_ADDRESS),
            logger = logger
        ) { packet ->
            handleIncomingPacket(packet)
        }

        chatServer = ChatServer(nearbyNetwork, logger)
        observeChatMessages()
    }

    private fun handleIncomingPacket(packet: VirtualPacket) {
        logger.invoke(Log.DEBUG, "Received virtual packet: ${packet.header}")

        if (packet.header.toPort == ChatServer.UDP_PORT) {
            try {
                val messageData = ByteArray(packet.header.payloadSize).apply {
                    System.arraycopy(packet.data, packet.payloadOffset, this, 0, packet.header.payloadSize)
                }
                // Pass the data to chat server for processing
                chatServer.processReceivedMessage(messageData)
            } catch (e: Exception) {
                logger.invoke(Log.ERROR, "Error processing chat packet", e)
            }
        }
    }

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
                _uiState.update { it.copy(isNetworkRunning = true) }
                isNetworkInitialized = true
                observeEndpoints()
                logger.invoke(Log.INFO, "Network started successfully with IP: ${nearbyNetwork.virtualAddress.hostAddress}")
            } catch (e: IllegalStateException) {
                logger.invoke(Log.ERROR, "Failed to start network: ${e.message}", e)
                retryStartNetwork()
            }
        }
    }

    private fun retryStartNetwork() {
        viewModelScope.launch {
            delay(RETRY_DELAY)
            logger.invoke(Log.INFO, "Retrying to start network...")
            startNetwork()
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
                logger.invoke(Log.INFO, "Network stopped successfully")
            } catch (e: Exception) {
                logger.invoke(Log.ERROR, "Failed to stop network: ${e.message}", e)
            }
        }
    }

    private fun resetState() {
        _uiState.update {
            NearbyTestUiState()
        }
        isNetworkInitialized = false
    }

    private fun observeEndpoints() {
        viewModelScope.launch {
            nearbyNetwork.endpointStatusFlow
                .collect { endpointMap ->
                    val connectedEndpoints = endpointMap.values
                        .filter { it.status == NearbyVirtualNetwork.EndpointStatus.CONNECTED }
                        .distinctBy { it.endpointId }

                    _uiState.update { it.copy(endpoints = connectedEndpoints) }

                    logger.invoke(
                        Log.INFO,
                        "Connected endpoints: ${connectedEndpoints.joinToString {
                            "${it.endpointId}: ${it.ipAddress?.hostAddress}"
                        }}"
                    )
                }
        }
    }

    private fun observeChatMessages() {
        viewModelScope.launch {
            chatServer.chatMessages
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                    messages.lastOrNull()?.let { lastMessage ->
                        logger.invoke(
                            Log.INFO,
                            "Received message from ${lastMessage.sender}: ${lastMessage.message}"
                        )
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
        private const val TAG_NEARBY_TEST = "NearbyTestViewModel"
        private const val BROADCAST_IP_ADDRESS = "255.255.255.255"
        private const val NETWORK_SERVICE_ID = "com.ustadmobile.meshrabiya.test"
        private const val DEVICE_NAME_SUFFIX_LIMIT = 1000
        private const val RETRY_DELAY = 5000L
        private const val IP_PREFIX = "169.254."
        private const val IP_START = 1
        private const val IP_END = 255
    }
}