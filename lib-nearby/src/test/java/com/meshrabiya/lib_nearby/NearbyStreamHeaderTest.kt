package com.meshrabiya.lib_nearby

import com.meshrabiya.lib_nearby.nearby.NearbyStreamHeader
import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer

class NearbyStreamHeaderTest {

    @Test
    fun testNearbyStreamHeaderToBytes() {
        val header = NearbyStreamHeader(
            streamId = 1234,
            isReply = true,
            payloadSize = 5678,
            fromAddress = 192168001,
            toAddress = 192168002
        )

        val bytes = header.toBytes()
        val buffer = ByteBuffer.wrap(bytes)

        assertEquals(NearbyStreamHeader.MESSAGE_TYPE, buffer.int)
        assertEquals(192168001, buffer.int)
        assertEquals(192168002, buffer.int)
        assertEquals(1234, buffer.int)
        assertEquals(1.toByte(), buffer.get())
        assertEquals(5678, buffer.int)
    }

    @Test
    fun testNearbyStreamHeaderFromBytes() {
        val originalHeader = NearbyStreamHeader(
            streamId = 1234,
            isReply = false,
            payloadSize = 5678,
            fromAddress = 192168001,
            toAddress = 192168002
        )

        val bytes = originalHeader.toBytes()
        val reconstructedHeader = NearbyStreamHeader.fromBytes(bytes)

        assertEquals(originalHeader.messageType, reconstructedHeader.messageType)
        assertEquals(originalHeader.fromAddress, reconstructedHeader.fromAddress)
        assertEquals(originalHeader.toAddress, reconstructedHeader.toAddress)
        assertEquals(originalHeader.streamId, reconstructedHeader.streamId)
        assertEquals(originalHeader.isReply, reconstructedHeader.isReply)
        assertEquals(originalHeader.payloadSize, reconstructedHeader.payloadSize)
    }
}