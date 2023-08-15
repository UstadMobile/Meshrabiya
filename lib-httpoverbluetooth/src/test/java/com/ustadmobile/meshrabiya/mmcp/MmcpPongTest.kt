package com.ustadmobile.meshrabiya.mmcp

import org.junit.Assert
import org.junit.Test

class MmcpPongTest {

    @Test
    fun givenPongMessage_whenConvertedToFromBytes_thenShouldMatch() {
        val pong = MmcpPong(42, 4042)
        val bytes = pong.toBytes()
        val fromBytes = MmcpPong.fromBytes(bytes)
        Assert.assertEquals(pong.messageId, fromBytes.messageId)
        Assert.assertEquals(pong.replyToMessageId, fromBytes.replyToMessageId)
    }
}