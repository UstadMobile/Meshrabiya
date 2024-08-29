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
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.random.Random


class NearbyVirtualNetwork(
    context: Context,
    private val name: String,
    private val serviceId: String,
    private val virtualIpAddress: String,
    private val broadcastAddress: String,
    private val strategy: Strategy = Strategy.P2P_CLUSTER,
    private val logger: MNetLogger
) {

    data class EndpointInfo(
        val endpointId: String,
        var status: EndpointStatus = EndpointStatus.DISCONNECTED,
        var ipAddress: String? = null,
        var isOutgoing: Boolean = false
    )

    enum class EndpointStatus {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
    }
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val desiredOutgoingConnections = 3

    private val _endpointStatusFlow = MutableStateFlow<Map<String, EndpointInfo>>(emptyMap())
    val endpointStatusFlow = _endpointStatusFlow.asStateFlow()


    init {
        observeEndpointStatusFlow()
    }

    fun start() {
        startAdvertising()
        startDiscovery()
    }

    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _endpointStatusFlow.value = emptyMap()
        log("Stopped advertising, discovery, all connections, and cleared endpoints")
    }


    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            name, serviceId, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            log("Started advertising successfully")
        }.addOnFailureListener { e ->
            log("Failed to start advertising", e)
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            log("Started discovery successfully")
        }.addOnFailureListener { e ->
            log("Failed to start discovery", e)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            log("Connection initiated with endpoint: $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            updateEndpointStatus(endpointId, EndpointStatus.CONNECTING)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                log("Successfully connected to endpoint: $endpointId")
                updateEndpointStatus(endpointId, EndpointStatus.CONNECTED)
                sendMmcpPingPacket(endpointId)
            } else {
                log("Failed to connect to endpoint: $endpointId. Reason: ${result.status}")
                updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
            }
        }

        override fun onDisconnected(endpointId: String) {
            log("Disconnected from endpoint: $endpointId")
            updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            log("Endpoint found: $endpointId")
            addEndpoint(endpointId)
            checkAndInitiateConnection(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            log("Endpoint lost: $endpointId")
            removeEndpoint(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleBytesPayload(endpointId, payload)
                else -> log("Received unsupported payload type from: $endpointId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        }
    }

    private fun updateEndpointIpAddress(endpointId: String, ipAddress: String) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                this[endpointId]?.ipAddress = ipAddress
            }
        }
    }

    private fun updateEndpointStatus(endpointId: String, status: EndpointStatus) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                this[endpointId]?.status = status
            }
        }
    }

    private fun addEndpoint(endpointId: String) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                this[endpointId] = EndpointInfo(endpointId)
            }
        }
    }

    private fun removeEndpoint(endpointId: String) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                remove(endpointId)
            }
        }
    }

    private fun checkAndInitiateConnection(endpointId: String) {
        val endpointInfo = _endpointStatusFlow.value[endpointId]
        if (endpointInfo != null && endpointInfo.status == EndpointStatus.DISCONNECTED) {
            requestConnection(endpointId)
        }
    }

    private fun requestConnection(endpointId: String) {
        connectionsClient.requestConnection(name, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                log("Connection request sent to endpoint: $endpointId")
                updateEndpointStatus(endpointId, EndpointStatus.CONNECTING)
                _endpointStatusFlow.update { currentMap ->
                    currentMap.toMutableMap().apply {
                        this[endpointId]?.isOutgoing = true
                    }
                }
            }
            .addOnFailureListener { e ->
                log("Failed to request connection to endpoint: $endpointId", e)
                updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
            }
    }

    private fun log(message: String, exception: Exception? = null) {
        val prefix = "[NearbyVirtualNetwork:$name] "
        if (exception != null) {
            logger(Log.ERROR, "$prefix$message", exception)
        } else {
            logger(Log.DEBUG, "$prefix$message")
        }
    }

    private fun observeEndpointStatusFlow() {
        CoroutineScope(Dispatchers.Default).launch {
            endpointStatusFlow.collect { endpointMap ->
                checkAndInitiateConnections(endpointMap)
            }
        }
    }

    private fun checkAndInitiateConnections(endpointMap: Map<String, EndpointInfo>) {
        val connectedOutgoing = endpointMap.values.count { it.status == EndpointStatus.CONNECTED && it.isOutgoing }
        val disconnectedEndpoints = endpointMap.values.filter { it.status == EndpointStatus.DISCONNECTED }

        log( "Connected outgoing: $connectedOutgoing, Disconnected endpoints: ${disconnectedEndpoints.size}")

        if (connectedOutgoing < desiredOutgoingConnections && disconnectedEndpoints.isNotEmpty()) {
            val endpointsToConnect = disconnectedEndpoints.take(desiredOutgoingConnections - connectedOutgoing)
            log( "Initiating connection to ${endpointsToConnect.size} endpoints")
            updateMultipleEndpointsStatus(endpointsToConnect.map { it.endpointId }, EndpointStatus.CONNECTING)
            endpointsToConnect.forEach { requestConnection(it.endpointId) }
        }
    }

    private fun updateMultipleEndpointsStatus(endpointIds: List<String>, status: EndpointStatus) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                endpointIds.forEach { endpointId ->
                    this[endpointId]?.status = status
                    this[endpointId]?.isOutgoing = true
                }
            }
        }
    }

    private fun sendMmcpPingPacket(endpointId: String) {
        val mmcpPing = MmcpPing(Random.nextInt())
        val pingBytes = mmcpPing.toBytes()

        val header = VirtualPacketHeader(
            fromAddr = InetAddress.getByName(virtualIpAddress).address.ip4AddressToInt(),
            toAddr = InetAddress.getByName(broadcastAddress).address.ip4AddressToInt(),
            fromPort = 0,
            toPort = 0,
            lastHopAddr = InetAddress.getByName(virtualIpAddress).address.ip4AddressToInt(),
            payloadSize = pingBytes.size,
            hopCount = 0,
            maxHops = 1
        )

        val totalSize = VirtualPacketHeader.HEADER_SIZE + pingBytes.size
        val data = ByteArray(totalSize)
        header.toBytes(data, 0)
        System.arraycopy(pingBytes, 0, data, VirtualPacketHeader.HEADER_SIZE, pingBytes.size)

        val virtualPacket = VirtualPacket.fromData(data, 0)
        val payload = Payload.fromBytes(virtualPacket.data)

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log("Sent MMCP Ping to $endpointId") }
            .addOnFailureListener { e -> log("Failed to send MMCP Ping to $endpointId", e) }
    }

    private fun sendMmcpPongPacket(endpointId: String, replyToMessageId: Int) {
        val mmcpPong = MmcpPong(Random.nextInt(), replyToMessageId)
        val pongBytes = mmcpPong.toBytes()

        val header = VirtualPacketHeader(
            fromAddr = InetAddress.getByName(virtualIpAddress).address.ip4AddressToInt(),
            toAddr = InetAddress.getByName(broadcastAddress).address.ip4AddressToInt(),
            fromPort = 0,
            toPort = 0,
            lastHopAddr = InetAddress.getByName(virtualIpAddress).address.ip4AddressToInt(),
            payloadSize = pongBytes.size,
            hopCount = 0,
            maxHops = 1
        )

        val totalSize = VirtualPacketHeader.HEADER_SIZE + pongBytes.size
        val data = ByteArray(totalSize)
        header.toBytes(data, 0)
        System.arraycopy(pongBytes, 0, data, VirtualPacketHeader.HEADER_SIZE, pongBytes.size)

        val virtualPacket = VirtualPacket.fromData(data, 0)
        val payload = Payload.fromBytes(virtualPacket.data)

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log("Sent MMCP Pong to $endpointId") }
            .addOnFailureListener { e -> log("Failed to send MMCP Pong to $endpointId", e) }
    }

    private fun Int.toByteArray(): ByteArray {
        return ByteBuffer.allocate(4).putInt(this).array()
    }

    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes()
        if (bytes == null || bytes.isEmpty()) {
            log("Received invalid payload from endpoint: $endpointId")
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
            val fromAddress = InetAddress.getByAddress(virtualPacket.header.fromAddr.toByteArray()).hostAddress

            when (mmcpMessage) {
                is MmcpPing -> handleMmcpPing(endpointId, mmcpMessage, fromAddress)
                is MmcpPong -> handleMmcpPong(endpointId, mmcpMessage, fromAddress)
                else -> log("Received unknown MMCP message from $endpointId")
            }
        } catch (e: Exception) {
            log("Error parsing payload from $endpointId", e)
        }
    }

    private fun handleMmcpPing(endpointId: String, mmcpPing: MmcpPing, fromAddress: String) {
        log( "Received MMCP Ping from $endpointId with IP $fromAddress")
        updateEndpointIpAddress(endpointId, fromAddress)
        sendMmcpPongPacket(endpointId, mmcpPing.messageId)
    }

    private fun handleMmcpPong(endpointId: String, mmcpPong: MmcpPong, fromAddress: String) {
        log( "Received MMCP Pong from $endpointId with IP $fromAddress")
        updateEndpointIpAddress(endpointId, fromAddress)
    }


}


