package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import org.junit.Assert
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class VirtualNodeTest {

    class PipeSocket(
        override val inStream: InputStream,
        override val outputStream: OutputStream,
    ): ISocket {
        override fun close() {
            inStream.close()
            outputStream.close()
        }
    }

    private val logger = MNetLogger { priority, message, exception ->
        println(buildString {
            append(message)
            if(exception != null) {
                append(" ")
                append(exception.stackTraceToString())
            }
        })
    }

    @Test
    fun givenTwoVirtualNodesConnectedOverIoStreamSocket_whenPingSent_thenReplyWillBeReceived() {
        val executor = Executors.newCachedThreadPool()
        val node1 = VirtualNode(UUID.randomUUID(), UUID.randomUUID(), logger)
        val node2 = VirtualNode(UUID.randomUUID(), UUID.randomUUID(), logger)

        val node1ToNode2Out = PipedOutputStream()
        val node2FromNode1In = PipedInputStream(node1ToNode2Out)

        val node2ToNode1Out = PipedOutputStream()
        val node1FromNode2In = PipedInputStream(node2ToNode1Out)

        val node1Socket = PipeSocket(node1FromNode2In, node1ToNode2Out)
        val node2Socket = PipeSocket(node2FromNode1In, node2ToNode1Out)


        //needs to run on executor... otherwise will get stuck
        executor.submit { node1.handleNewSocketConnection(node1Socket) }
        executor.submit { node2.handleNewSocketConnection(node2Socket) }

        val node1ToNode2Ping = MmcpPing(Random.nextInt())

        val latch = CountDownLatch(1)

        node1.addPongListener(object: PongListener{
            override fun onPongReceived(fromNode: Int, pong: MmcpPong) {
                latch.countDown()
            }
        })

        node1.route(
            node1ToNode2Ping.toVirtualPacket(
                toAddr = node2.localNodeAddress,
                fromAddr = node1.localNodeAddress
            )
        )

        latch.await(5000, TimeUnit.MILLISECONDS)

        executor.shutdown()
    }

    @Test
    fun givenTwoVirtualNodesConnectedOverDatagramSocket_whenPingSent_thenReplyWillBeReceived() {
        val node1 = VirtualNode(UUID.randomUUID(), UUID.randomUUID(), logger)
        val node2 = VirtualNode(UUID.randomUUID(), UUID.randomUUID(), logger)

        node1.addNewDatagramNeighborConnection(InetAddress.getLoopbackAddress(), node2.datagramPort)

        val latch = CountDownLatch(1)
        val pongMessage = AtomicReference<MmcpPong>()
        val node1ToNode2Ping = MmcpPing(Random.nextInt())

        node1.addPongListener(object: PongListener{
            override fun onPongReceived(fromNode: Int, pong: MmcpPong) {
                if(pong.messageId == node1ToNode2Ping.messageId) {
                    pongMessage.set(pong)
                    latch.countDown()
                }

            }
        })


        node1.route(
            node1ToNode2Ping.toVirtualPacket(
                toAddr = node2.localNodeAddress,
                fromAddr = node1.localNodeAddress
            )
        )

        latch.await(5000, TimeUnit.MILLISECONDS)
        Assert.assertEquals(node1ToNode2Ping.messageId, pongMessage.get().messageId)
    }


}