package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.io.IOException
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
)

/**
 * A neighbor node is a node that is directly connected to this node. There may be multiple underlying
 * connections (bluetooth, WiFi over local hotspot, normal WiFi, etc).
 */
class NeighborNodeManager(
    val remoteAddress: Int,
    val localNodeAddress: Int,
    private val router: IRouter,
    private val connectionExecutor: ExecutorService,
    private val scheduledExecutor: ScheduledExecutorService,
    private val logger: com.ustadmobile.meshrabiya.MNetLogger,
    private val listener: RemoteMNodeManagerListener,
): BluetoothNeighborNodeConnectionManager.RemoteMNodeConnectionListener {

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
        logger(Log.DEBUG, "RemoteMNodeManager: addConnection", null)

        val newConnectionManager = BluetoothNeighborNodeConnectionManager(
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
            prev.copy(numConnections = prev.numConnections + 1)
        }

        listener.onNodeStateChanged(newState)
    }

    override fun onConnectionStateChanged(connectionState: NeighborNodeConnectionState) {
        if(connectionState.connectionState == BluetoothNeighborNodeConnectionManager.ConnectionState.DISCONNECTED) {
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