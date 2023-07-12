package com.ustadmobile.meshrabiya.vnet

import org.junit.Assert
import org.junit.Test
import kotlin.random.Random

class VirtualPacketTest {

    @Test
    fun givenVirtualPacket_whenConvertedToDatagramAndBackToVirtualPacket_thenShouldMatch() {
        val payloadSize = 1000
        val payload = Random.nextBytes(ByteArray(payloadSize + VirtualPacketHeader.HEADER_SIZE))
        val header = VirtualPacketHeader(
            toAddr = 1000,
            toPort = 8080,
            fromAddr = 1002,
            fromPort = 8072,
            lastHopAddr = 1002,
            hopCount = 1,
            maxHops = 4,
            payloadSize = payloadSize,
        )

        val virtualPacket = VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = payload,
            payloadOffset = VirtualPacketHeader.HEADER_SIZE,
        )

        val datagramPacket = virtualPacket.toDatagramPacket()
        val virtualPacketFromDatagramPacket = VirtualPacket.fromDatagramPacket(datagramPacket)

        Assert.assertEquals(header, virtualPacketFromDatagramPacket.header)
        for(i in 0 until virtualPacket.header.payloadSize) {
            Assert.assertEquals(
                virtualPacket.data[i + virtualPacket.dataOffset],
                virtualPacketFromDatagramPacket.data[i + virtualPacketFromDatagramPacket.dataOffset]
            )
        }
    }

}