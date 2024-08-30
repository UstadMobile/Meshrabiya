package com.meshrabiya.lib_nearby.nearby


import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress
import kotlin.random.Random

class NearbyVirtualNetwork(
    context: Context,
    private val name: String,
    private val serviceId: String,
    private val virtualIpAddress: Int,
    private val broadcastAddress: Int,
    private val strategy: Strategy = Strategy.P2P_CLUSTER,
    private val logger: MNetLogger
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val desiredOutgoingConnections = 3
    private val scope = CoroutineScope(Dispatchers.Default)

    data class EndpointInfo(
        val endpointId: String,
        val status: EndpointStatus,
        val ipAddress: String?,
        val isOutgoing: Boolean
    )

    enum class EndpointStatus {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
    }

    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARNING, ERROR
    }

    private val _endpointStatusFlow = MutableStateFlow<Map<String, EndpointInfo>>(emptyMap())
    val endpointStatusFlow = _endpointStatusFlow.asStateFlow()

    init {
        observeEndpointStatusFlow()
        startAdvertising()
        startDiscovery()
    }

    fun start() {
        startAdvertising()
        startDiscovery()
    }

    fun close() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _endpointStatusFlow.value = emptyMap()
        scope.cancel()
        log(LogLevel.INFO, "Stopped advertising, discovery, all connections, and cleared endpoints")
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            name, serviceId, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            log(LogLevel.INFO, "Started advertising successfully")
        }.addOnFailureListener { e ->
            log(LogLevel.ERROR, "Failed to start advertising", e)
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            log(LogLevel.INFO, "Started discovery successfully")
        }.addOnFailureListener { e ->
            log(LogLevel.ERROR, "Failed to start discovery", e)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            log(LogLevel.INFO, "Connection initiated with endpoint: $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            updateEndpointStatus(endpointId, EndpointStatus.CONNECTING)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                log(LogLevel.INFO, "Successfully connected to endpoint: $endpointId")
                updateEndpointStatus(endpointId, EndpointStatus.CONNECTED)
                sendMmcpPingPacket(endpointId)
            } else {
                log(LogLevel.ERROR, "Failed to connect to endpoint: $endpointId. Reason: ${result.status}")
                updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
            }
        }

        override fun onDisconnected(endpointId: String) {
            log(LogLevel.INFO, "Disconnected from endpoint: $endpointId")
            updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            log(LogLevel.DEBUG, "Endpoint found: $endpointId")
            _endpointStatusFlow.update { currentMap ->
                currentMap + (endpointId to EndpointInfo(endpointId, EndpointStatus.DISCONNECTED, null, false))
            }
        }

        override fun onEndpointLost(endpointId: String) {
            log(LogLevel.DEBUG, "Endpoint lost: $endpointId")
            _endpointStatusFlow.update { currentMap ->
                currentMap - endpointId
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleBytesPayload(endpointId, payload)
                Payload.Type.STREAM -> handleStreamPayload(endpointId, payload)
                else -> log(LogLevel.WARNING, "Received unsupported payload type from: $endpointId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            log(LogLevel.DEBUG, "Payload transfer update for $endpointId: ${update.status}")
        }
    }

    private fun observeEndpointStatusFlow() {
        scope.launch {
            endpointStatusFlow.collect { endpointMap ->
                val connectedOutgoing = endpointMap.values.count { it.status == EndpointStatus.CONNECTED && it.isOutgoing }
                val disconnectedEndpoints = endpointMap.values.filter { it.status == EndpointStatus.DISCONNECTED }

                log(LogLevel.DEBUG, "Connected outgoing: $connectedOutgoing, Disconnected endpoints: ${disconnectedEndpoints.size}")

                if (connectedOutgoing < desiredOutgoingConnections && disconnectedEndpoints.isNotEmpty()) {
                    val endpointsToConnect = disconnectedEndpoints.take(desiredOutgoingConnections - connectedOutgoing)
                    log(LogLevel.INFO, "Initiating connection to ${endpointsToConnect.size} endpoints")
                    endpointsToConnect.forEach { requestConnection(it.endpointId) }
                }
            }
        }
    }

    private fun requestConnection(endpointId: String) {
        connectionsClient.requestConnection(name, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                log(LogLevel.INFO, "Connection request sent to endpoint: $endpointId")
                _endpointStatusFlow.update { currentMap ->
                    currentMap[endpointId]?.let { info ->
                        currentMap + (endpointId to info.copy(status = EndpointStatus.CONNECTING, isOutgoing = true))
                    } ?: currentMap
                }
            }
            .addOnFailureListener { e ->
                log(LogLevel.ERROR, "Failed to request connection to endpoint: $endpointId", e)
                updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
            }
    }

    private fun sendMmcpPingPacket(endpointId: String) {
        val mmcpPing = MmcpPing(Random.nextInt())
        val virtualPacket = mmcpPing.toVirtualPacket(virtualIpAddress, broadcastAddress)
        val payload = Payload.fromBytes(virtualPacket.data)

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log(LogLevel.DEBUG, "Sent MMCP Ping to $endpointId") }
            .addOnFailureListener { e -> log(LogLevel.ERROR, "Failed to send MMCP Ping to $endpointId", e) }
    }

    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes()
        if (bytes == null) {
            log(LogLevel.WARNING, "Received null payload from endpoint: $endpointId")
            return
        }

        try {
            val virtualPacket = VirtualPacket.fromData(bytes, 0)
            val mmcpMessage = MmcpMessage.fromBytes(
                bytes.copyOfRange(
                    virtualPacket.payloadOffset,
                    virtualPacket.payloadOffset + virtualPacket.header.payloadSize
                )
            )
            val fromAddress = InetAddress.getByAddress(virtualPacket.header.fromAddr.addressToByteArray()).hostAddress

            when (mmcpMessage) {
                is MmcpPing -> handleMmcpPing(endpointId, mmcpMessage, fromAddress ?: "Unknown")
                is MmcpPong -> handleMmcpPong(endpointId, mmcpMessage, fromAddress ?: "Unknown")
                else -> log(LogLevel.WARNING, "Received unknown MMCP message from $endpointId")
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error parsing payload from $endpointId", e)
        }
    }

    private fun handleStreamPayload(endpointId: String, payload: Payload) {
        val inputStream = payload.asStream()?.asInputStream()
        if (inputStream != null) {
            log(LogLevel.DEBUG, "Received stream payload from $endpointId")
            // Process the stream here
        } else {
            log(LogLevel.WARNING, "Received invalid stream payload from $endpointId")
        }
    }

    private fun handleMmcpPing(endpointId: String, mmcpPing: MmcpPing, fromAddress: String) {
        log(LogLevel.DEBUG, "Received MMCP Ping from $endpointId with IP $fromAddress")
        updateEndpointIpAddress(endpointId, fromAddress)
        sendMmcpPongPacket(endpointId, mmcpPing.messageId)
    }

    private fun handleMmcpPong(endpointId: String, mmcpPong: MmcpPong, fromAddress: String) {
        log(LogLevel.DEBUG, "Received MMCP Pong from $endpointId with IP $fromAddress")
        updateEndpointIpAddress(endpointId, fromAddress)
    }

    private fun sendMmcpPongPacket(endpointId: String, replyToMessageId: Int) {
        val mmcpPong = MmcpPong(Random.nextInt(), replyToMessageId)
        val virtualPacket = mmcpPong.toVirtualPacket(virtualIpAddress, broadcastAddress)
        val payload = Payload.fromBytes(virtualPacket.data)

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log(LogLevel.DEBUG, "Sent MMCP Pong to $endpointId") }
            .addOnFailureListener { e -> log(LogLevel.ERROR, "Failed to send MMCP Pong to $endpointId", e) }
    }

    private fun updateEndpointStatus(endpointId: String, status: EndpointStatus) {
        _endpointStatusFlow.update { currentMap ->
            currentMap[endpointId]?.let { info ->
                currentMap + (endpointId to info.copy(status = status))
            } ?: currentMap
        }
    }

    private fun updateEndpointIpAddress(endpointId: String, ipAddress: String) {
        _endpointStatusFlow.update { currentMap ->
            currentMap[endpointId]?.let { info ->
                currentMap + (endpointId to info.copy(ipAddress = ipAddress))
            } ?: currentMap
        }
    }

    private fun log(level: LogLevel, message: String, exception: Exception? = null) {
        val prefix = "[NearbyVirtualNetwork:$name] "
        when (level) {
            LogLevel.VERBOSE -> logger(Log.VERBOSE, "$prefix$message", exception)
            LogLevel.DEBUG -> logger(Log.DEBUG, "$prefix$message", exception)
            LogLevel.INFO -> logger(Log.INFO, "$prefix$message", exception)
            LogLevel.WARNING -> logger(Log.WARN, "$prefix$message", exception)
            LogLevel.ERROR -> logger(Log.ERROR, "$prefix$message", exception)
        }
    }
}