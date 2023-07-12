package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.ext.requireAsIpv6
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import org.junit.Assert
import org.junit.Test
import java.net.Inet6Address
import kotlin.random.Random

class MmcpHotspotResponseTest {

    @Test
    fun givenHotspotResponse_whenConvertedToFromBytes_thenShouldBeEqual() {
        val responseMessage = MmcpHotspotResponse(
            messageId = 42,
            result = LocalHotspotResponse(
                responseToMessageId = Random.nextInt(),
                errorCode = 0,
                config = WifiConnectConfig(
                    nodeVirtualAddr = randomApipaAddr(),
                    ssid = "test",
                    passphrase = "secret",
                    port = 8042,
                    hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                    linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
                ),
                redirectAddr = 0
            )
        )

        val responseBytes = responseMessage.toBytes()
        val responseDeserialized = MmcpHotspotResponse.fromBytes(responseBytes) as MmcpHotspotResponse
        Assert.assertEquals(responseMessage.messageId, responseDeserialized.messageId)
        Assert.assertEquals(responseMessage.result, responseDeserialized.result)
    }

}