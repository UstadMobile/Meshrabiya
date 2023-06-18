package com.ustadmobile.meshrabiya.vnet

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class VirtualNodeDatagramSocket(
    port: Int,
    private val ioExecutorService: ExecutorService,
    private val router: IRouter,
) : DatagramSocket(port), Runnable {

    private val future: Future<*> = ioExecutorService.submit(this)

    override fun run() {
        val buffer = ByteArray(VirtualPacket.MAX_PAYLOAD_SIZE)

        while(!Thread.interrupted()) {
            val packet = DatagramPacket(buffer, 0, buffer.size)
            receive(packet)
            val virtualPacket = VirtualPacket.fromDatagramPacket(packet)

            router.route(
                from = virtualPacket.header.fromAddr,
                packet = virtualPacket
            )
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
        send(datagramPacket)
    }


    override fun close() {
        future.cancel(true)
        super.close()
    }
}