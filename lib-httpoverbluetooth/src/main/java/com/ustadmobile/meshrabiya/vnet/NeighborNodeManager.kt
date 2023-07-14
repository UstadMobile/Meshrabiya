package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.io.Closeable
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
    private val listener: NeighborNodeStateChangedListener,
): AbstractNeighborNodeConnectionManager.OnNeighborNodeConnectionStateChangedListener, Closeable {

    private val logPrefix: String = "[NeighborNodeManager ${localNodeAddress.addressToDotNotation()}->${remoteAddress.addressToDotNotation()}]"

    interface NeighborNodeStateChangedListener {

        fun onNeighborNodeStateChanged(
            remoteMNodeState: NeighborNodeState
        )

    }

    private val connections = CopyOnWriteArrayList<AbstractNeighborNodeConnectionManager>()

    private val connectionIdAtomic = AtomicInteger(1)

    private val nodeState = MutableStateFlow(NeighborNodeState(remoteAddress = remoteAddress, 0, 0))

    val pingTime: Short
        get() = nodeState.value.pingTime.toShort()

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

        listener.onNeighborNodeStateChanged(newState)
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
            scheduledExecutor = scheduledExecutor,
            logger = logger,
            stateChangeListener = this,
        )
        connections.add(connectionManager)

        val newState = nodeState.updateAndGet { prev ->
            prev.copy(
                numConnections = prev.numConnections + 1,
                hasWifiConnection = true,
            )
        }

        listener.onNeighborNodeStateChanged(newState)
    }

    override fun onNeighborNodeConnectionStateChanged(state: NeighborNodeConnectionState) {
        if(state.connectionState == StreamConnectionNeighborNodeConnectionManager.ConnectionState.DISCONNECTED) {
            connections.firstOrNull { it.connectionId == state.connectionId }?.also { connectionManager ->
                connections.remove(connectionManager)
            }
        }else {
            val newState = nodeState.updateAndGet { prev ->
                prev.copy(
                    pingTime = state.pingTime,
                    pingsSent = state.pingAttempts,
                    pingsReceived = state.pingsReceived,
                )
            }
            listener.onNeighborNodeStateChanged(newState)
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


    override fun close() {
        connections.forEach {
            it.close()
        }
    }

}
