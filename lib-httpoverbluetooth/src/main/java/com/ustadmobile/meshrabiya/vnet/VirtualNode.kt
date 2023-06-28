package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.appendOrReplace
import com.ustadmobile.meshrabiya.ext.readRemoteAddress
import com.ustadmobile.meshrabiya.ext.writeAddress
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotRequest
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotResponse
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.util.matchesMask
import com.ustadmobile.meshrabiya.util.uuidForMaskAndPort
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiState
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
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
): NeighborNodeManager.RemoteMNodeManagerListener, VirtualRouter, Closeable {

    //This executor is used for direct I/O activities
    protected val connectionExecutor = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    protected val scheduledExecutor = Executors.newScheduledThreadPool(2)

    private val coroutineScope = CoroutineScope(Dispatchers.Default + Job())

    private val neighborNodeManagers: MutableMap<Int, NeighborNodeManager> = ConcurrentHashMap()

    private val _neighborNodesState = MutableStateFlow(emptyList<NeighborNodeState>())

    private val mmcpMessageIdAtomic = AtomicInteger()

    val neighborNodesState: Flow<List<NeighborNodeState>> = _neighborNodesState.asStateFlow()

    abstract val hotspotManager: MeshrabiyaWifiManager

    val localHotSpotState: Flow<MeshrabiyaWifiState>
        get() = hotspotManager.state

    private val pongListeners = CopyOnWriteArrayList<PongListener>()

    protected val logPrefix: String = "[VirtualNode ${localNodeAddress.addressToDotNotation()}]"

    protected val datagramSocket = VirtualNodeDatagramSocket(
        port = port,
        ioExecutorService = connectionExecutor,
        router = this,
        localNodeVirtualAddress = localNodeAddress,
        onMmcpHelloReceivedListener = {
            logger(Log.DEBUG, "$logPrefix onMmcpHelloReceived from ${it.address}", null)
            handleNewDatagramNeighborConnection(
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

    private val _incomingMmcpMessages = MutableSharedFlow<MmcpMessage>(
        replay = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val incomingMmcpMessages: Flow<MmcpMessage> = _incomingMmcpMessages.asSharedFlow()

    override fun onNodeStateChanged(remoteMNodeState: NeighborNodeState) {
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

    override fun route(
        packet: VirtualPacket
    ) {
        if(packet.header.toAddr == localNodeAddress) {
            if(packet.header.toPort == 0) {
                //This is an Mmcp message
                val mmcpMessage = MmcpMessage.fromVirtualPacket(packet)
                val from = packet.header.fromAddr

                when(mmcpMessage) {
                    is MmcpPing -> {
                        logger(Log.DEBUG, "$logPrefix Received ping(id=${mmcpMessage.messageId}) from ${from.addressToDotNotation()}", null)
                        //send pong
                        val pongMessage = MmcpPong(mmcpMessage.messageId)
                        val replyPacket = pongMessage.toVirtualPacket(
                            toAddr = from,
                            fromAddr = localNodeAddress
                        )

                        logger(Log.DEBUG, "$logPrefix Sending pong to ${from.addressToDotNotation()}", null)
                        route(replyPacket)
                    }
                    is MmcpPong -> {
                        logger(Log.DEBUG, "$logPrefix Received pong(id=${mmcpMessage.messageId})}", null)
                        pongListeners.forEach {
                            it.onPongReceived(from, mmcpMessage)
                        }
                    }
                    is MmcpHotspotRequest -> {
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
                    else -> {
                        // do nothing
                    }
                }

                _incomingMmcpMessages.tryEmit(mmcpMessage)
            }
        }else {
            //packet needs to be sent to nexthop
            val neighborManager = neighborNodeManagers[packet.header.toAddr]
            if(neighborManager != null) {
                logger(Log.DEBUG, "$logPrefix ${packet.header.toAddr.addressToDotNotation()}", null)
                neighborManager.send(packet)
            }else {
                //not routeable
                logger(Log.ERROR,
                    "${logPrefix }Cannot route packet to ${packet.header.toAddr.addressToDotNotation()}",
                null)
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
    protected fun handleNewDatagramNeighborConnection(
        address: InetAddress,
        port: Int,
        neighborNodeVirtualAddr: Int,
        socket: VirtualNodeDatagramSocket,
    ) {
        logger(Log.DEBUG,
            "$logPrefix handleNewDatagramNeighborConnection connection to virtual addr " +
                    "${neighborNodeVirtualAddr.addressToDotNotation()} " +
                    "via datagram to $address:$port",
            null
        )

        val remoteNodeManager = getOrCreateNeighborNodeManager(neighborNodeVirtualAddr)
        remoteNodeManager.addDatagramConnection(address, port, socket)
    }

    /**
     * Add a new datagram neighbor connection :
     *
     *  1. Send a MmcpHello to the remote address/port (virtual address = 0)
     *  2. Wait for a reply MmcpAck that gives the remote virtual address
     *
     *  On the remote side receiving a Hello will trigger the DatagramSocket's
     *  OnNewIncomingConnectionListener, so the remote side can also setup a connectionmanager
     */
    fun addNewDatagramNeighborConnection(
        address: InetAddress,
        port: Int,
        socket: VirtualNodeDatagramSocket
    ) {
        logger(
            Log.DEBUG,
            "$logPrefix addNewDatagramNeighborConnection to addr=$address port=$port ",
            null
        )

        val addrResponseLatch = CountDownLatch(1)
        val neighborVirtualAddr = AtomicInteger(0)
        val helloMessageId = nextMmcpMessageId()

        val packetReceivedListener = VirtualNodeDatagramSocket.PacketReceivedListener {
            if(it.address == address && it.port == port) {
                val virtualPacket = VirtualPacket.fromDatagramPacket(it)
                neighborVirtualAddr.set(virtualPacket.header.fromAddr)
                addrResponseLatch.countDown()
            }
        }

        try {
            logger(
                Log.DEBUG,
                "$logPrefix addNewDatagramNeighborConnection Sending hello to $address:$port",
                null
            )
            socket.addPacketReceivedListener(packetReceivedListener)
            socket.sendHello(helloMessageId, address, port)
            addrResponseLatch.await(10, TimeUnit.SECONDS)

            if(neighborVirtualAddr.get() != 0) {
                handleNewDatagramNeighborConnection(
                    address, port, neighborVirtualAddr.get(), socket,
                )
            }else {
                val exception = IOException("Sent HELLO to $address, did not receive reply")
                logger(Log.ERROR, "$logPrefix addNewDatagramNeighborConnection", exception)
                throw exception
            }
        }finally {
            socket.removePacketReceivedListener(packetReceivedListener)
        }
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
        route(request.toVirtualPacket(
            toAddr = virtualAddr,
            fromAddr = localNodeAddress
        ))

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