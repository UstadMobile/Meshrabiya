package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.appendOrReplace
import com.ustadmobile.meshrabiya.ext.readRemoteAddress
import com.ustadmobile.meshrabiya.ext.writeAddress
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotRequest
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotResponse
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpMessageAndPacketHeader
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.util.matchesMask
import com.ustadmobile.meshrabiya.util.uuidForMaskAndPort
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
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
    val logger: com.ustadmobile.meshrabiya.MNetLogger = com.ustadmobile.meshrabiya.MNetLogger { _, _, _, -> },
    val localNodeAddress: Int = randomApipaAddr(),
    val autoForwardInbound: Boolean = true,
    val json: Json,
    val config: NodeConfig,
): NeighborNodeManager.NeighborNodeStateChangedListener, VirtualRouter, Closeable {

    //This executor is used for direct I/O activities
    protected val connectionExecutor = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    protected val scheduledExecutor = Executors.newScheduledThreadPool(2)

    protected val coroutineScope = CoroutineScope(Dispatchers.Default + Job())

    private val neighborNodeManagers: MutableMap<Int, NeighborNodeManager> = ConcurrentHashMap()

    private val _neighborNodesState = MutableStateFlow(emptyList<NeighborNodeState>())

    private val mmcpMessageIdAtomic = AtomicInteger()

    val _state = MutableStateFlow(LocalNodeState())

    val state: Flow<LocalNodeState> = _state.asStateFlow()

    val neighborNodesState: Flow<List<NeighborNodeState>> = _neighborNodesState.asStateFlow()

    abstract val hotspotManager: MeshrabiyaWifiManager

    private val pongListeners = CopyOnWriteArrayList<PongListener>()

    protected val logPrefix: String = "[VirtualNode ${localNodeAddress.addressToDotNotation()}]"

    private val neighborConnectionManagerLock = ReentrantLock()

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
    )

    /**
     * The currently known latest originator messages that can be used to route traffic.
     */
    protected val originatorMessages: MutableMap<Int, LastOriginatorMessage> = ConcurrentHashMap()

    internal val datagramSocket = VirtualNodeDatagramSocket(
        socket = DatagramSocket(0),
        ioExecutorService = connectionExecutor,
        router = this,
        localNodeVirtualAddress = localNodeAddress,
        onMmcpHelloReceivedListener = {
            logger(Log.DEBUG, "$logPrefix onMmcpHelloReceived from ${it.address}", null)
            addNewNeighborConnection(
                address = it.address,
                port = it.port,
                neighborNodeVirtualAddr = it.virtualPacket.header.fromAddr,
                socket = it.socket
            )
        },
        logger = logger,
    )

    val allocationServiceUuid: UUID by lazy {
        uuidForMaskAndPort(uuidMask, datagramSocket.localPort).also {
            val matches = it.matchesMask(uuidMask)
            logger(Log.DEBUG, "Allocation Service UUID: matches mask ($uuidMask) = $matches", null)
        }
    }

    val allocationCharacteristicUuid: UUID by lazy {
        uuidForMaskAndPort(uuidMask, datagramSocket.localPort + 1)
    }

    private val sendOriginatingMessageRunnable = Runnable {
        val originatingMessage = MmcpOriginatorMessage(
            messageId = nextMmcpMessageId(),
            pingTimeSum = 0,
            connectConfig = _state.value.wifiState.config,
            sentTime = System.currentTimeMillis()
        )

        logger(Log.DEBUG, "$logPrefix sending originating message", null)

        route(originatingMessage.toVirtualPacket(
            toAddr = ADDR_BROADCAST,
            fromAddr = localNodeAddress,
        ))
    }

    private val _incomingMmcpMessages = MutableSharedFlow<MmcpMessageAndPacketHeader>(
        replay = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val incomingMmcpMessages: Flow<MmcpMessageAndPacketHeader> = _incomingMmcpMessages.asSharedFlow()

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

    override fun allocatePortOrThrow(protocol: Protocol, portNum: Int): Int {
        TODO("Not yet implemented")
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
    ) : Boolean {
        //This is an Mmcp message
        try {
            val mmcpMessage = MmcpMessage.fromVirtualPacket(packet)
            val from = packet.header.fromAddr
            logger(Log.DEBUG, "$logPrefix received MMCP message (${mmcpMessage::class.simpleName}) " +
                    "from ${from.addressToDotNotation()}", null)

            val isBroadcast = packet.isBroadcast()
            val isToThisNode = packet.header.toAddr == localNodeAddress

            var shouldRoute = true

            when {
                mmcpMessage is MmcpPing && isToThisNode -> {
                    logger(Log.DEBUG, "$logPrefix Received ping(id=${mmcpMessage.messageId}) from ${from.addressToDotNotation()}", null)
                    //send pong
                    val pongMessage = MmcpPong(
                        messageId = nextMmcpMessageId(),
                        replyToMessageId = mmcpMessage.messageId
                    )

                    val replyPacket = pongMessage.toVirtualPacket(
                        toAddr = from,
                        fromAddr = localNodeAddress
                    )

                    logger(Log.DEBUG, "$logPrefix Sending pong to ${from.addressToDotNotation()}", null)
                    route(replyPacket)
                }

                mmcpMessage is MmcpPong && isToThisNode -> {
                    logger(Log.DEBUG, "$logPrefix Received pong(id=${mmcpMessage.messageId})}", null)
                    pongListeners.forEach {
                        it.onPongReceived(from, mmcpMessage)
                    }
                }

                mmcpMessage is MmcpHotspotRequest && isToThisNode -> {
                    logger(Log.INFO, "$logPrefix Received hotspotrequest (id=${mmcpMessage.messageId})", null)
                    coroutineScope.launch {
                        val hotspotResult = hotspotManager.requestHotspot(
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
                    logger(Log.DEBUG, "$logPrefix received originating message from " +
                            "${packet.header.fromAddr.addressToDotNotation()} via ${packet.header.lastHopAddr.addressToDotNotation()}",
                        null)
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
                    val isMoreRecentOrBetter = mmcpMessage.sentTime > currentlyKnownSentTime
                            || packet.header.hopCount < currentlyKnownHopCount

                    logger(Log.DEBUG, "$logPrefix received originating message from " +
                            "${packet.header.fromAddr.addressToDotNotation()} via ${packet.header.lastHopAddr.addressToDotNotation()}" +
                            " hopCount=${packet.header.hopCount} sentTime=${mmcpMessage.sentTime} " +
                            " Currently known: senttime=$currentlyKnownSentTime  hop count = $currentlyKnownHopCount " +
                            "isMoreRecentOrBetter=$isMoreRecentOrBetter ",
                        null)

                    if(currentOriginatorMessage == null || isMoreRecentOrBetter) {
                        originatorMessages[packet.header.fromAddr] = LastOriginatorMessage(
                            originatorMessage = mmcpMessage,
                            timeReceived = System.currentTimeMillis(),
                            lastHopAddr = packet.header.lastHopAddr,
                            hopCount = packet.header.hopCount
                        )
                        logger(Log.DEBUG, "$logPrefix update originator messages: " +
                                "currently known nodes = ${originatorMessages.keys.joinToString { it.addressToDotNotation() }}", null)

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
                if(!onIncomingMmcpMessage(packet)){
                    //It was determined that this packet should go no further by MMCP processing
                    logger(Log.DEBUG, "Drop mmcp packet from ${packet.header.fromAddr}", null)
                }
            }

            if(packet.header.toAddr == localNodeAddress) {
                //this is an incoming packet - give to the destination virtual socket/forwarding
            }else {
                //packet needs to be sent to next hop / destination
                val toAddr = packet.header.toAddr

                packet.updateLastHopAddrAndIncrementHopCountInData(localNodeAddress)
                if(toAddr == ADDR_BROADCAST) {
                    neighborNodeManagers.values
                        .filter {
                            it.remoteAddress != fromLastHop && it.remoteAddress != packet.header.fromAddr
                        }.forEach {
                            logger(Log.DEBUG, "$logPrefix broadcast packet " +
                                    "from=${packet.header.fromAddr.addressToDotNotation()} " +
                                    "lasthop=${fromLastHop.addressToDotNotation()} " +
                                    "send to ${it.remoteAddress.addressToDotNotation()}", null)
                            it.send(packet)
                        }
                }else {
                    val neighborManager = neighborNodeManagers[packet.header.toAddr]
                    if(neighborManager != null) {
                        logger(Log.DEBUG, "$logPrefix ${packet.header.toAddr.addressToDotNotation()}", null)
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
                is5GhzSupported = hotspotManager.is5GhzSupported
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

    open suspend fun setWifiHotspotEnabled(enabled: Boolean) {
        if(enabled){
            hotspotManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    is5GhzSupported = hotspotManager.is5GhzSupported
                )
            )
        }
    }





    override fun close() {
        neighborNodeManagers.values.forEach {
            it.close()
        }
        datagramSocket.close()

        connectionExecutor.shutdown()
        scheduledExecutor.shutdown()
    }

}