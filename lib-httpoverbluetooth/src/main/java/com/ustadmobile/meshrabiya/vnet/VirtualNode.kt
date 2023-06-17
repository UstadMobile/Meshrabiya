package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ustadmobile.meshrabiya.client.UuidAllocationClient
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.appendOrReplace
import com.ustadmobile.meshrabiya.ext.readRemoteAddress
import com.ustadmobile.meshrabiya.ext.writeAddress
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.server.AbstractHttpOverBluetoothServer
import com.ustadmobile.meshrabiya.server.OnUuidAllocatedListener
import com.ustadmobile.meshrabiya.server.UuidAllocationServer
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotManager
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotSubReservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.Exception
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
    val appContext: Context,
    val allocationServiceUuid: UUID,
    val allocationCharacteristicUuid: UUID,
    val logger: com.ustadmobile.meshrabiya.MNetLogger = com.ustadmobile.meshrabiya.MNetLogger { _, _, _, -> },
    val localMNodeAddress: Int = randomApipaAddr(),
): NeighborNodeManager.RemoteMNodeManagerListener, IRouter {

    //This executor is used for direct I/O activities
    private val connectionExecutor = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    private val scheduledExecutor = Executors.newScheduledThreadPool(2)

    private val bluetoothManager: BluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val neighborNodeManagers: MutableMap<Int, NeighborNodeManager> = ConcurrentHashMap()

    private val _neighborNodesState = MutableStateFlow(emptyList<NeighborNodeState>())

    val neighborNodesState: Flow<List<NeighborNodeState>> = _neighborNodesState.asStateFlow()

    private val localHotspotManager = LocalHotspotManager(appContext, logger)

    val localHotSpotState = localHotspotManager.state

    private val pongListeners = CopyOnWriteArrayList<PongListener>()

    /**
     * Listener that opens a bluetooth server socket
     */
    private val onUuidAllocatedListener = OnUuidAllocatedListener { uuid ->
        val serverSocket: BluetoothServerSocket? = try {
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("mnet", uuid)
        } catch (e: SecurityException) {
            null
        }

        val clientSocket: BluetoothSocket? = try {
            logger(Log.DEBUG, "Waiting for client to connect on bluetooth classic UUID $uuid", null)
            serverSocket?.accept(AbstractHttpOverBluetoothServer.SOCKET_ACCEPT_TIMEOUT) //Can add timeout here
        } catch (e: IOException) {
            logger(Log.ERROR,"Exception accepting socket", e)
            null
        }

        clientSocket?.also { socket ->
            try {
                val iSocket = socket.asISocket()
                handleNewBluetoothConnection(iSocket)
            }catch(e: SecurityException) {
                logger(Log.ERROR, "Accept new node via Bluetooth: security exception exception", e)
            }catch(e: Exception) {
                logger(Log.ERROR, "Accept new node via Bluetooth: connect exception", e)
            }
        }
    }

    override fun onNodeStateChanged(remoteMNodeState: NeighborNodeState) {
        _neighborNodesState.update { prev ->
            prev.appendOrReplace(remoteMNodeState) { it.remoteAddress == remoteMNodeState.remoteAddress }
        }
    }

    private val uuidAllocationServer = UuidAllocationServer(
        appContext = appContext,
        allocationServiceUuid = allocationServiceUuid,
        allocationCharacteristicUuid = allocationCharacteristicUuid,
        onUuidAllocated = onUuidAllocatedListener,
    )

    private val uuidAllocationClient = UuidAllocationClient(appContext, onLog = { _, _, _ -> } )

    init {
        uuidAllocationServer.start()
    }

    private fun handleNewBluetoothConnection(
        iSocket: ISocket
    ) {
        logger(Log.DEBUG, "MNode.handleNewBluetoothConnection: write address: " +
                localMNodeAddress.addressToDotNotation(),null)

        iSocket.outputStream.writeAddress(localMNodeAddress)
        iSocket.outputStream.flush()

        val remoteAddress = iSocket.inStream.readRemoteAddress()
        logger(Log.DEBUG, "MNode.handleNewBluetoothConnection: read remote address: " +
                remoteAddress.addressToDotNotation(),null)

        val newRemoteNodeManager = NeighborNodeManager(
            remoteAddress = remoteAddress,
            router = this,
            localNodeAddress = localMNodeAddress,
            connectionExecutor = connectionExecutor,
            scheduledExecutor = scheduledExecutor,
            logger = logger,
            listener = this,
        ).also {
            it.addConnection(iSocket)
        }

        neighborNodeManagers[remoteAddress] = newRemoteNodeManager
    }

    suspend fun addBluetoothConnection(
        remoteBluetooothAddr: String,
        remoteAllocationServiceUuid: UUID,
        remoteAllocationCharacteristicUuid: UUID,
    ) {
        logger(Log.DEBUG, "AddBluetoothConnection to $remoteBluetooothAddr", null)
        withContext(Dispatchers.IO) {
            val dataUuid = uuidAllocationClient.requestUuidAllocation(
                remoteAddress = remoteBluetooothAddr,
                remoteServiceUuid = remoteAllocationServiceUuid,
                remoteCharacteristicUuid = remoteAllocationCharacteristicUuid,
            )

            val remoteDevice = bluetoothAdapter?.getRemoteDevice(remoteBluetooothAddr)

            var socket: BluetoothSocket? = null
            try {
                logger(Log.DEBUG, "AddBluetoothConnection : got data UUID: $dataUuid, " +
                        "creating rfcomm sockettoservice", null)
                socket = remoteDevice?.createInsecureRfcommSocketToServiceRecord(
                    dataUuid
                )

                socket?.also {
                    logger(Log.DEBUG, "AddBluetoothConnection: connecting", null)
                    it.connect()
                    logger(Log.DEBUG, "AddBluetoothConnection: connected, submit runnable", null)
                    val iSocket = it.asISocket()
                    handleNewBluetoothConnection(iSocket)
                }

            }catch(e:SecurityException){
                logger(Log.ERROR, "addBluetoothConnection: SecurityException", e)
            }catch(e: Exception) {
                logger(Log.ERROR, "addBluetoothConnection: other exception", e)
            }
        }
    }

    override fun route(
        from: Int,
        packet: VirtualPacket
    ) {
        if(packet.header.toAddr == localMNodeAddress) {
            if(packet.header.toPort == 0.toShort()) {
                //This is an Mmcp message
                val mmcpMessage = MmcpMessage.fromBytes(packet.payload, packet.payloadOffset,
                    packet.header.payloadSize)

                when(mmcpMessage) {
                    is MmcpPing -> {
                        logger(Log.DEBUG, "Received ping from ${from.addressToDotNotation()}", null)
                        //send pong
                        val pongMessage = MmcpPong(mmcpMessage.payload)
                        val pongBytes = pongMessage.toBytes()
                        val replyPacket = VirtualPacket(
                            header = VirtualPacketHeader(
                                toAddr = from,
                                toPort = 0,
                                fromAddr = localMNodeAddress,
                                fromPort = 0,
                                hopCount = 0,
                                maxHops = 5,
                                payloadSize = pongBytes.size
                            ),
                            payload = pongBytes
                        )

                        logger(Log.DEBUG, "Sending pong to ${from.addressToDotNotation()}", null)
                        route(localMNodeAddress, replyPacket)
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
                logger(Log.DEBUG, "Send packet to ${packet.header.toAddr.addressToDotNotation()}", null)
                neighborManager.send(packet)
            }else {
                //not routeable
                logger(Log.ERROR,
                    "Cannot route packet to ${packet.header.toAddr.addressToDotNotation()}",
                null)
            }
        }
    }

    suspend fun requestLocalHotspot() : LocalHotspotSubReservation {
        return localHotspotManager.request()
    }

    fun addPongListener(listener: PongListener) {
        pongListeners += listener
    }

    fun removePongListener(listener: PongListener) {
        pongListeners -= listener
    }

}