package com.ustadmobile.meshrabiya.vnet

import org.junit.Assert
import org.junit.Test

class VirtualPacketHeaderTest {

    @Test
    fun givenHeaderObject_whenToBytesThenFromBytesCalled_thenShouldBeEqual() {
        val header = VirtualPacketHeader(
            toAddr = 1000,
            toPort = 8080,
            fromAddr = 1002,
            fromPort = 8072,
            lastHopAddr = 1002,
            hopCount = 1,
            maxHops = 4,
            payloadSize = 1300
        )

        val headerInBytes = header.toBytes()

        val headerFromBytes = VirtualPacketHeader.fromBytes(headerInBytes)

        Assert.assertEquals("Header matches when serialized/deserialized", header, headerFromBytes)
    }

}