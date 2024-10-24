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
import com.ustadmobile.meshrabiya.ext.asInetAddress
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.netinterface.VSocket
import com.ustadmobile.meshrabiya.vnet.netinterface.VirtualNetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random


class NearbyVirtualNetwork(
    context: Context,
    private val name: String,
    private val serviceId: String,
    private val virtualIpAddress: Int,
    private val broadcastAddress: Int,
    private val strategy: Strategy = Strategy.P2P_CLUSTER,
    override val logger: MNetLogger,
    private val onPacketReceived: (VirtualPacket) -> Unit
) : VirtualNetworkInterface {

    override val virtualAddress: InetAddress get() = InetAddress.getByAddress(virtualIpAddress.addressToByteArray())

    private val streamReplies = ConcurrentHashMap<Int, CompletableFuture<InputStream>>()
    private val endpointIpMap = ConcurrentHashMap<String, InetAddress>()

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val desiredOutgoingConnections = 3
    private val scope = CoroutineScope(Dispatchers.Default)
    private val isClosed = AtomicBoolean(false)

    data class EndpointInfo(
        val endpointId: String,
        val status: EndpointStatus,
        val ipAddress: InetAddress?,
        val isOutgoing: Boolean
    )

    enum class EndpointStatus {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
    }

    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARNING, ERROR
    }



    private val _endpointStatusFlow = MutableStateFlow<MutableMap<String, EndpointInfo>>(mutableMapOf())
    val endpointStatusFlow = _endpointStatusFlow.asStateFlow()


    init {
        observeEndpointStatusFlow()
    }

    fun start() {
        checkClosed()
        startAdvertising()
        startDiscovery()
    }


    override fun send(virtualPacket: VirtualPacket, nextHopAddress: InetAddress) {
        val connectedEndpoints = _endpointStatusFlow.value.filter { it.value.status == EndpointStatus.CONNECTED }

        //If the nextHopAddress is the broadcast address, send to all known points.
        //Else, send only to the related endpoint; if not found, throw exception.
        if (nextHopAddress.address.contentEquals(InetAddress.getByAddress(broadcastAddress.addressToByteArray()).address)) {
            log(LogLevel.INFO, "Broadcasting packet to all connected endpoints")
            connectedEndpoints.forEach { (endpointId, _) ->
                sendPacketToEndpoint(endpointId, virtualPacket)
            }
        }
        else {
            val matchingEndpoint = connectedEndpoints.entries.find { (_, info) ->
                info.ipAddress?.address?.contentEquals(nextHopAddress.address) == true
            }

            if (matchingEndpoint != null) {
                log(LogLevel.INFO, "Sending packet to specific endpoint: ${matchingEndpoint.key}")
                sendPacketToEndpoint(matchingEndpoint.key, virtualPacket)
            } else {
                throw IllegalArgumentException("No connected endpoint found for IP address: $nextHopAddress")
            }
        }
    }

    private fun sendPacketToEndpoint(endpointId: String, virtualPacket: VirtualPacket) {
        val payload = Payload.fromBytes(virtualPacket.data)
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log(LogLevel.INFO, "Virtual packet sent to $endpointId") }
            .addOnFailureListener { e -> log(LogLevel.ERROR, "Failed to send virtual packet to $endpointId", e) }
    }

    override fun connectSocket(nextHopAddress: InetAddress, destAddress: InetAddress, destPort: Int): VSocket {
        log(LogLevel.INFO, "Connecting to socket: $nextHopAddress -> $destAddress:$destPort")
        return TODO("Provide the return value")
    }

    override fun close() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            _endpointStatusFlow.value.clear()
            scope.cancel()
            streamReplies.forEach { (_, future) -> future.cancel(true) }
            streamReplies.clear()
            endpointIpMap.clear()
            log(LogLevel.INFO, "Network is closed and all operations have been stopped.")
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error during network closure", e)
        }
    }


    private fun startAdvertising() {
        checkClosed()
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
        checkClosed()
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
            checkClosed()
            log(LogLevel.INFO, "Connection initiated with endpoint: $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            updateEndpointStatus(endpointId, EndpointStatus.CONNECTING)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            checkClosed()
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
            checkClosed()
            log(LogLevel.INFO, "Disconnected from endpoint: $endpointId")
            updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            checkClosed()
            log(LogLevel.DEBUG, "Endpoint found: $endpointId")
            val updatedMap = ConcurrentHashMap<String, EndpointInfo>(_endpointStatusFlow.value)
            updatedMap[endpointId] = EndpointInfo(endpointId, EndpointStatus.DISCONNECTED, null, false)
            _endpointStatusFlow.value = updatedMap
        }

        override fun onEndpointLost(endpointId: String) {
            checkClosed()
            log(LogLevel.DEBUG, "Endpoint lost: $endpointId")
            val updatedMap = ConcurrentHashMap<String, EndpointInfo>(_endpointStatusFlow.value)
            updatedMap.remove(endpointId)
            _endpointStatusFlow.value = updatedMap
        }
    }

     val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            checkClosed()
            when (payload.type) {
                Payload.Type.BYTES -> handleBytesPayload(endpointId, payload)
                Payload.Type.STREAM -> handleStreamPayload(endpointId, payload)
                else -> log(LogLevel.WARNING, "Received unsupported payload type from: $endpointId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            checkClosed()
            log(LogLevel.DEBUG, "Payload transfer update for $endpointId: ${update.status}")
        }
    }

    private fun observeEndpointStatusFlow() {
        scope.launch {
            endpointStatusFlow.collect { endpointMap ->
                val connectedOutgoing = endpointMap.values.count { it.status == EndpointStatus.CONNECTED && it.isOutgoing }
                val disconnectedEndpoints = endpointMap.values.filter { it.status == EndpointStatus.DISCONNECTED }

                if (connectedOutgoing < desiredOutgoingConnections && disconnectedEndpoints.isNotEmpty()) {
                    val endpointsToConnect = disconnectedEndpoints
                        .take(desiredOutgoingConnections - connectedOutgoing)

                    log(LogLevel.INFO, "Initiating connection to ${endpointsToConnect.size} endpoints")

                    val updatedMap = ConcurrentHashMap(endpointMap)

                    endpointsToConnect.forEach { endpoint ->
                        updatedMap[endpoint.endpointId] = endpoint.copy(status = EndpointStatus.CONNECTING, isOutgoing = true)
                        requestConnection(endpoint.endpointId)
                    }

                    _endpointStatusFlow.value = updatedMap
                }
            }
        }
    }

    private fun requestConnection(endpointId: String) {
        checkClosed()
        connectionsClient.requestConnection(name, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                log(LogLevel.INFO, "Connection request sent to endpoint: $endpointId")
            }
            .addOnFailureListener { e ->
                log(LogLevel.ERROR, "Failed to request connection to endpoint: $endpointId", e)
                updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
            }
    }

    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        checkClosed()

        // Payload should be converted to VirtualPacket always
        val bytes = payload.asBytes()
        if (bytes == null) {
            log(LogLevel.WARNING, "Received null payload from endpoint: $endpointId")
            return
        }

        val virtualPacket: VirtualPacket = try {
            VirtualPacket.fromData(bytes, 0)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to convert payload to VirtualPacket from endpoint: $endpointId", e)
            return
        }

        // Update the mapping of endpointId to virtual IP address
        val lastHopAddress = virtualPacket.header.toAddr // toAddr holds the last hop address
        if (lastHopAddress != null) {
            endpointIpMap[endpointId] = lastHopAddress.asInetAddress()
            log(LogLevel.DEBUG, "Updated IP mapping for endpoint $endpointId to $lastHopAddress")
        } else {
            log(LogLevel.WARNING, "Last hop address is null for the received packet from endpoint $endpointId")
        }

        // Invoke the onPacketReceived callback with the converted VirtualPacket
        try {
            onPacketReceived(virtualPacket)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error processing VirtualPacket from $endpointId", e)
        }

        try {
            val message = String(bytes, Charsets.UTF_8)
            log(LogLevel.INFO, "Received message from $endpointId: $message")
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error parsing payload from $endpointId", e)
        }
    }

    private fun handleStreamPayload(endpointId: String, payload: Payload) {
        checkClosed()
        payload.asStream()?.asInputStream()?.use { inputStream ->
            val header = inputStream.readNearbyStreamHeader()

            if (header.isReply) {
                streamReplies[header.streamId]?.complete(inputStream)
            } else {
                handleIncomingStream(endpointId, header, inputStream)
            }
        }
    }

    private fun handleIncomingStream(endpointId: String, header: NearbyStreamHeader, inputStream: InputStream) {
        log(LogLevel.INFO, "Received new stream from $endpointId with streamId: ${header.streamId}, fromAddress: ${header.fromAddress}, toAddress: ${header.toAddress}")

    }
    private fun sendMmcpPingPacket(endpointId: String) {
        checkClosed()
        val mmcpPing = MmcpPing(Random.nextInt())
        val virtualPacket = mmcpPing.toVirtualPacket(virtualIpAddress, broadcastAddress)
        val payload = Payload.fromBytes(virtualPacket.data)

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log(LogLevel.DEBUG, "Sent MMCP Ping to $endpointId") }
            .addOnFailureListener { e -> log(LogLevel.ERROR, "Failed to send MMCP Ping to $endpointId", e) }
    }

    private fun sendMmcpPongPacket(endpointId: String, replyToMessageId: Int) {
        checkClosed()
        val mmcpPong = MmcpPong(Random.nextInt(), replyToMessageId)
        val virtualPacket = mmcpPong.toVirtualPacket(virtualIpAddress, broadcastAddress)
        val payload = Payload.fromBytes(virtualPacket.data)

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log(LogLevel.DEBUG, "Sent MMCP Pong to $endpointId") }
            .addOnFailureListener { e -> log(LogLevel.ERROR, "Failed to send MMCP Pong to $endpointId", e) }
    }

    private fun updateEndpointStatus(endpointId: String, status: EndpointStatus) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                this[endpointId] = this[endpointId]?.copy(status = status)
                    ?: EndpointInfo(endpointId, status, null, false)
            }
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

    private fun checkClosed() {
        if (isClosed.get()) {
            throw IllegalStateException("Network is closed")
        }
    }
}
