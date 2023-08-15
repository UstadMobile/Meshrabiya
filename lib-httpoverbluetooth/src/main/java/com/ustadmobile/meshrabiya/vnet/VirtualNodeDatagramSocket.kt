package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 *
 * VirtualNodeDatagramSocket listens on the real network interface. It uses the executor service
 * to run a thread that will receive all packets, convert them from a DatagramPacket into a
 * VirtualPacket, and then give them to the VirtualRouter.
 *
 * @param socket - the underlying DatagramSocket to use - this can be bound to a network, interface etc if required
 * neighbor connects.
 */
class VirtualNodeDatagramSocket(
    private val socket: DatagramSocket,
    private val localNodeVirtualAddress: Int,
    ioExecutorService: ExecutorService,
    private val router: VirtualRouter,
    private val logger: MNetLogger,
    name: String? = null
):  Runnable, Closeable {

    private val future: Future<*>

    private val logPrefix: String

    val localPort: Int = socket.localPort

    init {
        logPrefix = buildString {
            append("[VirtualNodeDatagramSocket for ${localNodeVirtualAddress.addressToDotNotation()} ")
            if(name != null)
                append("- $name")
            append("] ")
        }
        future = ioExecutorService.submit(this)
    }

    override fun run() {
        val buffer = ByteArray(VirtualPacket.MAX_PAYLOAD_SIZE)
        logger(Log.DEBUG, "$logPrefix Started on ${socket.localPort} waiting for first packet", null)

        while(!Thread.interrupted() && !socket.isClosed) {
            try {
                val rxPacket = DatagramPacket(buffer, 0, buffer.size)
                socket.receive(rxPacket)

                val rxVirtualPacket = VirtualPacket.fromDatagramPacket(rxPacket)
                router.route(
                    packet = rxVirtualPacket,
                    datagramPacket = rxPacket,
                    virtualNodeDatagramSocket = this,
                )
            }catch(e: Exception) {
                logger(Log.WARN, "$logPrefix : run : exception handling packet", e)
            }
        }
    }


    /**
     *
     */
    fun send(
        nextHopAddress: InetAddress,
        nextHopPort: Int,
        virtualPacket: VirtualPacket
    ) {
        val datagramPacket = virtualPacket.toDatagramPacket()
        datagramPacket.address = nextHopAddress
        datagramPacket.port = nextHopPort
        socket.send(datagramPacket)
    }

    fun close(closeSocket: Boolean) {
        future.cancel(true)
        socket.takeIf { closeSocket }?.close()
    }

    override fun close() {
        close(false)
    }
}
