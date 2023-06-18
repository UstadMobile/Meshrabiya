package com.ustadmobile.meshrabiya.portforward

import org.junit.Assert
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ForwardingTest {

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

    @Test(timeout = 5000)
    fun givenEchoSent_whenListening_willReceive() {
        val executor = Executors.newCachedThreadPool()
        val echoServer = EchoDatagramServer(0, executor)

        val client = DatagramSocket()

        val helloBytes = "Hello".toByteArray()
        val helloPacket = DatagramPacket(helloBytes, helloBytes.size,
            InetAddress.getLoopbackAddress(), echoServer.listeningPort)
        client.send(helloPacket)

        val receiveBuffer = ByteArray(100)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        client.receive(receivePacket)

        val decoded = String(receivePacket.data, receivePacket.offset, receivePacket.length)
        Assert.assertEquals("Hello", decoded)
        executor.shutdown()
        echoServer.close()
    }

    @Test(timeout = 5000)
    fun givenPortForwardingRuleActive_whenPacketSentToForwarder_thenReplyWillBeReceived() {
        val executor = Executors.newCachedThreadPool()
        val echoServer = EchoDatagramServer(0, executor)

        val forwardingRule = UdpForwardRule(
            DatagramSocket(), executor,
            InetAddress.getLoopbackAddress(), echoServer.listeningPort)

        val client = DatagramSocket()
        val helloBytes = "Hello".toByteArray()
        val helloPacket = DatagramPacket(helloBytes, helloBytes.size,
            forwardingRule.localAddress, forwardingRule.localPort)
        client.send(helloPacket)

        val receiveBuffer = ByteArray(100)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        client.receive(receivePacket)

        val decoded = String(receivePacket.data, receivePacket.offset, receivePacket.length)
        Assert.assertEquals("Hello", decoded)
        executor.shutdown()
        echoServer.close()
    }



}