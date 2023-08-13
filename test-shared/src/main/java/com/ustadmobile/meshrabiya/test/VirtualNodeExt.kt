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
            state.filter { it.originatorMessages.containsKey(other.localNodeAddress) }
                .first()

            other.state.filter {
                it.originatorMessages.containsKey(localNodeAddress)
            }.first()

        }
    }
}