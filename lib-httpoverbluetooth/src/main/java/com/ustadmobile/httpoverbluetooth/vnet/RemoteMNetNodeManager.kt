package com.ustadmobile.httpoverbluetooth.vnet

import android.util.Log
import com.ustadmobile.httpoverbluetooth.MNetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
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
    val localMNodeAddress: Int,
    private val executor: ExecutorService,
    private val logger: MNetLogger,
    private val listener: RemoteMNodeManagerListener,
) {

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
            connectionIdAtomic.getAndIncrement(), iSocket, logger
        )

        connections.add(newConnectionManager)

        executor.submit(newConnectionManager)

        val newState = nodeState.updateAndGet { prev ->
            prev.copy(numConnections = prev.numConnections + 1)
        }

        listener.onNodeStateChanged(newState)
    }

}
