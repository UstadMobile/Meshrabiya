package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.localhotspot.HotspotConfig
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotResponse
import org.junit.Assert
import org.junit.Test
import kotlin.random.Random

class MmcpHotspotResponseTest {

    @Test
    fun givenHotspotResponse_whenConvertedToFromBytes_thenShouldBeEqual() {
        val responseMessage = MmcpHotspotResponse(
            messageId = 42,
            result = LocalHotspotResponse(
                responseToMessageId = Random.nextInt(),
                errorCode = 0,
                config = HotspotConfig(
                    ssid = "test",
                    passphrase = "secret"
                ),
                redirectAddr = 0
            )
        )

        val responseBytes = responseMessage.toBytes()
        val responseDeserialized = MmcpHotspotResponse.fromBytes(responseBytes)
        Assert.assertEquals(responseMessage.messageId, responseDeserialized.messageId)
        Assert.assertEquals(responseMessage.result, responseDeserialized.result)
    }

}