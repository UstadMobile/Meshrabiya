package com.ustadmobile.meshrabiya.vnet.wifi

import org.junit.Assert
import org.junit.Test
import kotlin.random.Random

class HotspotResponseTest {

    @Test
    fun givenHotspotResponse_whenConvertedToAndFromBytes_thenShouldBeEqual() {
        val response = LocalHotspotResponse(
            responseToMessageId = Random.nextInt(),
            errorCode = 0,
            config = HotspotConfig(
                ssid = "test",
                passphrase = "world",
                port = 8042,
                hotspotType = HotspotType.LOCALONLY_HOTSPOT
            ),
            redirectAddr = 0,
        )

        val responseArr = ByteArray(response.sizeInBytes + 10)
        response.toBytes(responseArr, 10)

        val responseFromBytes = LocalHotspotResponse.fromBytes(responseArr, 10)
        Assert.assertEquals(response, responseFromBytes)
    }

}