package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argWhere
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.random.Random

class VirtualNodeDatagramSocketTest {

    @Test
    fun givenTwoVirtualNodeDatagramSockets_whenOneSendsVirtualPacketToOther_thenReceiveWillCallVirtualRouter() {
        val executorService = Executors.newCachedThreadPool()
        val socket1VirtualNodeAddr = 42
        val socket2VirtualNodeAddr = 43

        val socket1Router: VirtualRouter = mock { }
        val socket1 = VirtualNodeDatagramSocket(
            socket = DatagramSocket(0),
            localNodeVirtualAddress = socket1VirtualNodeAddr,
            ioExecutorService = executorService,
            router = socket1Router,
            logger = MNetLoggerStdout(),
        )

        val socket2Router: VirtualRouter = mock { }
        val socket2 = VirtualNodeDatagramSocket(
            socket = DatagramSocket(0),
            localNodeVirtualAddress = socket2VirtualNodeAddr,
            ioExecutorService = executorService,
            router = socket2Router,
            logger = MNetLoggerStdout(),
        )

        try {
            val data = Random.nextBytes(ByteArray(1000 + VirtualPacketHeader.HEADER_SIZE))
            val packetToSend = VirtualPacket.fromHeaderAndPayloadData(
                header = VirtualPacketHeader(
                    toAddr = socket2VirtualNodeAddr,
                    toPort = 80,
                    fromAddr = socket1VirtualNodeAddr,
                    fromPort = 80,
                    lastHopAddr = socket1VirtualNodeAddr,
                    hopCount = 1.toByte(),
                    maxHops = 8.toByte(),
                    payloadSize = 1000
                ),
                data = data,
                payloadOffset = VirtualPacketHeader.HEADER_SIZE,
            )

            socket1.send(
                nextHopAddress = InetAddress.getLoopbackAddress(),
                nextHopPort = socket2.localPort,
                virtualPacket = packetToSend,
            )

            verify(socket2Router, timeout(5000)).route(
                packet = argWhere {
                    it.header == packetToSend.header
                },
                datagramPacket = any(),
                virtualNodeDatagramSocket = eq(socket2),
            )
        }finally {
            executorService.shutdown()
            socket1.close()
            socket2.close()
        }
    }

}