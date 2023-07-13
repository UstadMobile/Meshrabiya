package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.mmcp.MmcpAck
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.test.TestLogger
import org.junit.Assert
import org.junit.Test
import org.mockito.kotlin.argWhere
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class VirtualNodeDatagramSocketTest {

    fun createMockRouter(): VirtualRouter {
        val atomicInt = AtomicInteger()
        return mock {
            on {
                nextMmcpMessageId()
            }.thenAnswer {
                atomicInt.incrementAndGet()
            }
        }
    }

    @Test
    fun givenTwoVirtualNodeDatagramSockets_whenOneSendsHello_thenOtherWillReplyWithAck() {
        val executorService = Executors.newCachedThreadPool()
        val socket1VirtualNodeAddr = 42
        val socket2VirtualNodeAddr = 43
        val socket2IncomingHelloListener: VirtualNodeDatagramSocket.OnMmcpHelloReceivedListener = mock { }

        val socket1 = VirtualNodeDatagramSocket(
            socket = DatagramSocket(0),
            localNodeVirtualAddress = socket1VirtualNodeAddr,
            ioExecutorService = executorService,
            router = createMockRouter(),
            onMmcpHelloReceivedListener = { },
            logger = TestLogger(),
        )

        val socket2 = VirtualNodeDatagramSocket(
            socket = DatagramSocket(0),
            localNodeVirtualAddress = socket2VirtualNodeAddr,
            ioExecutorService = executorService,
            router = createMockRouter(),
            onMmcpHelloReceivedListener = socket2IncomingHelloListener,
            logger = TestLogger(),
        )

        try {
            val helloMessageId = Random.nextInt()
            val helloLatch = CountDownLatch(1)
            val responseMessage = AtomicReference<VirtualPacket>()

            val socket1Listener = VirtualNodeDatagramSocket.LinkLocalMmcpListener {
                val message = it.mmcpMessage
                if(message is MmcpAck && message.ackOfMessageId == helloMessageId) {
                    responseMessage.set(it.virtualPacket)
                    helloLatch.countDown()
                }
            }

            socket1.addLinkLocalMmmcpListener(socket1Listener)
            socket1.sendHello(helloMessageId, InetAddress.getLoopbackAddress(), socket2.localPort)

            helloLatch.await(500, TimeUnit.SECONDS)

            val mmcpMessage = MmcpMessage.fromVirtualPacket(responseMessage.get()) as MmcpAck
            Assert.assertEquals(helloMessageId, mmcpMessage.ackOfMessageId)
            Assert.assertEquals(socket2VirtualNodeAddr, responseMessage.get().header.fromAddr)
            verify(socket2IncomingHelloListener).onMmcpHelloReceived(argWhere {
                it.address == InetAddress.getLoopbackAddress() &&
                        it.port == socket1.localPort &&
                        it.virtualPacket.header.fromAddr == socket1VirtualNodeAddr
            })
        }finally {
            executorService.shutdown()
            socket1.close()
            socket2.close()
        }
    }

}