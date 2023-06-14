package com.ustadmobile.httpoverbluetooth.vnet

import android.util.Log
import com.ustadmobile.httpoverbluetooth.MNetLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

data class RemoteMNodeConnectionState(
    val remoteNodeAddr: Int,
    val connectionId: Int,
    val pingTime: Int = 0,
)

class RemoteMNodeConnectionManager(
    private val connectionId: Int,
    private val socket: ISocket,
    private val logger: MNetLogger,
    //to add: connection type, etc.
): Runnable {

    private val inStream: InputStream

    private val outStream: OutputStream

    private var lastPingSentTime = 0L

    init {
        try {
            inStream = socket.inStream
            outStream = socket.outputStream
        }catch(e: Exception){
            logger(Log.ERROR, "RemoteMNodeConnectionManager: cannot open", e)
            throw e
        }
    }

    private val writeLock = ReentrantLock()

    fun sendPing() {
        writeLock.withLock {
            val id = Random.nextInt(65000)
            lastPingSentTime = System.currentTimeMillis()
            outStream.write("PING $id\n".toByteArray())
            outStream.flush()
        }
    }

    override fun run() {
        try {
            logger(Log.DEBUG, "RemoteMNodeConnectionManager: running: ", null)

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
                    val pingTime = System.currentTimeMillis() - lastPingSentTime
                    logger(Log.DEBUG, "PING time: $pingTime ms", null)
                }
            }
        }catch(e: Exception) {
            logger(Log.WARN, "Exception on handling socket", e)
        }finally {
            socket.close()
        }
    }

}