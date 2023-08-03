package com.ustadmobile.meshrabiya.vnet.socket

import com.ustadmobile.meshrabiya.ext.readChainInitResponse
import com.ustadmobile.meshrabiya.ext.readyByteArrayOfSizeOrThrow
import com.ustadmobile.meshrabiya.ext.writeChainSocketInitResponse
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService

/**
 * Chain Sockets create a chain of sockets to connect a socket over multiple hops.
 *
 * E.g. device A wants to connect to a port on Device C via device B. Each device will lookup the
 * next hop on the way to connect the socket. If the next hop is the final destination, then it can
 * will connect directly. If not, it will connect to the ForwardSocketServer on the next hop. The
 * real destination will be written to the stream so that the forward socket server can connect
 * onwards.
 *
 */
class ChainSocketServer(
    private val serverSocket: ServerSocket,
    private val executorService: ExecutorService,
    private val chainSocketFactory: ChainSocketFactory,
) {

    private val acceptRunnable = Runnable {
        while(!Thread.interrupted()) {
            val incomingSocket = serverSocket.accept()
            executorService.submit(ClientInitRunnable(incomingSocket))
        }
    }

    val localPort: Int = serverSocket.localPort

    private inner class ClientInitRunnable(private val incomingSocket: Socket): Runnable {
        override fun run() {
            //read the destination - find next connection
            val inStream = incomingSocket.getInputStream()
            val initRequest = ChainSocketInitRequest.fromBytes(
                inStream.readyByteArrayOfSizeOrThrow(ChainSocketInitRequest.MESSAGE_SIZE)
            )

            val chainSocketResult = chainSocketFactory.createChainSocket(
                initRequest.virtualDestAddr, initRequest.virtualDestPort
            )
            val onwardSocket = chainSocketResult.socket
            val initResponse = if(!chainSocketResult.nextHop.isFinalDest) {
                onwardSocket.getInputStream().readChainInitResponse()
            }else {
                //Final destination has been connected
                ChainSocketInitResponse(200)
            }

            incomingSocket.getOutputStream().writeChainSocketInitResponse(initResponse)

            executorService.submit(CopyStreamRunnable(incomingSocket, onwardSocket))
            executorService.submit(CopyStreamRunnable(onwardSocket, incomingSocket))
        }
    }

    private inner class CopyStreamRunnable(
        private val fromSocket: Socket,
        private val toSocket: Socket,
    ): Runnable {
        override fun run() {
            try {
                val outStream = toSocket.getOutputStream()
                fromSocket.getInputStream().copyTo(outStream)
                outStream.flush()
            }finally {
                toSocket.close()
            }
        }
    }

    init {
        executorService.submit(acceptRunnable)
    }

}