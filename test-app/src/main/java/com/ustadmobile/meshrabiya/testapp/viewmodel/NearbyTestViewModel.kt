package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.server.ChatMessage
import com.ustadmobile.meshrabiya.testapp.server.ChatServer
import com.ustadmobile.meshrabiya.testapp.server.MeshrabiyaDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import kotlinx.coroutines.CoroutineExceptionHandler
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


class NearbyTestViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NearbyTestUiState())
    val uiState: StateFlow<NearbyTestUiState> = _uiState.asStateFlow()

    private var nearbyNetwork: NearbyVirtualNetwork? = null
    private var chatServer: ChatServer? = null
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
        try {
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

            chatServer = nearbyNetwork?.let { network ->
                ChatServer(network, logger).also { server ->
                    observeChatMessages(server)
                }
            }

            logger(Log.INFO, "Network initialized with IP: $virtualIpAddress")
        } catch (e: Exception) {
            logger(Log.ERROR, "Failed to initialize network", e)
        }
    }

    private fun observeEndpoints() {
        viewModelScope.launch {
            try {
                nearbyNetwork?.endpointStatusFlow?.collect { endpointMap ->
                    val connectedEndpoints = endpointMap.values
                        .filter { it.status == NearbyVirtualNetwork.EndpointStatus.CONNECTED }
                        .distinctBy { it.endpointId }

                    _uiState.update { it.copy(endpoints = connectedEndpoints) }

                    logger(
                        Log.INFO,
                        "Connected endpoints: ${connectedEndpoints.joinToString { "${it.endpointId}: ${it.ipAddress?.hostAddress}" }}"
                    )
                }
            } catch (e: Exception) {
                logger(Log.ERROR, "Error observing endpoints", e)
            }
        }
    }

    private fun observeChatMessages(server: ChatServer) {
        viewModelScope.launch {
            try {
                server.chatMessages.collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                    messages.lastOrNull()?.let { lastMessage ->
                        logger(
                            Log.INFO,
                            "Received message from ${lastMessage.sender}: ${lastMessage.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                logger(Log.ERROR, "Error observing chat messages", e)
            }
        }
    }

    private fun handleIncomingPacket(packet: VirtualPacket) {
        try {
            logger(Log.DEBUG, "Received virtual packet: ${packet.header}")

            if (packet.header.toPort == ChatServer.UDP_PORT) {
                val messageData = ByteArray(packet.header.payloadSize).apply {
                    System.arraycopy(packet.data, packet.payloadOffset, this, 0, packet.header.payloadSize)
                }
                chatServer?.processReceivedMessage(messageData)
            }
        } catch (e: Exception) {
            logger(Log.ERROR, "Error handling incoming packet", e)
        }
    }

    fun startNetwork() {
        if (isNetworkInitialized) {
            logger(Log.INFO, "Network is already running")
            return
        }

        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            retryStartNetwork()
        }) {
            try {
                nearbyNetwork?.start()
                _uiState.update { it.copy(isNetworkRunning = true) }
                isNetworkInitialized = true
                observeEndpoints()
                logger(Log.INFO, "Network started successfully with IP: ${nearbyNetwork?.virtualAddress?.hostAddress}")
            } catch (e: Exception) {
                logger(Log.ERROR, "Failed to start network", e)
                retryStartNetwork()
            }
        }
    }

    private fun retryStartNetwork() {
        viewModelScope.launch {
            delay(RETRY_DELAY)
            logger(Log.INFO, "Retrying to start network...")
            startNetwork()
        }
    }

    fun stopNetwork() {
        if (!isNetworkInitialized) {
            logger(Log.INFO, "Network is not running")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatServer?.close()
                nearbyNetwork?.close()
                resetState()
                logger(Log.INFO, "Network stopped successfully")
            } catch (e: Exception) {
                logger(Log.ERROR, "Failed to stop network", e)
            }
        }
    }

    private fun resetState() {
        _uiState.update { NearbyTestUiState() }
        isNetworkInitialized = false
    }

    fun sendMessage(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) {
            logger(Log.INFO, "Empty message not sent")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatServer?.sendMessage(trimmedMessage)
                logger(Log.INFO, "Sent message: $trimmedMessage")
            } catch (e: Exception) {
                logger(Log.ERROR, "Failed to send message", e)
            }
        }
    }

    private fun ipToInt(ipAddress: String): Int {
        return try {
            val inetAddress = InetAddress.getByName(ipAddress)
            ByteBuffer.wrap(inetAddress.address).int
        } catch (e: Exception) {
            logger(Log.ERROR, "Failed to convert IP address", e)
            throw e
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            try {
                stopNetwork()
            } catch (e: Exception) {
                logger(Log.ERROR, "Error during ViewModel cleanup", e)
            }
        }
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

data class NearbyTestUiState(
    val isNetworkRunning: Boolean = false,
    val endpoints: List<NearbyVirtualNetwork.EndpointInfo> = emptyList(),
    val logs: List<String> = emptyList(),
    val messages: List<ChatMessage> = emptyList()
)