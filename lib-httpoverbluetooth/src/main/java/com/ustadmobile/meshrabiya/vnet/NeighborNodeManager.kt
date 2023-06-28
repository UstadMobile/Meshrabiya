package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

data class NeighborNodeState(
    val remoteAddress: Int,
    val numConnections: Int,
    val pingTime: Int,
    val pingsSent: Int = 0,
    val pingsReceived: Int = 0,
    val hasBluetoothConnection: Boolean = false,
    val hasWifiConnection: Boolean = false,
)

/**
 * A neighbor node is a node that is directly connected to this node. There may be multiple underlying
 * connections (bluetooth, WiFi over local hotspot, normal WiFi, etc).
 */
class NeighborNodeManager(
    val remoteAddress: Int,
    val localNodeAddress: Int,
    private val router: VirtualRouter,
    private val connectionExecutor: ExecutorService,
    private val scheduledExecutor: ScheduledExecutorService,
    private val logger: com.ustadmobile.meshrabiya.MNetLogger,
    private val listener: RemoteMNodeManagerListener,
): StreamConnectionNeighborNodeConnectionManager.RemoteMNodeConnectionListener {

    private val logPrefix: String = "[NeighborNodeManager ${localNodeAddress.addressToDotNotation()}->${remoteAddress.addressToDotNotation()}]"

    interface RemoteMNodeManagerListener {

        fun onNodeStateChanged(
            remoteMNodeState: NeighborNodeState
        )

    }

    private val connections = CopyOnWriteArrayList<AbstractNeighborNodeConnectionManager>()

    private val connectionIdAtomic = AtomicInteger(1)

    private val nodeState = MutableStateFlow(NeighborNodeState(remoteAddress = remoteAddress, 0, 0))

    fun addConnection(
        iSocket: ISocket
    ) {
        logger(Log.DEBUG, "$logPrefix addIoStreamConnection", null)

        val newConnectionManager = StreamConnectionNeighborNodeConnectionManager(
            connectionId = connectionIdAtomic.getAndIncrement(),
            router = router,
            localNodeAddr = localNodeAddress,
            remoteNodeAddr = remoteAddress,
            socket = iSocket,
            logger = logger,
            stateListener = this,
            executor = connectionExecutor,
            scheduledExecutor = scheduledExecutor,
        )

        connections.add(newConnectionManager)

        val newState = nodeState.updateAndGet { prev ->
            prev.copy(
                numConnections = prev.numConnections + 1,
                hasBluetoothConnection = true,
            )
        }

        listener.onNodeStateChanged(newState)
    }

    fun addDatagramConnection(
        address: InetAddress,
        port: Int,
        datagramSocket: VirtualNodeDatagramSocket,
    ) {
        if(connections.any {
            it is DatagramSocketNeighborNodeConnectionManager &&
                    it.neighborAddress == address &&
                    it.neighborPort == port
        }) {
            logger(Log.DEBUG, "$logPrefix addDataGramConnection: Already have connection for address/port", null)
            return
        }

        val connectionManager = DatagramSocketNeighborNodeConnectionManager(
            connectionId = connectionIdAtomic.getAndIncrement(),
            router = router,
            localNodeAddr = localNodeAddress,
            remoteNodeAddr = remoteAddress,
            datagramSocket = datagramSocket,
            neighborAddress = address,
            neighborPort = port,
        )

        connections.add(connectionManager)

        val newState = nodeState.updateAndGet { prev ->
            prev.copy(
                numConnections = prev.numConnections + 1,
                hasWifiConnection = true,
            )
        }

        listener.onNodeStateChanged(newState)
    }

    override fun onConnectionStateChanged(connectionState: NeighborNodeConnectionState) {
        if(connectionState.connectionState == StreamConnectionNeighborNodeConnectionManager.ConnectionState.DISCONNECTED) {
            connections.removeIf { it.connectionId == connectionState.connectionId }
        }else {
            val newState = nodeState.updateAndGet { prev ->
                prev.copy(
                    pingTime = connectionState.pingTime,
                    pingsSent = connectionState.pingAttempts,
                    pingsReceived = connectionState.pingsReceived,
                )
            }
            listener.onNodeStateChanged(newState)
        }
    }

    fun send(virtualPacket: VirtualPacket) {
        val sendConnection = connections.firstOrNull() // do better selection based on QoS speed etc. here
        if(sendConnection != null) {
            sendConnection.send(virtualPacket)
        }else {
            val noConnectionException = IOException("No connection available")
            logger(Log.ERROR, "Cannot send packet", noConnectionException)
            throw noConnectionException
        }
    }


    fun close() {

    }

}
