package com.ustadmobile.meshrabiya.vnet

import kotlinx.coroutines.flow.Flow
import java.io.Closeable


data class NeighborNodeConnectionState(
    val remoteNodeAddr: Int,
    val connectionId: Int,
    val pingTime: Int = 0,
    val connectionState: BluetoothNeighborNodeConnectionManager.ConnectionState,
    val pingAttempts: Int = 0,
    val pingsReceived: Int = 0,
)


abstract class AbstractNeighborNodeConnectionManager(
    val connectionId: Int,
    protected val router: IRouter,
    protected val localNodeAddr: Int,
    protected val remoteNodeAddr: Int
) : Closeable{

    abstract val state: Flow<NeighborNodeConnectionState>

    abstract fun send(packet: VirtualPacket)




}