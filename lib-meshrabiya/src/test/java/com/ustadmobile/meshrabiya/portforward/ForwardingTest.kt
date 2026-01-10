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
        println("[DEBUG] ForwardingTest: Running givenEchoSent_whenListening_willReceive")
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
        executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        echoServer.close()
    }

    @Test(timeout = 5000)
    fun givenPortForwardingRuleActive_whenPacketSentToForwarder_thenReplyWillBeReceived() {
        println("[DEBUG] ForwardingTest: Running givenPortForwardingRuleActive_whenPacketSentToForwarder_thenReplyWillBeReceived")
        val executor = Executors.newCachedThreadPool()
        val echoServer = EchoDatagramServer(0, executor)

        // Create a bound socket for the forwarding rule
        val boundSocket = DatagramSocket(0)
        val forwardingRule = UdpForwardRule(
            boundSocket = boundSocket,
            ioExecutor = executor,
            destAddress = InetAddress.getLoopbackAddress(),
            destPort = echoServer.listeningPort,
            logger = MNetLoggerStdout()
        )

        // Start the forwarding rule
        executor.submit(forwardingRule)

        val client = DatagramSocket()
        val helloBytes = "Hello".toByteArray()
        val helloPacket = DatagramPacket(helloBytes, helloBytes.size,
            InetAddress.getLoopbackAddress(), boundSocket.localPort)
        client.send(helloPacket)

        val receiveBuffer = ByteArray(100)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        client.receive(receivePacket)

        val decoded = String(receivePacket.data, receivePacket.offset, receivePacket.length)
        Assert.assertEquals("Hello", decoded)
        
        // Cleanup - close forwardingRule first to stop listening, then close socket
        forwardingRule.close()
        Thread.sleep(100)  // Give the thread time to exit the receive() call
        boundSocket.close()
        client.close()
        echoServer.close()
        executor.shutdown()
        executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
    }
}