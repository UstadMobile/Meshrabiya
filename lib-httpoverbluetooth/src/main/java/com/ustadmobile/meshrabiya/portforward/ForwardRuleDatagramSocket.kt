package com.ustadmobile.meshrabiya.portforward

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * The ForwardRuleDatagramSocket performs NAT port forwarding.
 *
 * It receives packets on a given listening (UDP) socket. When a packet is received, it will:
 *
 *  a) Create a "return path" by opening another UDP socket on a random port on the same interface as
 *     as the toAddress. This return path is linked to the senders origin address and port.
 *  b) Send the packet using the return path socket to the given toAddress/toPort
 *  c) When the return path receives a reply, it will be sent using the listening socket back to the
 *     senders origin address and port.
 *
 */
class ForwardRuleDatagramSocket(
    localPort: Int,
    private val ioExecutor: ExecutorService,
    private val toAddress: InetAddress,
    private val toPort: Int,
): DatagramSocket(localPort), Runnable {

    private inner class ReturnPathDatagramSocket(
        private val returnToAddress: InetAddress,
        private val returnToPort: Int,
    ): DatagramSocket(), Runnable {

        val returnFuture: Future<*>

        init {
            returnFuture = ioExecutor.submit(this)
        }

        override fun run() {
            val buffer = ByteArray(VirtualPacket.MAX_SIZE)

            while(!Thread.interrupted()) {
                val packet = DatagramPacket(buffer, 0, buffer.size)
                receive(packet)
                packet.address = returnToAddress
                packet.port = returnToPort
                this@ForwardRuleDatagramSocket.send(packet)
            }
        }

        override fun close() {
            super.close()
            returnFuture.cancel(true)
        }
    }

    private val future: Future<*>

    private val returnSockets = ConcurrentHashMap<SocketAddress, ReturnPathDatagramSocket>()

    init {
        future = ioExecutor.submit(this)
    }

    override fun run() {
        val buffer = ByteArray(VirtualPacket.MAX_SIZE)
        while(!Thread.interrupted()) {
            val packet = DatagramPacket(buffer, 0, buffer.size)
            receive(packet)

            val returnSocket = returnSockets.getOrPut(packet.socketAddress){
                ReturnPathDatagramSocket(
                    returnToAddress = packet.address,
                    returnToPort = packet.port
                )
            }

            packet.address = toAddress
            packet.port = toPort

            returnSocket.send(packet)
        }
    }

    override fun close() {
        super.close()
        future.cancel(true)
    }
}