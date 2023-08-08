package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 */
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
    stateChangeListener: OnNeighborNodeConnectionStateChangedListener,
) : AbstractNeighborNodeConnectionManager(
    connectionId = connectionId,
    router = router,
    localNodeVirtualAddr = localNodeAddr,
    remoteNodeVirtualAddr = remoteNodeAddr,
    stateChangeListener = stateChangeListener,
) {

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val logPrefix = "[DatagramSocketNeighborConnMgr ${localNodeVirtualAddr.addressToDotNotation()} - " +
            "${remoteNodeVirtualAddr.addressToDotNotation()}] "

    data class PendingPing(val ping: MmcpPing, val timesent: Long)

    private val pendingPings = CopyOnWriteArrayList<PendingPing>()

    private val linkLocalMmcpListener =  VirtualNodeDatagramSocket.LinkLocalMmcpListener { linkLocalMmcpEvt ->
        if(linkLocalMmcpEvt.virtualPacket.header.fromAddr != remoteNodeVirtualAddr)
            return@LinkLocalMmcpListener

        val pong = linkLocalMmcpEvt.mmcpMessage as? MmcpPong ?: return@LinkLocalMmcpListener

        val pendingPing = pendingPings.firstOrNull {
            it.ping.messageId == pong.replyToMessageId
        } ?: return@LinkLocalMmcpListener

        pendingPings.removeAll { it.ping.messageId == pendingPing.ping.messageId }

        //Note: sometimes this is VERY fast during tests, a ping time of 0 would fail the test.
        val responseTime = max(System.currentTimeMillis() - pendingPing.timesent, 1L)
        logger(Log.VERBOSE, "$logPrefix pong(replyTo=${pendingPing.ping.messageId}) received time=${responseTime}ms", null)

        val newState = _state.updateAndGet { prev ->
            prev.copy(
                pingTime = responseTime.toInt(),
                pingsReceived = prev.pingsReceived + 1,
            )
        }
        stateChangeListener.onNeighborNodeConnectionStateChanged(newState)
    }

    private val pingRunnable = Runnable {
        val pingToSend = MmcpPing(router.nextMmcpMessageId())
        pendingPings += PendingPing(pingToSend, System.currentTimeMillis())
        logger(Log.VERBOSE, "$logPrefix send ping", null)
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

    override val pingTime: Short
        get() = _state.value.pingTime.toShort()

    private val pingFuture: Future<*>

    init {
        pingFuture = scheduledExecutor.scheduleAtFixedRate(pingRunnable, PING_INITIAL_DELAY,
            PING_INTERVAL, TimeUnit.MILLISECONDS)
        datagramSocket.addLinkLocalMmmcpListener(linkLocalMmcpListener)
    }

    override fun send(packet: VirtualPacket) {
        datagramSocket.send(
            nextHopAddress = neighborAddress,
            nextHopPort = neighborPort,
            virtualPacket = packet
        )
    }

    override fun close() {
        datagramSocket.removeLinkLocalMmcpListener(linkLocalMmcpListener)
        pingFuture.cancel(true)
        scope.cancel()
    }

    companion object {
        const val PING_INITIAL_DELAY = 1000L

        const val PING_INTERVAL = 12000L

        const val PING_TIMEOUT = 10000
    }
}