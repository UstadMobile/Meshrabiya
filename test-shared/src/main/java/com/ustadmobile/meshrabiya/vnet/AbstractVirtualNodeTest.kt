package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.test.TestVirtualNode
import com.ustadmobile.meshrabiya.test.assertByteArrayEquals
import com.ustadmobile.meshrabiya.test.connectTo
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test
import org.mockito.kotlin.mock
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID


abstract class AbstractVirtualNodeTest {

    private val logger = MNetLoggerStdout()

    private val json = Json {
        encodeDefaults = true
    }

    @Test(timeout = 5000)
    fun givenTwoNodesConnected_whenPacketSentUsingVirtualSocket_thenShouldBeReceived() {
        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, (1).toByte()).ip4AddressToInt()
        )

        val node2  = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, (2).toByte()).ip4AddressToInt()
        )
        try {
            node1.connectTo(node2)
            val node1socket  = node1.createDatagramSocket()
            node1socket.bind(InetSocketAddress(node1.localNodeInetAddress, 81))

            val node2socket = node2.createDatagramSocket()
            node2socket.bind(InetSocketAddress(node2.localNodeInetAddress, 82))

            val packetData = "Hello World".encodeToByteArray()
            val txPacket = DatagramPacket(packetData, 0, packetData.size)
            txPacket.address = InetAddress.getByAddress(node2.localNodeAddress.addressToByteArray())
            txPacket.port = 82
            node1socket.send(txPacket)

            val rxBuffer = ByteArray(1500)
            val rxPacket = DatagramPacket(rxBuffer, 0, rxBuffer.size)
            node2socket.receive(rxPacket)
            assertByteArrayEquals(
                packetData, 0, rxBuffer, rxPacket.offset, packetData.size
            )
            Assert.assertEquals(txPacket.length, rxPacket.length)
        }finally {
            node1.close()
            node2.close()
        }

    }
}