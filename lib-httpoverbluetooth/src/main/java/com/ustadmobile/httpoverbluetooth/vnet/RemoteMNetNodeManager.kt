package com.ustadmobile.httpoverbluetooth.vnet

import android.util.Log
import com.ustadmobile.httpoverbluetooth.MNetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

data class RemoteMNodeState(
    val remoteAddress: Int,
    val numConnections: Int,
    val pingTime: Int,
)

/**
 * Represents a remote node that is directly connected. There may be multiple underlying connections
 * (bluetooth, WiFi over local hotspot, normal WiFi, etc)
 */
class RemoteMNodeManager(
    val remoteAddress: Int,
    @Suppress("unused")//Reserved for future use
    val localMNodeAddress: Int,
    private val connectionExecutor: ExecutorService,
    private val scheduledExecutor: ScheduledExecutorService,
    private val logger: MNetLogger,
    private val listener: RemoteMNodeManagerListener,
): RemoteMNodeConnectionManager.RemoteMNodeConnectionListener {

    interface RemoteMNodeManagerListener {

        fun onNodeStateChanged(
            remoteMNodeState: RemoteMNodeState
        )

    }

    private val connections = CopyOnWriteArrayList<RemoteMNodeConnectionManager>()

    private val connectionIdAtomic = AtomicInteger(1)

    private val nodeState = MutableStateFlow(RemoteMNodeState(remoteAddress = remoteAddress, 0, 0))

    fun addConnection(
        iSocket: ISocket
    ) {
        logger(Log.DEBUG, "RemoteMNodeManager: addConnection", null)

        val newConnectionManager = RemoteMNodeConnectionManager(
            connectionId = connectionIdAtomic.getAndIncrement(),
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

    override fun onConnectionStateChanged(connectionState: RemoteMNodeConnectionState) {
        if(connectionState.connectionState == RemoteMNodeConnectionManager.ConnectionState.DISCONNECTED) {
            connections.removeIf { it.connectionId == connectionState.connectionId }
        }else {
            val newState = nodeState.updateAndGet { prev ->
                prev.copy(pingTime = connectionState.pingTime)
            }
            listener.onNodeStateChanged(newState)
        }
    }

    fun close() {

    }

}
