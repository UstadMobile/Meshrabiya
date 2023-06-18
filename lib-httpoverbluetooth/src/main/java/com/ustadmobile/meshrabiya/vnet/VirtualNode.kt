package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.appendOrReplace
import com.ustadmobile.meshrabiya.ext.readRemoteAddress
import com.ustadmobile.meshrabiya.ext.writeAddress
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotState
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotStatus
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotSubReservation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
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
 * Streams: use KWIK?
Open local port on sender,

Each node has a UDP port
When packet is received: unwrap, check is it

For general forwarding:
Just wrap/unwrap each packet. Then forward to nexthop.

Accepting (Server):
1. open local QUIC server on given port

Connecting (client):
1. Open local port which will rewrite/forward


 *
 *
 * Addresses are 32 bit integers in the APIPA range
 */
open class VirtualNode(
    val allocationServiceUuid: UUID,
    val allocationCharacteristicUuid: UUID,
    val logger: com.ustadmobile.meshrabiya.MNetLogger = com.ustadmobile.meshrabiya.MNetLogger { _, _, _, -> },
    val localNodeAddress: Int = randomApipaAddr(),
): NeighborNodeManager.RemoteMNodeManagerListener, IRouter, Closeable {

    //This executor is used for direct I/O activities
    protected val connectionExecutor = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    protected val scheduledExecutor = Executors.newScheduledThreadPool(2)

    private val neighborNodeManagers: MutableMap<Int, NeighborNodeManager> = ConcurrentHashMap()

    private val _neighborNodesState = MutableStateFlow(emptyList<NeighborNodeState>())

    val neighborNodesState: Flow<List<NeighborNodeState>> = _neighborNodesState.asStateFlow()

    open val localHotSpotState: Flow<LocalHotspotState> = MutableStateFlow(
        LocalHotspotState(
            status = LocalHotspotStatus.STOPPED
        )
    )

    private val pongListeners = CopyOnWriteArrayList<PongListener>()

    protected val logPrefix: String = "[VirtualNode ${localNodeAddress.addressToDotNotation()}]"

    override fun onNodeStateChanged(remoteMNodeState: NeighborNodeState) {
        _neighborNodesState.update { prev ->
            prev.appendOrReplace(remoteMNodeState) { it.remoteAddress == remoteMNodeState.remoteAddress }
        }
    }


    override fun route(
        from: Int,
        packet: VirtualPacket
    ) {
        if(packet.header.toAddr == localNodeAddress) {
            if(packet.header.toPort == 0.toShort()) {
                //This is an Mmcp message
                val mmcpMessage = MmcpMessage.fromBytes(packet.payload, packet.payloadOffset,
                    packet.header.payloadSize)

                when(mmcpMessage) {
                    is MmcpPing -> {
                        logger(Log.DEBUG, "$logPrefix Received ping from ${from.addressToDotNotation()}", null)
                        //send pong
                        val pongMessage = MmcpPong(mmcpMessage.payload)
                        val pongBytes = pongMessage.toBytes()
                        val replyPacket = VirtualPacket(
                            header = VirtualPacketHeader(
                                toAddr = from,
                                toPort = 0,
                                fromAddr = localNodeAddress,
                                fromPort = 0,
                                hopCount = 0,
                                maxHops = 5,
                                payloadSize = pongBytes.size
                            ),
                            payload = pongBytes
                        )

                        logger(Log.DEBUG, "$logPrefix Sending pong to ${from.addressToDotNotation()}", null)
                        route(localNodeAddress, replyPacket)
                    }
                    is MmcpPong -> {
                        pongListeners.forEach {
                            it.onPongReceived(from, mmcpMessage)
                        }
                    }
                }
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

    fun handleNewSocketConnection(
        iSocket: ISocket
    ) {
        logger(Log.DEBUG, "MNode.handleNewBluetoothConnection: write address: " +
                localNodeAddress.addressToDotNotation(),null)

        iSocket.outputStream.writeAddress(localNodeAddress)
        iSocket.outputStream.flush()

        val remoteAddress = iSocket.inStream.readRemoteAddress()
        logger(Log.DEBUG, "MNode.handleNewBluetoothConnection: read remote address: " +
                remoteAddress.addressToDotNotation(),null)

        val newRemoteNodeManager = NeighborNodeManager(
            remoteAddress = remoteAddress,
            router = this,
            localNodeAddress = localNodeAddress,
            connectionExecutor = connectionExecutor,
            scheduledExecutor = scheduledExecutor,
            logger = logger,
            listener = this,
        ).also {
            it.addConnection(iSocket)
        }

        neighborNodeManagers[remoteAddress] = newRemoteNodeManager
    }


    fun addPongListener(listener: PongListener) {
        pongListeners += listener
    }

    fun removePongListener(listener: PongListener) {
        pongListeners -= listener
    }

    override fun close() {
        neighborNodeManagers.values.forEach {
            it.close()
        }

        connectionExecutor.shutdown()
        scheduledExecutor.shutdown()
    }

}