package com.ustadmobile.meshrabiya.test

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class EchoDatagramServer(
    port: Int,
    executor: ExecutorService,
) : Runnable {

    val datagramSocket = DatagramSocket(port)

    val future: Future<*>

    val listeningPort = datagramSocket.localPort

    init {
        future = executor.submit(this)
    }

    override fun run() {
        val buf = ByteArray(1500)
        val packet = DatagramPacket(buf, buf.size)
        while(!Thread.interrupted()) {
            datagramSocket.receive(packet)

            val replyPacket = DatagramPacket(buf, 0, packet.length, packet.address, packet.port)
            datagramSocket.send(replyPacket)
        }
    }

    fun close() {
        future.cancel(true)
        datagramSocket.close()
    }

}