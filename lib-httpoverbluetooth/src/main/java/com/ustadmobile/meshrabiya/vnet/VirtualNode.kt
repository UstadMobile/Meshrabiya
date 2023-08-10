package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.appendOrReplace
import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.ext.readRemoteAddress
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.ext.writeAddress
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotRequest
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotResponse
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpMessageAndPacketHeader
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.portforward.ForwardBindPoint
import com.ustadmobile.meshrabiya.portforward.UdpForwardRule
import com.ustadmobile.meshrabiya.util.findFreePort
import com.ustadmobile.meshrabiya.util.matchesMask
import com.ustadmobile.meshrabiya.util.uuidForMaskAndPort
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocket2
import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocketImpl
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketFactory
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketFactoryImpl
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketNextHop
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketServer
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.net.SocketFactory
import kotlin.concurrent.withLock
import kotlin.random.Random

//Generate a random Automatic Private IP Address
fun randomApipaAddr(): Int {
    //169.254
    val fixedSection = (169 shl 24).or(254 shl 16)

    val randomSection = Random.nextInt(Short.MAX_VALUE.toInt())

    return fixedSection.or(randomSection)
}

/**
 * Mashrabiya Node
 *
 * Connection refers to the underlying "real" connection to some other device. There may be multiple
 * connections to the same remote node (e.g. Bluetooth, Sockets running over WiFi, etc)
 *
 * Streams run over KWIK ?
Open local port on sender,


 *
 *
 * Addresses are 32 bit integers in the APIPA range
 */
abstract class VirtualNode(
    //Note: allocationServiceUuid should be based on datagram port using a UUID "Mask" and the port
    val uuidMask: UUID,
    val port: Int,
    val logger: MNetLogger = MNetLoggerStdout(),
    val localNodeAddress: Int = randomApipaAddr(),
    override val networkPrefixLength: Int = 16,
    val json: Json,
    val config: NodeConfig,
): NeighborNodeManager.NeighborNodeStateChangedListener, VirtualRouter, Closeable {

    /**
     * The local node address as a byte array
     */
    val localNodeAddressByteArray = localNodeAddress.addressToByteArray()

    override val localNodeInetAddress: InetAddress = InetAddress.getByAddress(localNodeAddressByteArray)

    //This executor is used for direct I/O activities
    protected val connectionExecutor: ExecutorService = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    protected val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    protected val coroutineScope = CoroutineScope(Dispatchers.Default + Job())

    private val neighborNodeManagers: MutableMap<Int, NeighborNodeManager> = ConcurrentHashMap()

    private val _neighborNodesState = MutableStateFlow(emptyList<NeighborNodeState>())

    private val mmcpMessageIdAtomic = AtomicInteger()

    val _state = MutableStateFlow(LocalNodeState())

    val state: Flow<LocalNodeState> = _state.asStateFlow()

    val neighborNodesState: Flow<List<NeighborNodeState>> = _neighborNodesState.asStateFlow()

    abstract val meshrabiyaWifiManager: MeshrabiyaWifiManager

    private val pongListeners = CopyOnWriteArrayList<PongListener>()

    protected val logPrefix: String = "[VirtualNode ${localNodeAddress.addressToDotNotation()}]"

    protected val iDatagramSocketFactory = VirtualNodeReturnPathSocketFactory(this)

    private val neighborConnectionManagerLock = ReentrantLock()

    private val forwardingRules: MutableMap<ForwardBindPoint, UdpForwardRule> = ConcurrentHashMap()

    /**
     * @param originatorMessage the Originator message itself
     * @param timeReceived the time this message was received
     * @param lastHopAddr the recorded last hop address
     */
    data class LastOriginatorMessage(
        val originatorMessage: MmcpOriginatorMessage,
        val timeReceived: Long,
        val lastHopAddr: Int,
        val hopCount: Byte,
        val lastHopRealInetAddr: InetAddress,
        val lastHopRealPort: Int,
    )

    @Suppress("unused") //Part of the API
    enum class Zone {
        VNET, REAL
    }

    /**
     * The currently known latest originator messages that can be used to route traffic.
     */
    protected val originatorMessages: MutableMap<Int, LastOriginatorMessage> = ConcurrentHashMap()

    private val localPort = findFreePort(0)

    val datagramSocket = VirtualNodeDatagramSocket(
        socket = DatagramSocket(localPort),
        ioExecutorService = connectionExecutor,
        router = this,
        localNodeVirtualAddress = localNodeAddress,
        onMmcpHelloReceivedListener = {
            logger(Log.DEBUG, { "$logPrefix onMmcpHelloReceived from ${it.address}" })
            addNewNeighborConnection(
                address = it.address,
                port = it.port,
                neighborNodeVirtualAddr = it.virtualPacket.header.fromAddr,
                socket = it.socket
            )
        },
        logger = logger,
    )

    protected val chainSocketFactory: ChainSocketFactory = ChainSocketFactoryImpl(
        virtualRouter = this,
        logger = logger,
    )

    val socketFactory: SocketFactory
        get() = chainSocketFactory

    private val chainSocketServer = ChainSocketServer(
        serverSocket = ServerSocket(localPort),
        executorService = connectionExecutor,
        chainSocketFactory = chainSocketFactory,
        name = localNodeAddress.addressToDotNotation(),
        logger = logger
    )

    val allocationServiceUuid: UUID by lazy {
        uuidForMaskAndPort(uuidMask, datagramSocket.localPort).also {
            val matches = it.matchesMask(uuidMask)
            logger(Log.DEBUG,  {"Allocation Service UUID: matches mask ($uuidMask) = $matches" })
        }
    }

    val allocationCharacteristicUuid: UUID by lazy {
        uuidForMaskAndPort(uuidMask, datagramSocket.localPort + 1)
    }

    private val sendOriginatingMessageRunnable = Runnable {
        val sentTime = System.currentTimeMillis()
        val originatingMessage = MmcpOriginatorMessage(
            messageId = nextMmcpMessageId(),
            pingTimeSum = 0,
            connectConfig = _state.value.wifiState.config,
            sentTime = sentTime
        )

        logger(
            priority = Log.VERBOSE,
            message = { "$logPrefix sending originating message " +
                "messageId=${originatingMessage.messageId} sentTime=$sentTime"
            }
        )

        val packet = originatingMessage.toVirtualPacket(
            toAddr = ADDR_BROADCAST,
            fromAddr = localNodeAddress,
        )
        val messageFromPacket = MmcpMessage.fromVirtualPacket(packet) as? MmcpOriginatorMessage
        logger(Log.VERBOSE, { "$logPrefix from packet sent time = ${messageFromPacket?.sentTime}"})
        if(messageFromPacket?.sentTime != sentTime) {
            logger(Log.ERROR, "WRONG WRONG WRONG", null)
        }
        route(packet)
    }

    private val _incomingMmcpMessages = MutableSharedFlow<MmcpMessageAndPacketHeader>(
        replay = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val incomingMmcpMessages: Flow<MmcpMessageAndPacketHeader> = _incomingMmcpMessages.asSharedFlow()

    private val activeSockets: MutableMap<Int, VirtualDatagramSocketImpl> = ConcurrentHashMap()

    init {
        _state.update { prev ->
            prev.copy(
                address = localNodeAddress,
                connectUri = generateConnectLink(hotspot = null).uri
            )
        }

        scheduledExecutor.scheduleAtFixedRate(sendOriginatingMessageRunnable,
            config.originatingMessageInitialDelay, config.originatingMessageInterval,
            TimeUnit.MILLISECONDS
        )
    }

    override fun onNeighborNodeStateChanged(remoteMNodeState: NeighborNodeState) {
        _neighborNodesState.update { prev ->
            prev.appendOrReplace(remoteMNodeState) { it.remoteAddress == remoteMNodeState.remoteAddress }
        }
    }

    override fun nextMmcpMessageId() = mmcpMessageIdAtomic.incrementAndGet()


    override fun allocateUdpPortOrThrow(
        virtualDatagramSocketImpl: VirtualDatagramSocketImpl,
        portNum: Int
    ): Int {
        if(portNum > 0) {
            if(activeSockets.containsKey(portNum))
                throw IllegalStateException("VirtualNode: port $portNum already allocated!")

            //requested port is not allocated, everything OK
            activeSockets[portNum] = virtualDatagramSocketImpl
            return portNum
        }

        var attemptCount = 0
        do {
            val randomPort = Random.nextInt(0, Short.MAX_VALUE.toInt())
            if(!activeSockets.containsKey(randomPort)) {
                activeSockets[randomPort] = virtualDatagramSocketImpl
                return randomPort
            }

            attemptCount++
        }while(attemptCount < 100)

        throw IllegalStateException("Could not allocate random free port")
    }

    override fun deallocatePort(protocol: Protocol, portNum: Int) {
        activeSockets.remove(portNum)
    }

    fun createDatagramSocket(): DatagramSocket {
        return VirtualDatagramSocket2(this, localNodeAddress, logger)
    }

    fun createBoundDatagramSocket(port: Int): DatagramSocket {
        return createDatagramSocket().also {
            it.bind(InetSocketAddress(localNodeInetAddress, port))
        }
    }

    /**
     *
     */
    fun forward(
        bindAddress: InetAddress,
        bindPort: Int,
        destAddress: InetAddress,
        destPort: Int,
    ) : Int {
        val listenSocket = if(
            bindAddress.prefixMatches(networkPrefixLength, localNodeInetAddress)
        ) {
            createBoundDatagramSocket(bindPort)
        }else {
            DatagramSocket(bindPort, bindAddress)
        }

        val forwardRule = createForwardRule(listenSocket, destAddress, destPort)
        val boundPort = listenSocket.localPort
        forwardingRules[ForwardBindPoint(bindAddress, null, boundPort)] = forwardRule

        return boundPort
    }

    fun forward(
        bindZone: Zone,
        bindPort: Int,
        destAddress: InetAddress,
        destPort: Int
    ): Int {
        val listenSocket = if(bindZone == Zone.VNET) {
            createBoundDatagramSocket(bindPort)
        }else {
            DatagramSocket(bindPort)
        }
        val forwardRule = createForwardRule(listenSocket, destAddress, destPort)
        val boundPort = listenSocket.localPort
        forwardingRules[ForwardBindPoint(null, bindZone, boundPort)] = forwardRule
        return boundPort
    }

    fun stopForward(
        bindZone: Zone,
        bindPort: Int
    ) {

    }

    fun stopForward(
        bindAddr: InetAddress,
        bindPort: Int,
    ) {

    }

    private fun createForwardRule(
        listenSocket: DatagramSocket,
        destAddress: InetAddress,
        destPort: Int,
    ) : UdpForwardRule {
        return UdpForwardRule(
            boundSocket = listenSocket,
            ioExecutor = this.connectionExecutor,
            destAddress = destAddress,
            destPort = destPort,
            logger = logger,
            returnPathSocketFactory = iDatagramSocketFactory,
        )
    }


    override val localDatagramPort: Int
        get() = datagramSocket.localPort


    protected fun generateConnectLink(
        hotspot: WifiConnectConfig?,
        bluetoothConfig: MeshrabiyaBluetoothState? = null,
    ) : MeshrabiyaConnectLink {
        return MeshrabiyaConnectLink.fromComponents(
            nodeAddr = localNodeAddress,
            port = localDatagramPort,
            hotspotConfig = hotspot,
            bluetoothConfig = bluetoothConfig,
            json = json,
        )
    }

    private fun onIncomingMmcpMessage(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket?,
    ) : Boolean {
        //This is an Mmcp message
        try {
            val mmcpMessage = MmcpMessage.fromVirtualPacket(packet)
            val from = packet.header.fromAddr
            logger(Log.VERBOSE,
                message = {
                    "$logPrefix received MMCP message (${mmcpMessage::class.simpleName}) " +
                    "from ${from.addressToDotNotation()}"
                }
            )

            val isToThisNode = packet.header.toAddr == localNodeAddress

            var shouldRoute = true

            when {
                mmcpMessage is MmcpPing && isToThisNode -> {
                    logger(Log.VERBOSE,
                        message = {
                            "$logPrefix Received ping(id=${mmcpMessage.messageId}) from ${from.addressToDotNotation()}"
                        }
                    )
                    //send pong
                    val pongMessage = MmcpPong(
                        messageId = nextMmcpMessageId(),
                        replyToMessageId = mmcpMessage.messageId
                    )

                    val replyPacket = pongMessage.toVirtualPacket(
                        toAddr = from,
                        fromAddr = localNodeAddress
                    )

                    logger(Log.VERBOSE, { "$logPrefix Sending pong to ${from.addressToDotNotation()}" })
                    route(replyPacket)
                }

                mmcpMessage is MmcpPong && isToThisNode -> {
                    logger(Log.VERBOSE, { "$logPrefix Received pong(id=${mmcpMessage.messageId})}" })
                    pongListeners.forEach {
                        it.onPongReceived(from, mmcpMessage)
                    }
                }

                mmcpMessage is MmcpHotspotRequest && isToThisNode -> {
                    logger(Log.INFO, "$logPrefix Received hotspotrequest (id=${mmcpMessage.messageId})", null)
                    coroutineScope.launch {
                        val hotspotResult = meshrabiyaWifiManager.requestHotspot(
                            mmcpMessage.messageId, mmcpMessage.hotspotRequest
                        )

                        if(from != localNodeAddress) {
                            val replyPacket = MmcpHotspotResponse(
                                messageId = mmcpMessage.messageId,
                                result = hotspotResult
                            ).toVirtualPacket(
                                toAddr = from,
                                fromAddr = localNodeAddress
                            )
                            logger(Log.INFO, "$logPrefix sending hotspotresponse to ${from.addressToDotNotation()}", null)
                            route(replyPacket)
                        }
                    }
                }

                mmcpMessage is MmcpOriginatorMessage -> {
                    //Dont keep originator messages in our own table for this node
                    logger(Log.VERBOSE,
                        message= {
                            "$logPrefix received originating message from " +
                                    "${packet.header.fromAddr.addressToDotNotation()} via " +
                                    packet.header.lastHopAddr.addressToDotNotation()
                        }
                    )
                    if(packet.header.fromAddr == localNodeAddress)
                        return true

                    val connectionPingTime = neighborNodeManagers[packet.header.lastHopAddr]?.pingTime ?: 0
                    MmcpOriginatorMessage.takeIf { connectionPingTime != 0.toShort() }
                        ?.incrementPingTimeSum(packet, connectionPingTime)
                    val currentOriginatorMessage = originatorMessages[packet.header.fromAddr]

                    //Update this only if it is more recent and/or better. It might be that we are getting it back
                    //via some other (suboptimal) route with more hops
                    val currentlyKnownSentTime = (currentOriginatorMessage?.originatorMessage?.sentTime ?: 0)
                    val currentlyKnownHopCount = (currentOriginatorMessage?.hopCount ?: Byte.MAX_VALUE)
                    val receivedFromRealInetAddr = datagramPacket?.address ?: return false
                    val isMoreRecentOrBetter = mmcpMessage.sentTime > currentlyKnownSentTime
                            || packet.header.hopCount < currentlyKnownHopCount

                    logger(Log.VERBOSE,
                        message = {
                            "$logPrefix received originating message from " +
                                    "${packet.header.fromAddr.addressToDotNotation()} via ${packet.header.lastHopAddr.addressToDotNotation()}" +
                                    " messageId=${mmcpMessage.messageId} " +
                                    " hopCount=${packet.header.hopCount} sentTime=${mmcpMessage.sentTime} " +
                                    " Currently known: senttime=$currentlyKnownSentTime  hop count = $currentlyKnownHopCount " +
                                    "isMoreRecentOrBetter=$isMoreRecentOrBetter "
                        }
                    )

                    if(currentOriginatorMessage == null || isMoreRecentOrBetter) {
                        originatorMessages[packet.header.fromAddr] = LastOriginatorMessage(
                            originatorMessage = mmcpMessage.copyWithPingTimeIncrement(connectionPingTime),
                            timeReceived = System.currentTimeMillis(),
                            lastHopAddr = packet.header.lastHopAddr,
                            hopCount = packet.header.hopCount,
                            lastHopRealInetAddr = receivedFromRealInetAddr,
                            lastHopRealPort = datagramPacket.port
                        )
                        logger(Log.VERBOSE,
                            message = {
                                "$logPrefix update originator messages: " +
                                        "currently known nodes = ${originatorMessages.keys.joinToString { it.addressToDotNotation() }}"
                            }
                        )

                        _state.update { prev ->
                            prev.copy(
                                originatorMessages = originatorMessages.toMap()
                            )
                        }
                    }

                    shouldRoute = isMoreRecentOrBetter
                }

                else -> {
                    // do nothing
                }
            }

            _incomingMmcpMessages.tryEmit(MmcpMessageAndPacketHeader(mmcpMessage, packet.header))

            return shouldRoute
        }catch(e: Exception) {
            e.printStackTrace()
            return false
        }

    }


    override fun route(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket?,
        virtualNodeDatagramSocket: VirtualNodeDatagramSocket?
    ) {
        try {
            val fromLastHop = packet.header.lastHopAddr

            if(fromLastHop != 0 &&
                fromLastHop != localNodeAddress &&
                !neighborNodeManagers.containsKey(fromLastHop) &&
                datagramPacket != null && virtualNodeDatagramSocket != null
            ) {
                logger(Log.DEBUG,
                    "$logPrefix route: previously unknown node found from lasthop: ${fromLastHop.addressToDotNotation()}",
                    null
                )
                //this is a neighbor we don't know about yet - setup a connection manager
                addNewNeighborConnection(datagramPacket.address, datagramPacket.port,
                    packet.header.lastHopAddr, virtualNodeDatagramSocket)
            }


            if(packet.header.hopCount >= config.maxHops) {
                logger(Log.DEBUG,
                    "Drop packet from ${packet.header.fromAddr.addressToDotNotation()} - " +
                            "${packet.header.hopCount} exceeds ${config.maxHops}",
                    null)
                return
            }

            if(packet.header.toPort == 0 && packet.header.fromAddr != localNodeAddress){
                //this is an MMCP message
                if(!onIncomingMmcpMessage(packet, datagramPacket)){
                    //It was determined that this packet should go no further by MMCP processing
                    logger(Log.DEBUG, "Drop mmcp packet from ${packet.header.fromAddr}", null)
                }
            }

            if(packet.header.toAddr == localNodeAddress) {
                //this is an incoming packet - give to the destination virtual socket/forwarding
                val listeningSocket = activeSockets[packet.header.toPort]
                if(listeningSocket != null) {
                    listeningSocket.onIncomingPacket(packet)
                }else {
                    logger(Log.DEBUG, "$logPrefix Incoming packet received, but no socket listening on: ${packet.header.toPort}")
                }
            }else {
                //packet needs to be sent to next hop / destination
                val toAddr = packet.header.toAddr

                packet.updateLastHopAddrAndIncrementHopCountInData(localNodeAddress)
                if(toAddr == ADDR_BROADCAST) {
                    neighborNodeManagers.values
                        .filter {
                            it.remoteAddress != fromLastHop && it.remoteAddress != packet.header.fromAddr
                        }.forEach {
                            logger(Log.VERBOSE,
                                message = {
                                    "$logPrefix broadcast packet " +
                                            "from=${packet.header.fromAddr.addressToDotNotation()} " +
                                            "lasthop=${fromLastHop.addressToDotNotation()} " +
                                            "send to ${it.remoteAddress.addressToDotNotation()}"
                                }
                            )
                            it.send(packet)
                        }
                }else {
                    val nextHop = originatorMessages[packet.header.toAddr]?.lastHopAddr
                    val neighborManager = nextHop?.let {
                        neighborNodeManagers[it]
                    }

                    if(neighborManager != null) {
                        logger(Log.VERBOSE, "$logPrefix ${packet.header.toAddr.addressToDotNotation()}", null)
                        neighborManager.send(packet)
                    }else {
                        //not routeable
                        logger(
                            Log.ERROR,
                            "$logPrefix Cannot route packet to ${packet.header.toAddr.addressToDotNotation()}",
                            null
                        )
                    }
                }
            }
        }catch(e: Exception) {
            logger(Log.ERROR,
                "$logPrefix : route : exception routing packet from ${packet.header.fromAddr.addressToDotNotation()}",
                e
            )
            throw e
        }
    }

    override fun lookupNextHopForChainSocket(address: InetAddress, port: Int): ChainSocketNextHop {
        val addressInt = address.requireAddressAsInt()

        val originatorMessage = originatorMessages[addressInt]

        return when {
            //Destination address is this node
            addressInt == localNodeAddress -> {
                ChainSocketNextHop(InetAddress.getLoopbackAddress(), port, true)
            }

            //Destination is a direct neighbor (final destination) - connect to the actual socket itself
            originatorMessage != null && originatorMessage.hopCount == 1.toByte() -> {
                ChainSocketNextHop(originatorMessage.lastHopRealInetAddr, port, true)
            }

            //Destination is not a direct neighbor, but we have a route there
            originatorMessage != null -> {
                ChainSocketNextHop(originatorMessage.lastHopRealInetAddr,
                    originatorMessage.lastHopRealPort, false)
            }

            //No route available to reach the given address
            else -> {
                logger(Log.ERROR, "$logPrefix : No route to virtual host: $address")
                throw NoRouteToHostException("No route to virtual host $address")
            }
        }
    }

    protected fun getOrCreateNeighborNodeManager(
        remoteAddress: Int
    ): NeighborNodeManager {
        return neighborNodeManagers.getOrPut(remoteAddress) {
            NeighborNodeManager(
                remoteAddress = remoteAddress,
                router = this,
                localNodeAddress = localNodeAddress,
                connectionExecutor = connectionExecutor,
                scheduledExecutor = scheduledExecutor,
                logger = logger,
                listener = this,
            )
        }
    }

    fun handleNewSocketConnection(
        iSocket: ISocket
    ) {
        logger(Log.DEBUG, "$logPrefix handleNewBluetoothConnection: write address: " +
                localNodeAddress.addressToDotNotation(),null)

        iSocket.outputStream.writeAddress(localNodeAddress)
        iSocket.outputStream.flush()

        val remoteAddress = iSocket.inStream.readRemoteAddress()
        logger(Log.DEBUG, "$logPrefix handleNewBluetoothConnection: read remote address: " +
                remoteAddress.addressToDotNotation(),null)

        val newRemoteNodeManager = getOrCreateNeighborNodeManager(
            remoteAddress = remoteAddress,
        ).also {
            it.addConnection(iSocket)
        }

        neighborNodeManagers[remoteAddress] = newRemoteNodeManager
    }

    /**
     * Respond to a new
     */
    fun addNewNeighborConnection(
        address: InetAddress,
        port: Int,
        neighborNodeVirtualAddr: Int,
        socket: VirtualNodeDatagramSocket,
    ) {
        logger(Log.DEBUG,
            "$logPrefix addNewNeighborConnection connection to virtual addr " +
                    "${neighborNodeVirtualAddr.addressToDotNotation()} " +
                    "via datagram to $address:$port",
            null
        )

        neighborConnectionManagerLock.withLock {
            val remoteNodeManager = getOrCreateNeighborNodeManager(neighborNodeVirtualAddr)
            remoteNodeManager.addDatagramConnection(address, port, socket)
        }

        scheduledExecutor.submit(sendOriginatingMessageRunnable)
    }

    fun addPongListener(listener: PongListener) {
        pongListeners += listener
    }

    fun removePongListener(listener: PongListener) {
        pongListeners -= listener
    }



    fun sendRequestWifiConnectionMmcpMessage(virtualAddr: Int)  {
        val request = MmcpHotspotRequest(
            messageId = nextMmcpMessageId(),
            hotspotRequest = LocalHotspotRequest(
                is5GhzSupported = meshrabiyaWifiManager.is5GhzSupported
            )
        )

        //Send MMCP request to the other node
        route(
            packet = request.toVirtualPacket(
                toAddr = virtualAddr,
                fromAddr = localNodeAddress
            ),
        )
    }

    open suspend fun setWifiHotspotEnabled(enabled: Boolean): LocalHotspotResponse? {
        return if(enabled){
             meshrabiyaWifiManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    is5GhzSupported = meshrabiyaWifiManager.is5GhzSupported
                )
            )
        }else {
            null
        }
    }





    override fun close() {
        neighborNodeManagers.values.forEach {
            it.close()
        }
        datagramSocket.close()
        chainSocketServer.close(closeSocket = true)

        connectionExecutor.shutdown()
        scheduledExecutor.shutdown()
    }

}