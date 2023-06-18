package com.ustadmobile.meshrabiya.mmcp

import org.junit.Assert
import org.junit.Test
import kotlin.random.Random

class MmcpMessageTest {

    @Test
    fun givenPingMessage_whenConvertedToAndFromVirtualPacket_thenWillMatch() {
        val pingMessage = MmcpPing(Random.nextBytes(50))
        val pingPacket = pingMessage.toVirtualPacket(
            toAddr = 1000,
            fromAddr = 1042,
        )

        val pingFromPacket = MmcpMessage.fromVirtualPacket(pingPacket) as MmcpPing

        for(i in 0 until pingMessage.payload.size) {
            Assert.assertEquals(pingMessage.payload[i], pingFromPacket.payload[i])
        }
        Assert.assertEquals(1000, pingPacket.header.toAddr)
        Assert.assertEquals(1042, pingPacket.header.fromAddr)

    }

}