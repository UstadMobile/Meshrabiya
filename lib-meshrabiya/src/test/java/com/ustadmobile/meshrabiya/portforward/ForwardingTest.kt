package com.ustadmobile.meshrabiya.portforward

import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.test.EchoDatagramServer
import org.junit.Assert
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class ForwardingTest {

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

        val forwardRuleDatagramSocket = DatagramSocket()
        val forwardingRule = UdpForwardRule(
            DatagramSocket(), executor,
            InetAddress.getLoopbackAddress(), echoServer.listeningPort,
            logger = MNetLoggerStdout()
        )

        val client = DatagramSocket()
        val helloBytes = "Hello".toByteArray()
        val helloPacket = DatagramPacket(helloBytes, helloBytes.size,
            forwardRuleDatagramSocket.localAddress, forwardingRule.localPort)
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