package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class DatagramSocketNeighborNodeConnectionManager(
    connectionId: Int,
    router: VirtualRouter,
    localNodeAddr: Int,
    remoteNodeAddr: Int,
    private val datagramSocket: VirtualNodeDatagramSocket,
    internal val neighborAddress: InetAddress,
    internal val neighborPort: Int,
    private val scheduledExecutor: ScheduledExecutorService,
    private val logger: MNetLogger,
) : AbstractNeighborNodeConnectionManager(
    connectionId = connectionId,
    router = router,
    localNodeVirtualAddr = localNodeAddr,
    remoteNodeVirtualAddr = remoteNodeAddr,
) {

    private val logPrefix = "[DatagramSocketNeighborConnMgr ${localNodeVirtualAddr.addressToDotNotation()} - " +
            "${remoteNodeVirtualAddr.addressToDotNotation()}] "

    data class PendingPing(val ping: MmcpPing, val timesent: Long)

    private val pendingPings = CopyOnWriteArrayList<PendingPing>()

    private val neighborMmcpPacketListener = VirtualNodeDatagramSocket.NeighborMmcpMessageReceivedListener {
        if(it.virtualPacket.header.fromAddr != remoteNodeVirtualAddr)
            return@NeighborMmcpMessageReceivedListener

        val pong = it.mmcpMessage as? MmcpPong ?: return@NeighborMmcpMessageReceivedListener

        val pendingPing = pendingPings.firstOrNull {
            it.ping.messageId == pong.replyToMessageId
        } ?: return@NeighborMmcpMessageReceivedListener

        pendingPings.removeAll { it.ping.messageId == pendingPing.ping.messageId }
        val responseTime = System.currentTimeMillis() - pendingPing.timesent
        logger(Log.DEBUG, "$logPrefix pong(replyTo=${pendingPing.ping.messageId}) received time=${responseTime}ms", null)

        _state.update { prev ->
            prev.copy(
                pingTime = responseTime.toInt(),
                pingsReceived = prev.pingsReceived + 1,
            )
        }
    }

    private val pingRunnable = Runnable {
        val pingToSend = MmcpPing(router.nextMmcpMessageId())
        pendingPings += PendingPing(pingToSend, System.currentTimeMillis())
        logger(Log.DEBUG, "$logPrefix send ping", null)
        send(pingToSend.toVirtualPacket(
            toAddr = 0,
            fromAddr = localNodeAddr,
        ))
    }

    private val _state = MutableStateFlow(NeighborNodeConnectionState(
        remoteNodeAddr = remoteNodeAddr,
        connectionId = connectionId,
        connectionState = StreamConnectionNeighborNodeConnectionManager.ConnectionState.CONNECTED,
    ))

    override val state: Flow<NeighborNodeConnectionState> = _state.asStateFlow()

    private val pingFuture: Future<*>

    init {
        datagramSocket.addPacketReceivedListener(neighborMmcpPacketListener)
        pingFuture = scheduledExecutor.scheduleAtFixedRate(pingRunnable, 1000, 12000, TimeUnit.MILLISECONDS)
    }

    override fun send(packet: VirtualPacket) {
        datagramSocket.send(
            nextHopAddress = neighborAddress,
            nextHopPort = neighborPort,
            virtualPacket = packet
        )
    }

    override fun close() {
        datagramSocket.removePacketReceivedListener(neighborMmcpPacketListener)
        pingFuture.cancel(true)
    }

    companion object {
        const val PING_INITIA_DELAY = 1000

        const val PING_INTERVAL = 12000

        const val PING_TIMEOUT = 10000
    }
}