package com.ustadmobile.meshrabiya.test

import com.ustadmobile.meshrabiya.vnet.VirtualNode
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress

fun VirtualNode.connectTo(other: VirtualNode, timeout: Long = 5000) {
    addNewNeighborConnection(
        address = InetAddress.getLoopbackAddress(),
        port = other.localDatagramPort,
        neighborNodeVirtualAddr = other.localNodeAddress,
        socket = this.datagramSocket
    )

    //wait for connections to be ready
    runBlocking {
        withTimeout(timeout) {
            neighborNodesState.filter { neighbors ->
                neighbors.any { it.remoteAddress == other.localNodeAddress }
            }.first()

            other.neighborNodesState.filter { neighbors ->
                neighbors.any { it.remoteAddress == this@connectTo.localNodeAddress }
            }.first()
        }
    }
}