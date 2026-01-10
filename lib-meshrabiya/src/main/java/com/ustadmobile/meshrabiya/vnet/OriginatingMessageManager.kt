package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.ext.addressToByteArray

import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage  // NEW: Import official message type
// TODO: Uncomment when MmcpNodeAnnouncement is available
// import com.ustadmobile.meshrabiya.mmcp.MmcpNodeAnnouncement (DEPRECATED)
// TODO: Uncomment when MmcpMessageFactory is available
// import com.ustadmobile.meshrabiya.mmcp.MmcpMessageFactory (DEPRECATED)
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketNextHop
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import java.util.concurrent.atomic.AtomicInteger
import com.ustadmobile.meshrabiya.vnet.HasNodeState
import com.ustadmobile.meshrabiya.vnet.VirtualNode

class OriginatingMessageManager(
    private val localNodeInetAddr: InetAddress,
    private val logger: MNetLogger,
    private val scheduledExecutor: ScheduledExecutorService,
    private val nextMmcpMessageId: () -> Int,
    private val getWifiState: () -> MeshrabiyaWifiState,
    
    // === NEW: Callbacks to break circular dependency ===
    private val getCentralityScore: (() -> Float)? = null,
    private val getMeshRoles: (() -> Set<MeshRole>)? = null,
    private val getFitnessScore: (() -> Float)? = null,  // Changed from () -> Int
    
    // === EXISTING PARAMS ===
    private val pingTimeout: Int = 15_000,
    private val originatingMessageNodeLostThreshold: Int = 10000,
    lostNodeCheckInterval: Int = 1_000,
    private val betaLogger: BetaTestLogger? = null
) {

    private val logPrefix ="[OriginatingMessageManager for ${localNodeInetAddr}] "

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val localNodeAddress = localNodeInetAddr.requireAddressAsInt()

    /**
     * The currently known latest originator messages that can be used to route traffic.
     */
    private val originatorMessages: MutableMap<Int, VirtualNode.LastOriginatorMessage> = ConcurrentHashMap()

    private val _state = MutableStateFlow(OriginatingMessageState())
    val state: StateFlow<OriginatingMessageState> = _state

    private val receivedMessages: Flow<VirtualNode.LastOriginatorMessage> = MutableSharedFlow(
        replay = 1 , extraBufferCapacity = 0, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    data class PendingPing(
        val ping: MmcpPing,
        val toVirtualAddr: Int,
        val timesent: Long
    )

    data class PingTime(
        val nodeVirtualAddr: Int,
        val pingTime: Short,
        val timeReceived: Long,
    )

    private val pendingPings = CopyOnWriteArrayList<PendingPing>()

    private val neighborPingTimes: MutableMap<Int, PingTime> = ConcurrentHashMap()

    // Add a map to store neighbor fitness and role info
    private val neighborFitnessInfo: MutableMap<Int, Pair<Int, Byte>> = ConcurrentHashMap()

    // Track multi-hop neighbor info
    private val neighborCentralityInfo: MutableMap<Int, Float> = ConcurrentHashMap()

    private val messageCounter = AtomicInteger(0)

    // === PHASE 3C: GATEWAY MESSAGE TRACKING ===
    /**
     * Tracks messages sent via gateways for return path routing and statistics.
     * Key: "fromAddr:fromPort", Value: GatewayMessage
     */
    private val gatewayMessages: MutableMap<String, GatewayMessage> = ConcurrentHashMap()

    // === TOPOLOGY MAP WITH FULL NODE INFO ===
    // Enhanced to store complete NodeTopologyInfo (roles, metrics) instead of just neighbors
    private val _topologyMapInfo: MutableMap<Int, NodeTopologyInfo> = mutableMapOf()
    
    // Expose as Flow for observers (e.g., GatewaySelector)
    private val _topologyMapFlow = MutableStateFlow<Map<Int, NodeTopologyInfo>>(emptyMap())
    val topologyMapFlow: StateFlow<Map<Int, NodeTopologyInfo>> = _topologyMapFlow.asStateFlow()
    
    /**
     * Get full topology map with NodeTopologyInfo (roles, metrics, neighbors)
     * Used by GatewaySelector for intelligent gateway selection
     */
    fun getTopologyMapInfo(): Map<Int, NodeTopologyInfo> = _topologyMapInfo
    
    /**
     * Get nodes with specific role (e.g., TOR_GATEWAY, CLEARNET_GATEWAY)
     * @param role MeshRole to filter by
     * @return List of NodeTopologyInfo for nodes with the specified role
     */
    fun getNodesWithRole(role: MeshRole): List<NodeTopologyInfo> {
        return _topologyMapInfo.filter { it.value.hasRole(role) }.values.toList()
    }
    
    /**
     * Get all gateway nodes (TOR, CLEARNET, I2P)
     * @return List of NodeTopologyInfo for gateway nodes
     */
    fun getGatewayNodes(): List<NodeTopologyInfo> {
        return _topologyMapInfo.filter { it.value.isGatewayNode() }.values.toList()
    }
    
    /**
     * Backward compatibility: Convert NodeTopologyInfo to old Map<Int, Set<Int>> format
     * Used by EmergentRoleManager for centrality calculations
     */
    @Deprecated("Use getTopologyMapInfo() for full node information")
    fun getTopologyMap(): Map<Int, Set<Int>> {
        return _topologyMapInfo.mapValues { it.value.neighbors }
    }

    private fun logBeta(level: LogLevel, message: String, throwable: Throwable? = null) {
        betaLogger?.log(level, message, throwable)
    }

    private val sendOriginatingMessageRunnable = Runnable {
        try {
            val originatingMessage = makeOriginatingMessage()  // Now includes callbacks
            
            logBeta(LogLevel.DEBUG, "Sending originating message: " +
                "messageId=${originatingMessage.messageId}, " +
                "neighbors=${originatingMessage.neighbors.size}, " +
                "centrality=${originatingMessage.centralityScore}")

            logger(
                priority = Log.VERBOSE,
                message = { "$logPrefix sending originating message messageId=${originatingMessage.messageId} " +
                    "sentTime=${originatingMessage.sentTime} neighbors=${originatingMessage.neighbors.size}" }
            )

            val packet = originatingMessage.toVirtualPacket(
                toAddr = ADDR_BROADCAST,
                fromAddr = localNodeAddress,
                lastHopAddr = localNodeAddress,
                hopCount = 1,
            )

            val neighbors = originatorMessages.filter {
                it.value.hopCount == 1.toByte()
            }

            neighbors.forEach {
                val lastOriginatorMessage = it.value
                try {
                    lastOriginatorMessage.receivedFromSocket.send(
                        nextHopAddress = lastOriginatorMessage.lastHopRealInetAddr,
                        nextHopPort = lastOriginatorMessage.lastHopRealPort,
                        virtualPacket = packet,
                    )
                }catch(e: Exception) {
                    logger(Log.WARN, "$logPrefix : sendOriginatingMessagesRunnable: exception sending to " +
                            "${it.key.addressToDotNotation()} through ${it.value.lastHopRealInetAddr}:${it.value.lastHopRealPort}",
                        e)
                }
            }

            //check if we have an active station connection but have lost the originating message from
            // the hotspot node e.g. node slowed down for a while, app restart, etc.
            //Send it an originating message even if we haven't receive one from it lately
            //This could help restore a connection that died temporarily.
            val stationState = getWifiState().wifiStationState
            val stationNeighborInetAddr = stationState.config?.linkLocalAddr
            val stationDatagramPort = stationState.config?.port
            if(stationNeighborInetAddr != null &&
                !neighbors.any { it.value.lastHopRealInetAddr == stationNeighborInetAddr }
                && stationDatagramPort != null
                && stationState.stationBoundDatagramSocket != null
            ) {
                logger(Log.WARN, "$logPrefix : sendOriginatingMessagesRunnable: have not received " +
                        " originating message from hotspot we are connected to as station. Retrying")
                try {
                    stationState.stationBoundDatagramSocket.send(
                        nextHopAddress = stationNeighborInetAddr,
                        nextHopPort = stationDatagramPort,
                        virtualPacket = packet,
                    )
                }catch(e: Exception) {
                    logger(Log.ERROR, "$logPrefix : sendOriginatingMessagesRunnable: could not " +
                            "send originating message to group owner", e)
                }
            }else if(stationNeighborInetAddr != null && stationState.stationBoundDatagramSocket == null) {
                logger(Log.WARN, "$logPrefix : sendOriginatingMessagesRunnable : could not send " +
                        "originating message to group owner socket not set on state")
            }
        } catch (e: Exception) {
            logBeta(LogLevel.ERROR, "Error sending originating message", e)
            logger(Log.ERROR, { "$logPrefix : sendOriginatingMessageRunnable : exception sending originating message" }, e)
        }
    }

    private val pingNeighborsRunnable = Runnable {
        try {
            val neighbors = neighbors()
            logBeta(LogLevel.DEBUG, "Pinging neighbors: ${neighbors.map { it.first.addressToDotNotation() }.joinToString()}")
            neighbors.forEach {
                val neighborVirtualAddr = it.first
                val lastOrigininatorMessage = it.second
                val pingMessage = MmcpPing(messageId = nextMmcpMessageId())
                pendingPings.add(PendingPing(pingMessage, neighborVirtualAddr, System.currentTimeMillis()))
                logger(
                    priority = Log.VERBOSE,
                    message = { "$logPrefix pingNeighborsRunnable: send ping to ${neighborVirtualAddr.addressToDotNotation()}" }
                )

                it.second.receivedFromSocket.send(
                    nextHopAddress = lastOrigininatorMessage.lastHopRealInetAddr,
                    nextHopPort = lastOrigininatorMessage.lastHopRealPort,
                    virtualPacket = pingMessage.toVirtualPacket(
                        toAddr = neighborVirtualAddr,
                        fromAddr = localNodeAddress,
                        lastHopAddr = localNodeAddress,
                        hopCount = 1,
                    )
                )
            }

            //Remove expired pings
            val pingTimeoutThreshold = System.currentTimeMillis() - pingTimeout
            pendingPings.removeIf { it.timesent < pingTimeoutThreshold }

            logBeta(LogLevel.DEBUG, "Pinging neighbors: ${neighborFitnessInfo.keys.joinToString { it.addressToDotNotation() }}")
        } catch (e: Exception) {
            logBeta(LogLevel.ERROR, "Error pinging neighbors", e)
            logger(Log.ERROR, { "$logPrefix : pingNeighborsRunnable : exception pinging neighbors" }, e)
        }
    }

    private val checkLostNodesRunnable = Runnable {
        try {
            val timeNow = System.currentTimeMillis()
            val nodesLost = originatorMessages.entries.filter {
                (timeNow - it.value.timeReceived) > originatingMessageNodeLostThreshold
            }
            logBeta(LogLevel.DEBUG, "Checking lost nodes: ${nodesLost.map { it.key.addressToDotNotation() }.joinToString()}")
            nodesLost.forEach {
                logBeta(LogLevel.INFO, "Lost node: ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms")
                logger(Log.DEBUG, {"$logPrefix : checkLostNodesRunnable: " +
                        "Lost ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms"})
                originatorMessages.remove(it.key)
            }

            _state.value = OriginatingMessageState(
                pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
            )
        } catch (e: Exception) {
            logBeta(LogLevel.ERROR, "Error checking lost nodes", e)
            logger(Log.ERROR, { "$logPrefix : checkLostNodesRunnable : exception checking lost nodes" }, e)
        }
    }

    private val sendOriginatorMessagesFuture = scheduledExecutor.scheduleWithFixedDelay(
        sendOriginatingMessageRunnable, 1000, 3000, TimeUnit.MILLISECONDS
    )

    private val pingNeighborsFuture = scheduledExecutor.scheduleWithFixedDelay(
        pingNeighborsRunnable, 1000, 10000, TimeUnit.MILLISECONDS
    )

    private val checkLostNodesFuture = scheduledExecutor.scheduleWithFixedDelay(
        checkLostNodesRunnable, lostNodeCheckInterval.toLong(), lostNodeCheckInterval.toLong(), TimeUnit.MILLISECONDS
    )

    @Volatile
    private var closed = false


    private fun makeOriginatingMessage(): MmcpOriginatorMessage {
        // Get current direct neighbor addresses for topology building
        val neighborAddrs = originatorMessages
            .filter { it.value.hopCount == 1.toByte() }
            .keys
            .toList()
        
        // Use callbacks instead of direct EmergentRoleManager access
        val centralityScore = getCentralityScore?.invoke() ?: 0f
        val meshRoles = getMeshRoles?.invoke() ?: setOf(MeshRole.MESH_PARTICIPANT)
        val fitnessScore = getFitnessScore?.invoke() ?: 0f
        
        return MmcpOriginatorMessage(
            messageId = nextMmcpMessageId(),
            sentTime = System.currentTimeMillis(),
            pingTimeSum = 0,  // Will be incremented as message propagates
            connectConfig = getWifiState().connectConfig,
            neighbors = neighborAddrs,  // NEW: For topology building
            centralityScore = centralityScore,  // NEW: From callback
            fitnessScore = fitnessScore,  // NEW: From callback
            meshRoles = meshRoles,  // NEW: From callback
        )
    }


    private fun assertNotClosed() {
        if(closed)
            throw IllegalStateException("$logPrefix is closed!")
    }


    fun onReceiveOriginatingMessage(
        mmcpMessage: MmcpOriginatorMessage,  // Changed type
        datagramPacket: DatagramPacket,
        datagramSocket: VirtualNodeDatagramSocket,
        virtualPacket: VirtualPacket,
    ): Boolean {
        assertNotClosed()
        
        logBeta(LogLevel.DEBUG, "Received originating message from " +
            "${virtualPacket.header.fromAddr.addressToDotNotation()}: " +
            "neighbors=${mmcpMessage.neighbors.size}, " +
            "centrality=${mmcpMessage.centralityScore}")

        //Dont keep originator messages in our own table for this node
        logger(
            Log.VERBOSE,
            message= {
                "$logPrefix received originating message from " +
                        "${virtualPacket.header.fromAddr.addressToDotNotation()} via " +
                        virtualPacket.header.lastHopAddr.addressToDotNotation()
            }
        )

        val connectionPingTime = neighborPingTimes[virtualPacket.header.lastHopAddr]?.pingTime ?: 0.toLong()

        val currentOriginatorMessage = originatorMessages[virtualPacket.header.fromAddr]

        // === OFFICIAL FRESHNESS CHECK (preserved from canonical design) ===
        val currentlyKnownSentTime = (currentOriginatorMessage?.originatorMessage?.sentTime ?: 0)
        val currentlyKnownHopCount = (currentOriginatorMessage?.hopCount ?: Byte.MAX_VALUE)
        val receivedFromRealInetAddr = datagramPacket.address
        val receivedFromSocket = datagramSocket
        val isMoreRecentOrBetter = mmcpMessage.sentTime > currentlyKnownSentTime
                || mmcpMessage.sentTime == currentlyKnownSentTime && virtualPacket.header.hopCount < currentlyKnownHopCount
        val isNewNeighbor = virtualPacket.header.hopCount == 1.toByte() &&
                !originatorMessages.containsKey(virtualPacket.header.fromAddr)

        logger(
            Log.VERBOSE,
            message = {
                "$logPrefix received originating message from " +
                        "${virtualPacket.header.fromAddr.addressToDotNotation()} via ${virtualPacket.header.lastHopAddr.addressToDotNotation()}" +
                        " messageId=${mmcpMessage.messageId} " +
                        " hopCount=${virtualPacket.header.hopCount} sentTime=${mmcpMessage.sentTime} " +
                        " Currently known: sentTime=$currentlyKnownSentTime  hop count = $currentlyKnownHopCount " +
                        "isMoreRecentOrBetter=$isMoreRecentOrBetter "
            }
        )

        // === UPDATE ROUTING TABLE (official logic) ===
        if(currentOriginatorMessage == null || isMoreRecentOrBetter) {
            originatorMessages[virtualPacket.header.fromAddr] = VirtualNode.LastOriginatorMessage(
                originatorMessage = mmcpMessage.copyWithPingTimeIncrement(connectionPingTime.toLong()),
                timeReceived = System.currentTimeMillis(),
                lastHopAddr = virtualPacket.header.lastHopAddr,
                hopCount = virtualPacket.header.hopCount,
                lastHopRealInetAddr = receivedFromRealInetAddr,
                receivedFromSocket = receivedFromSocket,
                lastHopRealPort = datagramPacket.port,
                neighborAddr =  InetAddress.getByAddress(virtualPacket.header.fromAddr.addressToByteArray())
            )
            
            // === ENHANCED: BUILD TOPOLOGY MAP WITH ROLES ===
            val nodeInfo = NodeTopologyInfo(
                nodeAddress = virtualPacket.header.fromAddr,
                neighbors = mmcpMessage.neighbors.toSet(),
                meshRoles = mmcpMessage.meshRoles,  // Store ALL roles (gateway + intelligence)
                centralityScore = mmcpMessage.centralityScore,
                fitnessScore = mmcpMessage.fitnessScore,
                lastSeen = System.currentTimeMillis(),
                pingTime = mmcpMessage.pingTimeSum
            )
            
            _topologyMapInfo[virtualPacket.header.fromAddr] = nodeInfo
            _topologyMapFlow.value = _topologyMapInfo.toMap()  // Emit update for observers
            
            // Log gateway role changes (TOR/CLEARNET/I2P only)
            val gatewayRoles = nodeInfo.meshRoles.filter { 
                it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
            }
            if (gatewayRoles.isNotEmpty()) {
                logger(
                    Log.INFO, 
                    message = { "$logPrefix Node ${virtualPacket.header.fromAddr.addressToDotNotation()} offers gateways: $gatewayRoles " +
                        "(fitness=${nodeInfo.fitnessScore}, centrality=${nodeInfo.centralityScore})" }
                )
            }
            
            // Log intelligence roles (STORAGE/COMPUTE) at DEBUG level
            val intelligenceRoles = nodeInfo.meshRoles.filter {
                it in setOf(MeshRole.STORAGE_NODE, MeshRole.COMPUTE_NODE)
            }
            if (intelligenceRoles.isNotEmpty()) {
                logger(
                    Log.DEBUG,
                    message = { "$logPrefix Node ${virtualPacket.header.fromAddr.addressToDotNotation()} offers intelligence: $intelligenceRoles " +
                        "(fitness=${nodeInfo.fitnessScore}, centrality=${nodeInfo.centralityScore})" }
                )
            }
            
            logger(
                Log.VERBOSE,
                message = { "$logPrefix updated topology: node ${virtualPacket.header.fromAddr.addressToDotNotation()} " +
                    "has ${mmcpMessage.neighbors.size} neighbors, ${nodeInfo.meshRoles.size} roles" }
            )
            
            // === NEW: STORE NEIGHBOR METADATA ===
            if (virtualPacket.header.hopCount == 1.toByte()) {
                neighborFitnessInfo[virtualPacket.header.fromAddr] = Pair(
                    (mmcpMessage.fitnessScore * 100).toInt(),
                    0  // Reserved
                )
                neighborCentralityInfo[virtualPacket.header.fromAddr] = mmcpMessage.centralityScore
            }
            
            logger(
                Log.VERBOSE,
                message = {
                    "$logPrefix update originator messages: " +
                            "currently known nodes = ${originatorMessages.keys.joinToString { it.addressToDotNotation() }}; " +
                            "neighbor fitness/role: ${neighborFitnessInfo.map { (k, v) -> k.addressToDotNotation() + ":" + v.first + ",role=" + v.second }.joinToString()}" +
                            ", neighbor count: ${neighborFitnessInfo.size}" +
                            ", multi-hop neighbor centrality: ${neighborCentralityInfo}"
                }
            )

            // === EMIT STATE UPDATE ===
            _state.value = OriginatingMessageState(
                pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
            )
            
            logBeta(LogLevel.INFO, "Updated originator messages: known nodes = ${originatorMessages.keys.joinToString { it.addressToDotNotation() }}, neighbor fitness/role: ${neighborFitnessInfo.map { (k, v) -> k.addressToDotNotation() + ":" + v.first + ",role=" + v.second }.joinToString()}, neighbor count: ${neighborFitnessInfo.size}, multi-hop neighbor centrality: ${neighborCentralityInfo}")
        }

        // === TRIGGER IMMEDIATE REPLY FOR NEW NEIGHBORS (official behavior) ===
        if(isNewNeighbor) {
            scheduledExecutor.submit(sendOriginatingMessageRunnable)
        }

        return isMoreRecentOrBetter
    }

    fun onPongReceived(
        fromVirtualAddr: Int,
        pong: MmcpPong,
    ) {
        val pendingPingPredicate : (PendingPing) -> Boolean = {
            it.ping.messageId == pong.replyToMessageId && it.toVirtualAddr == fromVirtualAddr
        }

        val pendingPing = pendingPings.firstOrNull(pendingPingPredicate)

        if(pendingPing == null){
            logBeta(LogLevel.WARN, "Pong from ${fromVirtualAddr.addressToDotNotation()} does not match any known sent ping")
            return
        }

        val timeNow = System.currentTimeMillis()

        //Sometimes unit tests will run very quickly, and test may fail if ping time is 0
        val pingTime = maxOf((timeNow - pendingPing.timesent).toLong(), 1)
        logBeta(LogLevel.DEBUG, "Received ping from ${fromVirtualAddr.addressToDotNotation()} pingTime=$pingTime")

        neighborPingTimes[fromVirtualAddr] = PingTime(
            nodeVirtualAddr = fromVirtualAddr,
            pingTime = pingTime.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort(),
            timeReceived = timeNow,
        )

        pendingPings.removeIf(pendingPingPredicate)
    }

    fun findOriginatingMessageFor(addr: Int): VirtualNode.LastOriginatorMessage? {
        return originatorMessages[addr]
    }


    fun lookupNextHopForChainSocket(address: InetAddress, port: Int): ChainSocketNextHop {
        val addressInt = address.requireAddressAsInt()

        val originatorMessage = originatorMessages[addressInt]

        return when {
            //Destination address is this node
            addressInt == localNodeAddress -> {
                ChainSocketNextHop(InetAddress.getLoopbackAddress(), port, true, null)
            }

            //Destination is a direct neighbor (final destination) - connect to the actual socket itself
            originatorMessage != null && originatorMessage.hopCount == 1.toByte() -> {
                ChainSocketNextHop(originatorMessage.lastHopRealInetAddr, port, true,
                        originatorMessage.receivedFromSocket.boundNetwork)
            }

            //Destination is not a direct neighbor, but we have a route there
            originatorMessage != null -> {
                ChainSocketNextHop(originatorMessage.lastHopRealInetAddr,
                    originatorMessage.lastHopRealPort, false,
                    originatorMessage.receivedFromSocket.boundNetwork)
            }

            //No route available to reach the given address
            else -> {
                logger(Log.ERROR, "$logPrefix : No route to virtual host: $address")
                throw NoRouteToHostException("No route to virtual host $address")
            }
        }
    }


    /**
     * Run the process to add a new neighbor (e.g. after a Wifi station connection is established).
     *
     * This will send originating messages to the neighbor node and wait until we receive an
     * originating message reply (up until a timeout)
     *
     * @param neighborRealInetAddr the InetAddress of the neighbor (e.g. real IP address)
     * @param neighborRealPort The port on which the neighbor is running VirtualNodeDatagramSocket
     * @param socket our VirtualNodeDatagramSocket through which we will attempt to communicate with
     *        the new neighbor - this is often the socket bound to a Network object after a new
     *        wifi connection is established
     * @param timeout the timeout (in ms) for the new connection to be established. If the timeout
     *        is exceeded an exception will be thrown
     * @param sendInterval the interval period for sending out originating messages to the new neighbor
     */
    suspend fun addNeighbor(
        neighborRealInetAddr: InetAddress,
        neighborRealPort: Int,
        socket: VirtualNodeDatagramSocket,
        timeout: Int = 15_000,
        sendInterval: Int = 1_000,
    ) {
        logBeta(LogLevel.INFO, "Adding neighbor: $neighborRealInetAddr:$neighborRealPort")
        logger(Log.DEBUG, "$logPrefix: addNeighbor - sending originating messages out")

        //send originating packets out to the other device until we get something back from it
        val sendOriginatingMessageJob = scope.launch {
            try {
                val originatingMessage = makeOriginatingMessage()  // Use no-arg version with callbacks
                socket.send(
                    nextHopAddress = neighborRealInetAddr,
                    nextHopPort = neighborRealPort,
                    virtualPacket = originatingMessage.toVirtualPacket(
                        toAddr = ADDR_BROADCAST,
                        fromAddr = localNodeAddress,
                        lastHopAddr = localNodeAddress,
                        hopCount = 1,
                    )
                )
            }catch(e: Exception) {
                logger(Log.WARN, "$logPrefix : addNeighbor : exception trying to send originating message", e)
            }

            delay(sendInterval.toLong())
        }

        try {
            withTimeout(timeout.toLong()) {
                val replyMessage = receivedMessages.filter {
                    it.lastHopRealInetAddr == neighborRealInetAddr && it.lastHopRealPort == neighborRealPort
                }.first()
                logBeta(LogLevel.INFO, "Received originating message reply from ${replyMessage.lastHopAddr.addressToDotNotation()}")
            }
        }finally {
            sendOriginatingMessageJob.cancel()
        }

    }

    fun neighbors() : List<Pair<Int, VirtualNode.LastOriginatorMessage>> {
        return originatorMessages.filter { it.value.hopCount == 1.toByte() }.map {
            it.key to it.value
        }
    }


    fun close(){
        sendOriginatorMessagesFuture.cancel(true)
        pingNeighborsFuture.cancel(true)
        checkLostNodesFuture.cancel(true)
        scope.cancel("$logPrefix closed")
        closed = true
    }

    // Add a method to get neighbor fitness info
    fun getNeighborFitnessInfo(): Map<Int, Pair<Int, Byte>> = neighborFitnessInfo.toMap()

    fun gossipFitnessScore() {
        logBeta(LogLevel.DEBUG, "Gossiping fitness score")
        // Enhanced gossip protocol: propagate multi-hop neighbor info
        // For each direct neighbor, include our own neighbor count and centrality score in the message
        // (In a real implementation, you might extend MmcpOriginatorMessage to carry this info explicitly)
        sendOriginatingMessageRunnable.run()
        // Optionally, could send additional messages with multi-hop info, or piggyback on existing ones
    }

    /**
     * Send a custom message (deprecated - use makeOriginatingMessage for originating messages).
     * This is a placeholder for generic message sending.
     */
    fun sendMessage(message: MmcpMessage) {
        // Generic message sending - add to pending messages
        val messageId = message.messageId
        if (message is MmcpOriginatorMessage) {
            _state.value = _state.value.copy(
                pendingMessages = _state.value.pendingMessages + (messageId to message)
            )
        }
    }

    fun handlePong(pong: MmcpPong) {
        val messageId = pong.messageId
        _state.value = _state.value.copy(
            pendingMessages = _state.value.pendingMessages.filterKeys { it != messageId }
        )
    }

    fun getCurrentState(): OriginatingMessageState {
        return state.value
    }

    fun getNextMessageId(): Int {
        return messageCounter.incrementAndGet()
    }

    // === PHASE 3C: GATEWAY MESSAGE TRACKING METHODS ===
    
    /**
     * Tracks a message sent via gateway for return path routing.
     * Phase 3C: Gateway packet tracking
     *
     * @param fromAddr Source virtual address
     * @param fromPort Source port
     * @param toAddr Destination address (internet)
     * @param toPort Destination port
     * @param gatewayType Gateway type (TOR or CLEARNET)
     * @param gatewayAddr Gateway node address
     */
    fun trackGatewayMessage(
        fromAddr: Int,
        fromPort: Int,
        toAddr: Int,
        toPort: Int,
        gatewayType: Byte,
        gatewayAddr: Int
    ) {
        val key = createGatewayMessageKey(fromAddr, fromPort)
        val message = GatewayMessage(
            fromAddr = fromAddr,
            fromPort = fromPort,
            toAddr = toAddr,
            toPort = toPort,
            timestamp = System.currentTimeMillis(),
            gatewayType = gatewayType,
            gatewayAddr = gatewayAddr
        )
        
        gatewayMessages[key] = message
        
        logger(
            priority = Log.DEBUG,
            message = { 
                "$logPrefix Tracked gateway message: ${fromAddr.addressToDotNotation()}:$fromPort → " +
                "gateway ${gatewayAddr.addressToDotNotation()} (type=$gatewayType)" 
            }
        )
    }

    /**
     * Gets gateway address for return traffic.
     * Phase 3C: Used to route return packets back through same gateway
     *
     * @param toAddr Destination address (local node)
     * @param toPort Destination port
     * @return Gateway node address, or null if not routed via gateway
     */
    fun getGatewayForReturnTraffic(toAddr: Int, toPort: Int): Int? {
        val key = createGatewayMessageKey(toAddr, toPort)
        return gatewayMessages[key]?.gatewayAddr
    }

    /**
     * Returns statistics on gateway usage.
     * Phase 3C: For debugging and monitoring
     *
     * @return Map of gateway type to usage count
     */
    fun getGatewayUsageStats(): Map<Byte, Int> {
        val stats = mutableMapOf<Byte, Int>()
        
        gatewayMessages.values.forEach { msg ->
            val count = stats.getOrDefault(msg.gatewayType, 0)
            stats[msg.gatewayType] = count + 1
        }
        
        return stats
    }

    /**
     * Creates a unique key for gateway message tracking.
     * Format: "fromAddr:fromPort"
     */
    private fun createGatewayMessageKey(fromAddr: Int, fromPort: Int): String {
        return "$fromAddr:$fromPort"
    }

    /**
     * Cleans up stale gateway messages older than threshold.
     * Called periodically to prevent memory leaks.
     */
    fun cleanupStaleGatewayMessages(maxAgeMs: Long = 60_000L) {
        val now = System.currentTimeMillis()
        val iterator = gatewayMessages.entries.iterator()
        var removed = 0
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.timestamp > maxAgeMs) {
                iterator.remove()
                removed++
            }
        }
        
        if (removed > 0) {
            logger(
                priority = Log.DEBUG,
                message = { "$logPrefix Cleaned up $removed stale gateway messages" }
            )
        }
    }

    // Expose the current originatorMessages map for state updates
    fun getOriginatorMessages(): Map<Int, VirtualNode.LastOriginatorMessage> = originatorMessages
}

/**
 * Tracks a message sent via gateway for return path routing.
 * Phase 3C: Gateway packet tracking data structure
 */
data class GatewayMessage(
    val fromAddr: Int,
    val fromPort: Int,
    val toAddr: Int,
    val toPort: Int,
    val timestamp: Long,
    val gatewayType: Byte,
    val gatewayAddr: Int
)

data class OriginatingMessageState(
    val pendingMessages: Map<Int, MmcpOriginatorMessage> = emptyMap(),
)