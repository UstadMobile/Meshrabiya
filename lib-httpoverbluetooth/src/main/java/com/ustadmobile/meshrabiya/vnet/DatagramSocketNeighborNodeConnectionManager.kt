package com.ustadmobile.meshrabiya.vnet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

class DatagramSocketNeighborNodeConnectionManager(
    connectionId: Int,
    router: IRouter,
    localNodeAddr: Int,
    remoteNodeAddr: Int,
    private val datagramSocket: VirtualNodeDatagramSocket,
    private val neighborAddress: InetAddress,
    private val neighborPort: Int,
) : AbstractNeighborNodeConnectionManager(
    connectionId = connectionId,
    router = router,
    localNodeVirtualAddr = localNodeAddr,
    remoteNodeVirtualAddr = remoteNodeAddr,
){

    private val _state = MutableStateFlow(NeighborNodeConnectionState(
        remoteNodeAddr = remoteNodeAddr,
        connectionId = connectionId,
        connectionState = StreamConnectionNeighborNodeConnectionManager.ConnectionState.CONNECTED,
    ))

    override val state: Flow<NeighborNodeConnectionState> = _state.asStateFlow()

    override fun send(packet: VirtualPacket) {
        datagramSocket.send(
            nextHopAddress = neighborAddress,
            nextHopPort = neighborPort,
            virtualPacket = packet
        )
    }

    override fun close() {

    }
}