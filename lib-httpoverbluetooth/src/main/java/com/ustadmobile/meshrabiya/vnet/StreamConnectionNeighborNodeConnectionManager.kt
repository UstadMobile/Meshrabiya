package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.readVirtualPacket
import com.ustadmobile.meshrabiya.ext.writeVirtualPacket
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

class StreamConnectionNeighborNodeConnectionManager(
    connectionId: Int,
    router: VirtualRouter,
    localNodeAddr: Int,
    remoteNodeAddr: Int,
    private val socket: ISocket,
    private val logger: MNetLogger,
    executor: ExecutorService,
    scheduledExecutor: ScheduledExecutorService,
    stateChangeListener: OnNeighborNodeConnectionStateChangedListener = OnNeighborNodeConnectionStateChangedListener { },
): AbstractNeighborNodeConnectionManager(
    connectionId = connectionId,
    router = router,
    localNodeVirtualAddr = localNodeAddr,
    remoteNodeVirtualAddr = remoteNodeAddr,
    stateChangeListener = stateChangeListener,
) {

    enum class ConnectionState{
        CONNECTED, DISCONNECTED
    }

    @Volatile
    private var lastPingSentTime = 0L

    @Volatile
    private var lastPingId: Int = 0

    private val socketClosed = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(NeighborNodeConnectionState(
        remoteNodeAddr = remoteNodeAddr,
        connectionId = connectionId,
        pingTime = 0,
        connectionState = ConnectionState.CONNECTED
    ))


    override val state: Flow<NeighborNodeConnectionState> = _connectionState.asStateFlow()

    override val pingTime: Short
        get() = _connectionState.value.pingTime.toShort()

    private val executorFuture: Future<*>

    private val pingFuture: ScheduledFuture<*>

    private val inStream: InputStream

    private val outStream: OutputStream

    private val writeLock = ReentrantLock()

    private inner class ManageConnectionRunnable : Runnable {
        override fun run() {
            try {

                logger(Log.DEBUG, "RemoteMNodeConnectionManager: running: ", null)
                //stateListener.onConnectionStateChanged(_connectionState.value)

                val buffer = ByteArray(1600)
                lateinit var packet: VirtualPacket
                logger(Log.DEBUG, "RemoteMNodeConnectionManager: Waiting for connection input:", null)
                while(
                    inStream.readVirtualPacket(buffer, 0)?.also { packet = it } != null && !Thread.interrupted()
                ) {
                    router.route(packet)
                }
            }catch(e: Exception) {
                logger(Log.WARN, "Exception on handling socket", e)
            }finally {
                close()
            }
        }
    }

    private val manageConnectionRunnable: ManageConnectionRunnable

    private inner class PingRunnable: Runnable {
        override fun run() {
            writeLock.withLock {
                lastPingId = Random.nextInt(65000)
                lastPingSentTime = System.currentTimeMillis()
                _connectionState.update {  prev ->
                    prev.copy(
                        pingAttempts = prev.pingAttempts + 1,
                    )
                }

                val ping = MmcpPing(lastPingId)

                val virtualPacket = ping.toVirtualPacket(
                    toAddr = remoteNodeVirtualAddr,
                    fromAddr = localNodeVirtualAddr
                )

                router.route(
                    packet = virtualPacket,
                )
            }
        }
    }

    init {
        try {
            inStream = socket.inStream
            outStream = socket.outputStream
            manageConnectionRunnable = ManageConnectionRunnable()


            executorFuture = executor.submit(manageConnectionRunnable)
            pingFuture = scheduledExecutor.scheduleAtFixedRate(PingRunnable(),
                1000L, 12000L, TimeUnit.MILLISECONDS)
        }catch(e: Exception){
            logger(Log.ERROR, "RemoteMNodeConnectionManager: cannot open", e)
            throw e
        }
    }


    interface RemoteMNodeConnectionListener {

        fun onConnectionStateChanged(
            connectionState: NeighborNodeConnectionState
        )

    }

    override fun send(
        packet: VirtualPacket
    ) {
        writeLock.withLock {
            try {
                outStream.writeVirtualPacket(packet)
                outStream.flush()
            }catch(e: Exception) {
                logger(Log.WARN, "Exception sending packet", e)
                close()
                throw e
            }

        }
    }

    override fun close(){
        if(!socketClosed.getAndSet(true)) {
            logger(Log.DEBUG, "ConnectionManager: close", null)
            executorFuture.cancel(true)
            pingFuture.cancel(true)
            socket.close()
            val newState = _connectionState.updateAndGet { prev ->
                prev.copy(connectionState = ConnectionState.DISCONNECTED)
            }

            stateChangeListener.onNeighborNodeConnectionStateChanged(newState)
        }
    }

}