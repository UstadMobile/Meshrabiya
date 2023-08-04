package com.ustadmobile.meshrabiya.vnet.socket

import android.util.Log
import com.ustadmobile.meshrabiya.ext.readyByteArrayOfSizeOrThrow
import com.ustadmobile.meshrabiya.ext.writeChainSocketInitResponse
import com.ustadmobile.meshrabiya.log.MNetLogger
import java.io.Closeable
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Chain Sockets create a chain of sockets to connect a socket over multiple hops. Let's say device
 * A wants to connect to device D via device B and C:
 *
 *  1. Device A will open a socket to the ChainSocketServer on device B. Device A writes the
 *     ChainInitRequest containing the final destination to the stream.
 *  2. Device B will read the ChainInitRequest, and finds a route to Device D where the next hop
 *     is device C. Device opens a socket to the ChainSocketServer on Device C, and writes the
 *     ChainInitRequest containing the final destination to the stream.
 *  3. Device C will read the ChainInitRequest, and finds that the final destination device D is a
 *     neighbor node. Device C will open a socket to the "real" IP address that it knows and can
 *     reach for device D.
 *
 * Each node copies input/output streams back/forth.  The end result is that when Device A read/writes
 * from its socket all data is relayed back/forth, so it can communicate with device D even though
 * it does not have a direct route to device D.
 *
 * Next steps: Handling HTTP: ForwardServer could read the first "magic bytes" to see if this is a socket forward or http request
 * if http request, then read it as a raw http request and proxy it
 *
 */
class ChainSocketServer(
    private val serverSocket: ServerSocket,
    private val executorService: ExecutorService,
    private val chainSocketFactory: ChainSocketFactory,
    name: String,
    private val logger: MNetLogger,
    private val onMakeChainSocket: ChainSocketFactory.(address: InetAddress, port: Int) -> ChainSocketFactory.ChainSocketResult = { address, port ->
        createChainSocket(address, port)
    }
) : Closeable {

    private val logPrefix: String = "[ChainSocketServer: $name] "

    private val clientFutures: MutableList<WeakReference<Future<*>>> = CopyOnWriteArrayList()

    private val acceptRunnable = Runnable {
        while(!Thread.interrupted()) {
            val incomingSocket = serverSocket.accept()
            logger(Log.DEBUG, "$logPrefix accepted new client")
            executorService.submit(ClientInitRunnable(incomingSocket))
        }
    }

    val localPort: Int = serverSocket.localPort

    private val acceptRunnableFuture = executorService.submit(acceptRunnable)

    init {
        logger(Log.INFO, "$logPrefix init")
    }

    private inner class ClientInitRunnable(private val incomingSocket: Socket): Runnable {
        override fun run() {
            logger(
                Log.DEBUG,
                message = {"$logPrefix ${incomingSocket.remoteSocketAddress} : init client - reading init request..."}
            )

            //read the destination - find next connection
            val inStream = incomingSocket.getInputStream()
            val initRequest = ChainSocketInitRequest.fromBytes(
                inStream.readyByteArrayOfSizeOrThrow(ChainSocketInitRequest.MESSAGE_SIZE)
            )
            val clientAddr = incomingSocket.remoteSocketAddress

            logger(Log.DEBUG,
                message = {
                    "$logPrefix $clientAddr : receive init request to " +
                        "connect to ${initRequest.virtualDestAddr}:${initRequest.virtualDestPort}"
                }
            )

            val chainSocketResult = onMakeChainSocket(
                chainSocketFactory, initRequest.virtualDestAddr, initRequest.virtualDestPort
            )

            logger(Log.DEBUG,
                message = {
                    "$logPrefix $clientAddr : created onward socket"
                }
            )

            //If we made it here, everything is OK
            incomingSocket.getOutputStream().writeChainSocketInitResponse(
                ChainSocketInitResponse(200)
            )
            incomingSocket.getOutputStream().flush()
            logger(Log.DEBUG,
                message = {
                    "$logPrefix $clientAddr : wrote chain init response"
                }
            )

            val onwardSocket = chainSocketResult.socket

            val onwardToIncomingFuture = executorService.submit(
                CopyStreamRunnable(clientAddr, onwardSocket, incomingSocket, "onwardToIncoming")
            )
            val incomingToOnwardFuture = executorService.submit(
                CopyStreamRunnable(clientAddr, incomingSocket, onwardSocket, "incomingToOnward", onwardToIncomingFuture)
            )
            clientFutures += WeakReference(onwardToIncomingFuture)
            clientFutures += WeakReference(incomingToOnwardFuture)
        }
    }

    private inner class CopyStreamRunnable(
        private val clientAddr: SocketAddress,
        private val fromSocket: Socket,
        private val toSocket: Socket,
        private val name: String,
        private val otherFuture: Future<*>? = null,
    ): Runnable {

        override fun run() {
            val outStream = toSocket.getOutputStream()
            val inStream = fromSocket.getInputStream()
            try {
                logger(Log.VERBOSE, {"$logPrefix $clientAddr : CopyStream: $name - start copying input to output"})
                inStream.copyTo(outStream)
                logger(Log.VERBOSE, {"$logPrefix $clientAddr : CopyStream: $name - finished copying - reached end of stream"})
            }catch(e: Exception) {
                println("exception: $name : $e")
            }finally {
                //Need to explicitly close the streams we are handling immediately
                inStream.close()
                //Closing output stream will ensure that the client on the other side realizes that
                // its input is finished.
                outStream.close()

                if(otherFuture != null) {
                    otherFuture.get()
                    fromSocket.close()
                    toSocket.close()
                }
            }

            clientFutures.removeIf { it.get() == null }
        }
    }

    override fun close() {
        acceptRunnableFuture.cancel(true)
        clientFutures.forEach {
            it.get()?.cancel(true)
        }
    }

}