package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.readVirtualPacket
import com.ustadmobile.meshrabiya.ext.writeVirtualPacket
import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class VirtualPacketStreamTest {

    @Test
    fun givenVirtualPacketWrittenToOutputStream_whenReadFromInputStream_thenWillMatch() {
        val payloadSize = 1000
        val data = Random.nextBytes(ByteArray(1000 + VirtualPacketHeader.HEADER_SIZE))
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

        val outStream = ByteArrayOutputStream()
        val packet = VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = data,
            payloadOffset = VirtualPacketHeader.HEADER_SIZE,
        )
        outStream.writeVirtualPacket(packet)
        outStream.flush()

        val writtenBytes = outStream.toByteArray()

        val inStream = ByteArrayInputStream(writtenBytes)

        val buf = ByteArray(8000)
        val packetIn = inStream.readVirtualPacket(buf, 0)

        Assert.assertEquals("Header matches", header, packetIn?.header)
        data.forEachIndexed { index, byte ->
            Assert.assertEquals(byte, packetIn!!.data[index])
        }
    }

}