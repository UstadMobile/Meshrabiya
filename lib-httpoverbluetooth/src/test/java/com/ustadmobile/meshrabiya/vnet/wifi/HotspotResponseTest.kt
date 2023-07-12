package com.ustadmobile.meshrabiya.vnet.wifi

import com.ustadmobile.meshrabiya.ext.requireAsIpv6
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import org.junit.Assert
import org.junit.Test
import java.net.Inet6Address
import kotlin.random.Random

class HotspotResponseTest {

    @Test
    fun givenHotspotResponse_whenConvertedToAndFromBytes_thenShouldBeEqual() {
        val response = LocalHotspotResponse(
            responseToMessageId = Random.nextInt(),
            errorCode = 0,
            config = WifiConnectConfig(
                nodeVirtualAddr = randomApipaAddr(),
                ssid = "test",
                passphrase = "world",
                port = 8042,
                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
            ),
            redirectAddr = 0,
        )

        val responseArr = ByteArray(response.sizeInBytes + 10)
        response.toBytes(responseArr, 10)

        val responseFromBytes = LocalHotspotResponse.fromBytes(responseArr, 10)
        Assert.assertEquals(response, responseFromBytes)
    }

}