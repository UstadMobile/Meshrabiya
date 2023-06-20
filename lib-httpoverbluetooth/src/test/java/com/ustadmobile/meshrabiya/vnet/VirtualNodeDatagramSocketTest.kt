package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import org.junit.Assert
import org.junit.Test
import org.mockito.kotlin.argWhere
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class VirtualNodeDatagramSocketTest {

    fun createMockRouter(): IRouter {
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
            port = 0,
            localAddVirtualAddress = socket1VirtualNodeAddr,
            ioExecutorService = executorService,
            router = createMockRouter()
        )

        val socket2 = VirtualNodeDatagramSocket(
            port = 0,
            localAddVirtualAddress = socket2VirtualNodeAddr,
            ioExecutorService = executorService,
            router = createMockRouter(),
            onMmcpHelloReceivedListener = socket2IncomingHelloListener,
        )

        try {
            val helloMessageId = Random.nextInt()
            val helloLatch = CountDownLatch(1)
            val responseMessage = AtomicReference<VirtualPacket>()

            val socket1Listener = VirtualNodeDatagramSocket.PacketReceivedListener {
                val virtualPacket = VirtualPacket.fromDatagramPacket(it)
                if(virtualPacket.header.fromAddr == socket2VirtualNodeAddr && virtualPacket.header.toPort == 0) {
                    val mmcpMessage = MmcpMessage.fromVirtualPacket(virtualPacket)
                    if(mmcpMessage.messageId == helloMessageId) {
                        responseMessage.set(virtualPacket)
                        helloLatch.countDown()
                    }
                }
            }

            socket1.addPacketReceivedListener(socket1Listener)
            socket1.sendHello(helloMessageId, InetAddress.getLoopbackAddress(), socket2.localPort)

            helloLatch.await(5, TimeUnit.SECONDS)

            val mmcpMessage = MmcpMessage.fromVirtualPacket(responseMessage.get())
            Assert.assertEquals(helloMessageId, mmcpMessage.messageId)
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