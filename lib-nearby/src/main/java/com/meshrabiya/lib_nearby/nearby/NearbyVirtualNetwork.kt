package com.meshrabiya.lib_nearby.nearby


import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
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
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.asInetAddress
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.netinterface.VSocket
import com.ustadmobile.meshrabiya.vnet.netinterface.VirtualNetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetAddress
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

    private val _endpointStatusFlow = MutableStateFlow<Map<String, EndpointInfo>>(mutableMapOf())
    val endpointStatusFlow = _endpointStatusFlow.asStateFlow()

    /**
     * Connection lifecycle manager callback.
     * Handles the complete connection lifecycle:
     * - Connection initiation
     * - Connection establishment
     * - Connection termination
     * Updates endpoint status flow to reflect current connection states.
     */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            checkClosed()

            // Check current state first
            val currentState = _endpointStatusFlow.value[endpointId]?.status
            if (currentState == EndpointStatus.CONNECTED) {
                log(LogLevel.INFO, "Already connected to endpoint: $endpointId")
                return
            }

            val endpointIp = try {
                val parts = connectionInfo.endpointName.split("|")
                if (parts.size > 1) InetAddress.getByName(parts[1]) else null
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to parse IP from endpoint name", e)
                null
            }

            log(LogLevel.INFO, "Connection initiated with endpoint: $endpointId (IP: ${endpointIp?.hostAddress})")
            connectionsClient.acceptConnection(endpointId, payloadCallback)

            _endpointStatusFlow.update { currentMap ->
                currentMap.toMutableMap().apply {
                    val existingInfo = this[endpointId]
                    this[endpointId] = existingInfo?.copy(
                        status = EndpointStatus.CONNECTING,
                        ipAddress = endpointIp ?: existingInfo.ipAddress
                    ) ?: EndpointInfo(
                        endpointId = endpointId,
                        status = EndpointStatus.CONNECTING,
                        ipAddress = endpointIp,
                        isOutgoing = false
                    )
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            checkClosed()
            if (result.status.isSuccess) {
                log(LogLevel.INFO, "Connection successful with endpoint: $endpointId")
                _endpointStatusFlow.update { currentMap ->
                    currentMap.toMutableMap().apply {
                        val current = this[endpointId]
                        if (current != null) {
                            this[endpointId] = current.copy(
                                status = EndpointStatus.CONNECTED,
                                ipAddress = current.ipAddress  // Preserve IP if exists
                            )
                        }
                    }
                }
                // Send ping immediately after successful connection This will help establish IP mapping on both sides
                sendMmcpPingPacket(endpointId)
            } else {
                log(LogLevel.ERROR, "Connection failed with endpoint: $endpointId. Status: ${result.status}")
                _endpointStatusFlow.update { currentMap ->
                    currentMap.toMutableMap().apply {
                        remove(endpointId)
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            checkClosed()
            log(LogLevel.INFO, "Disconnected from endpoint: $endpointId")
            _endpointStatusFlow.update { currentMap ->
                currentMap.toMutableMap().apply {
                    remove(endpointId)
                }
            }
            log(LogLevel.DEBUG, "Current endpoints after disconnect: ${_endpointStatusFlow.value}")
        }
    }

    init {

    }

    fun start() {
        startAdvertising()
        startDiscovery()
        observeEndpointStatusFlow()
    }

    override fun close() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            _endpointStatusFlow.value = emptyMap()
            scope.cancel()
            streamReplies.forEach { (_, future) -> future.cancel(true) }
            streamReplies.clear()
            endpointIpMap.clear()
            log(LogLevel.INFO, "Network is closed and all operations have been stopped.")
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error during network closure", e)
        }
    }

    /**
     * Broadcasts device presence to nearby peers using Nearby Connections API.
     * Includes device identification (name and virtual IP) in advertising payload.
     *
     * Throws IllegalStateException if network is closed.
     */
    private fun startAdvertising() {
        checkClosed()
        // Send actual virtual IP address, not broadcast
        val deviceNameWithIP = "$name|${virtualAddress.hostAddress}"
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()

        log(LogLevel.DEBUG, "Starting advertising with device IP: ${virtualAddress.hostAddress} callback =$connectionLifecycleCallback")

        connectionsClient.startAdvertising(
            deviceNameWithIP, serviceId, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            log(LogLevel.INFO, "Started advertising with name: $deviceNameWithIP")
        }.addOnFailureListener { e ->
            log(LogLevel.ERROR, "Failed to start advertising", e)
        }
    }

    /**
     * Initiates discovery of nearby peers advertising the same service.
     * Uses configured Strategy (P2P_CLUSTER by default) for connection type.
     *
     * Throws IllegalStateException if network is closed.
     */
    private fun startDiscovery() {
        checkClosed()
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        log(LogLevel.INFO, "request start discovery: serviceId = $serviceId")
        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            log(LogLevel.INFO, "Started discovery successfully")
        }.addOnFailureListener { e ->
            log(LogLevel.ERROR, "Failed to start discovery", e)
        }
    }

    /**
     * Routes virtual packets to appropriate endpoints.
     * Handles both broadcast and unicast transmission modes.
     *
     * @param virtualPacket The packet to send
     * @param nextHopAddress Target address (broadcast or specific endpoint)
     * @throws IllegalArgumentException if target endpoint not found for unicast
     */
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

    /**
     * Transmits a virtual packet to a specific endpoint.
     * Converts packet to Nearby Connections payload format.
     *
     * @param endpointId Identifier of target endpoint
     * @param virtualPacket Packet data to transmit
     */
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



    /**
     * Endpoint discovery callback processor.
     * Manages endpoint discovery events:
     * - Processing new endpoint detection
     * - Handling endpoint loss/disconnection
     * - Maintaining endpoint status mapping
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            checkClosed()

            // Check if already connected
            val currentState = _endpointStatusFlow.value[endpointId]?.status
            if (currentState == EndpointStatus.CONNECTED || currentState == EndpointStatus.CONNECTING) {
                log(LogLevel.INFO, "Endpoint $endpointId already connected/connecting")
                return
            }

            val endpointIp = try {
                val parts = info.endpointName.split("|")
                if (parts.size > 1) InetAddress.getByName(parts[1]) else null
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to parse IP from endpoint name", e)
                null
            }

            log(LogLevel.DEBUG, "New endpoint found: $endpointId with Virtual IP: ${endpointIp?.hostAddress}")

            val updatedMap = ConcurrentHashMap<String, EndpointInfo>(_endpointStatusFlow.value)
            updatedMap[endpointId] = EndpointInfo(
                endpointId = endpointId,
                status = EndpointStatus.DISCONNECTED,
                ipAddress = endpointIp,
                isOutgoing = false
            )
            _endpointStatusFlow.value = updatedMap
        }

        override fun onEndpointLost(endpointId: String) {
            checkClosed()
            log(LogLevel.INFO, "Lost endpoint: $endpointId")
            _endpointStatusFlow.update { currentMap ->
                currentMap.toMutableMap().apply {
                    remove(endpointId)
                }
            }
        }
    }

    /**
     * Payload data processor callback.
     * Routes different payload types to appropriate handlers:
     * - BYTES: Network packets and control messages
     * - STREAM: Continuous data streams
     *
     * Maintains transfer status logging.
     */
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

    /**
     * Monitors endpoint status changes and manages connections.
     * - Tracks connected endpoint count
     * - Initiates new connections when below desired threshold
     */
    private fun observeEndpointStatusFlow() {
        scope.launch {
            endpointStatusFlow.collect { endpointMap ->
                val connectedEndpoints = endpointMap.values.count {
                    it.status == EndpointStatus.CONNECTED
                }

                // Only proceed if we need more connections
                if (connectedEndpoints < desiredOutgoingConnections) {
                    val disconnectedEndpoints = endpointMap.values.filter {
                        it.status == EndpointStatus.DISCONNECTED
                    }

                    disconnectedEndpoints.forEach { endpoint ->
                        if (endpoint.status != EndpointStatus.CONNECTED &&
                            endpoint.status != EndpointStatus.CONNECTING) {
                            log(LogLevel.INFO, "Initiating connection to: ${endpoint.endpointId}")
                            requestConnection(endpoint.endpointId)
                        }
                    }
                }
            }
        }
    }

    /**
     * Initiates connection to a specific endpoint.
     * Handles connection state management:
     * - Prevents duplicate connection attempts
     * - Updates endpoint status
     * - Processes connection failures
     *
     * @param endpointId Target endpoint identifier
     */
    private fun requestConnection(endpointId: String) {
        checkClosed()

        // Get current endpoint state
        val currentEndpoint = _endpointStatusFlow.value[endpointId]

        // Skip if already connected or connecting
        if (currentEndpoint?.status == EndpointStatus.CONNECTED ||
            currentEndpoint?.status == EndpointStatus.CONNECTING) {
            log(LogLevel.INFO, "Skip connection request - endpoint $endpointId status: ${currentEndpoint.status}")
            return
        }

        // Update status to CONNECTING
        updateEndpointStatus(endpointId, EndpointStatus.CONNECTING)

        connectionsClient.requestConnection(name, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                log(LogLevel.INFO, "Connection request sent to endpoint: $endpointId")
            }
            .addOnFailureListener { e ->
                when ((e as? ApiException)?.statusCode) {
                    8003 -> { // Already connected
                        log(LogLevel.INFO, "Endpoint $endpointId is already connected")
                        updateEndpointStatus(endpointId, EndpointStatus.CONNECTED)
                    }
                    else -> {
                        log(LogLevel.ERROR, "Failed to request connection to endpoint: $endpointId", e)
                        updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
                    }
                }
            }
    }

    /**
     * Processes received byte payloads from endpoints.
     * - Extracts virtual packet data
     * - Updates endpoint IP mappings
     * - Forwards to packet handler
     *
     * @param endpointId Source endpoint identifier
     * @param payload Received byte payload
     */
    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        checkClosed()

        val bytes = payload.asBytes() ?: run {
            log(LogLevel.WARNING, "Received null payload from endpoint: $endpointId")
            return
        }

        val virtualPacket = try {
            VirtualPacket.fromData(bytes, 0)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to convert payload to VirtualPacket from endpoint: $endpointId", e)
            return
        }

        // Get sender's virtual IP (not broadcast address)
        val fromAddr = virtualPacket.header.fromAddr?.takeIf {
            !it.addressToDotNotation().equals("255.255.255.255")
        }

        if (fromAddr != null) {
            _endpointStatusFlow.update { currentMap ->
                currentMap.toMutableMap().apply {
                    val existingInfo = this[endpointId]
                    this[endpointId] = existingInfo?.copy(
                        ipAddress = fromAddr.asInetAddress(),
                        status = existingInfo.status,
                        isOutgoing = existingInfo.isOutgoing
                    ) ?: EndpointInfo(
                        endpointId = endpointId,
                        status = EndpointStatus.CONNECTED,
                        ipAddress = fromAddr.asInetAddress(),
                        isOutgoing = false
                    )
                }
            }

            // Also update IP map
            endpointIpMap[endpointId] = fromAddr.asInetAddress()
            log(LogLevel.DEBUG, "Updated IP mapping for endpoint $endpointId to ${fromAddr.addressToDotNotation()}")
        }

        try {
            onPacketReceived(virtualPacket)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error processing VirtualPacket from $endpointId", e)
        }
    }

    /**
     * Processes received stream payloads.
     * Routes streams based on header information:
     * - Reply streams to pending requests
     * - New streams to stream handler
     *
     * @param endpointId Source endpoint identifier
     * @param payload Stream payload data
     */
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

    private fun updateEndpointStatus(endpointId: String, status: EndpointStatus) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                this[endpointId] = this[endpointId]?.copy(status = status)
                    ?: EndpointInfo(endpointId, status, null, false)
            }
        }
    }

    private fun sendMmcpPingPacket(endpointId: String) {
        checkClosed()
        // Use virtual IP instead of broadcast for ping
        val mmcpPing = MmcpPing(Random.nextInt())
        val virtualPacket = mmcpPing.toVirtualPacket(virtualIpAddress, virtualIpAddress)  // Changed here
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
