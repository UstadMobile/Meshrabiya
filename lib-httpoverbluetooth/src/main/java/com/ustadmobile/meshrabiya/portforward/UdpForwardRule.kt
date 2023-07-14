package com.ustadmobile.meshrabiya.portforward

import android.util.Log
import com.ustadmobile.meshrabiya.MNetLogger
import com.ustadmobile.meshrabiya.vnet.IDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.asIDatagramSocket
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * The UdpForwardRule performs NAT-style port forwarding. It can forward traffic received on a
 * localSocket to a destination, and route return packets back.
 *
 * It receives packets on a local socket (UDP) socket. When a packet is received, it will:
 *
 *  a) Create a "return path" (if not already created) by opening another UDP socket on a random port
 *     on the same interface as the toAddress. This return path is linked to the address and port
 *     from which a packet was received (so it knows where to send any return packets received).
 *  b) Send the packet using the return path socket to the given toAddress/toPort
 *  c) When the return path receives a reply, it will be sent using the local socket back to the
 *     senders origin address and port.
 *
 *
 * @param localSocket a Socket that is listening on a particular interface (e.g. local interface)
 *                    where any packets received should be forwarded to the given toAddress/toPort
 * @param ioExecutor ExecutorService that will be used to send/receive packets
 * @param toAddress the address that packets will be forwarded to
 * @param toPort the port that packets will be forwarded to
 * @param returnPathSocketFactory a factory that is used to generate the return path required for
 *                                step a) above.
 */
class UdpForwardRule(
    private val localSocket: IDatagramSocket,
    private val ioExecutor: ExecutorService,
    private val toAddress: InetAddress,
    private val toPort: Int,
    private val returnPathSocketFactory: ReturnPathSocketFactory = ReturnPathSocketFactory { addr, port ->
        DatagramSocket(port).asIDatagramSocket()
    },
    private val logger: MNetLogger,
): Runnable, Closeable {

    val localPort: Int = localSocket.localPort

    private val logPrefix: String = "[UdpForwardRule : ${localSocket.localPort} -> ${toAddress.hostAddress}:$toPort]"

    private inner class ReturnPathDatagramSocket(
        val returnPathSocket: IDatagramSocket,
        private val returnToAddress: InetAddress,
        private val returnToPort: Int,
    ): Runnable {

        private val returnFuture: Future<*> = ioExecutor.submit(this)

        override fun run() {
            val buffer = ByteArray(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)

            while(!Thread.interrupted()) {
                val packet = DatagramPacket(buffer, 0, buffer.size)
                returnPathSocket.receive(packet)
                packet.address = returnToAddress
                packet.port = returnToPort
                localSocket.send(packet)
            }
        }

        fun close() {
            returnPathSocket.close()
            returnFuture.cancel(true)
        }
    }

    private val future: Future<*> = ioExecutor.submit(this)

    private val returnSockets = ConcurrentHashMap<SocketAddress, ReturnPathDatagramSocket>()

    override fun run() {
        try {
            val buffer = ByteArray(VirtualPacket.MAX_PAYLOAD_SIZE)
            logger(Log.DEBUG, "$logPrefix listening", null)
            while(!Thread.interrupted()) {
                val packet = DatagramPacket(buffer, 0, buffer.size)
                localSocket.receive(packet)

                val returnSocket = returnSockets.getOrPut(packet.socketAddress){
                    ReturnPathDatagramSocket(
                        returnPathSocket = returnPathSocketFactory.createSocket(toAddress, 0),
                        returnToAddress = packet.address,
                        returnToPort = packet.port
                    )
                }

                packet.address = toAddress
                packet.port = toPort

                returnSocket.returnPathSocket.send(packet)
            }
        }catch(e: Exception) {
            logger(Log.ERROR, "$logPrefix : exception running", e)
        }finally {

        }
    }

    /**
     *
     */
    fun close(closeLocalSocket: Boolean) {
        returnSockets.values.forEach {
            it.close()
        }

        future.cancel(true)
        if(closeLocalSocket)
            localSocket.close()
    }

    override fun close() {
        close(closeLocalSocket = true)
    }

}