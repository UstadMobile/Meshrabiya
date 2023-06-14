package com.ustadmobile.httpoverbluetooth.vnet

import android.util.Log
import com.ustadmobile.httpoverbluetooth.MNetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.io.BufferedReader
import java.io.BufferedWriter
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

data class RemoteMNodeConnectionState(
    val remoteNodeAddr: Int,
    val connectionId: Int,
    val pingTime: Int = 0,
    val connectionState: RemoteMNodeConnectionManager.ConnectionState
)

class RemoteMNodeConnectionManager(
    val connectionId: Int,
    remoteNodeAddr: Int,
    private val socket: ISocket,
    private val logger: MNetLogger,
    private val stateListener: RemoteMNodeConnectionListener,
    executor: ExecutorService,
    scheduledExecutor: ScheduledExecutorService,
    //to add: connection type, etc.
) {

    enum class ConnectionState{
        CONNECTED, DISCONNECTED
    }

    @Volatile
    private var lastPingSentTime = 0L

    @Volatile
    private var lastPingId: Int = 0

    private val socketClosed = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(RemoteMNodeConnectionState(
        remoteNodeAddr = remoteNodeAddr,
        connectionId = connectionId,
        pingTime = 0,
        connectionState = ConnectionState.CONNECTED
    ))

    private val executorFuture: Future<*>

    private val pingFuture: ScheduledFuture<*>

    private val inStream: InputStream

    private val outStream: OutputStream

    private val writeLock = ReentrantLock()

    private inner class ManageConnectionRunnable : Runnable {
        override fun run() {
            try {

                logger(Log.DEBUG, "RemoteMNodeConnectionManager: running: ", null)
                stateListener.onConnectionStateChanged(_connectionState.value)

                val inBufferedReader = BufferedReader(inStream.bufferedReader())
                val outBufferedWriter = BufferedWriter(outStream.bufferedWriter())

                lateinit var line: String
                logger(Log.DEBUG, "RemoteMNodeConnectionManager: Waiting for connection input:", null)
                while(
                    inBufferedReader.readLine()?.also { line = it } != null && !Thread.interrupted()
                ) {
                    val (cmd, args) = line.split(" ", limit = 2)
                    if(cmd == "PING"){
                        writeLock.withLock {
                            outBufferedWriter.write("PONG ${args}\n")
                            outBufferedWriter.flush()
                        }
                    }else if(cmd == "PONG"){
                        //received ping reply
                        val pingIdReceived = args.trim().toIntOrNull()
                        val pingTime = System.currentTimeMillis() - lastPingSentTime
                        logger(Log.DEBUG, "PING id $pingIdReceived time: $pingTime ms", null)
                        val newState = _connectionState.updateAndGet { prev ->
                            prev.copy(
                                pingTime = pingTime.toInt()
                            )
                        }
                        stateListener.onConnectionStateChanged(newState)
                    }
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
                outStream.write("PING $lastPingId\n".toByteArray())
                outStream.flush()
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
            connectionState: RemoteMNodeConnectionState
        )

    }

    fun close(){
        if(!socketClosed.getAndSet(true)) {
            logger(Log.DEBUG, "ConnectionManager: close", null)
            executorFuture.cancel(true)
            pingFuture.cancel(true)
            socket.close()
            val newState = _connectionState.updateAndGet { prev ->
                prev.copy(connectionState = ConnectionState.DISCONNECTED)
            }
            stateListener.onConnectionStateChanged(newState)
        }
    }

}