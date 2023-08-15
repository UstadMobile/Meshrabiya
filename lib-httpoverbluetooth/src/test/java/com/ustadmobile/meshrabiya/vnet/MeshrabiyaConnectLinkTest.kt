package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.requireAsIpv6
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test
import java.net.Inet6Address

class MeshrabiyaConnectLinkTest {

    @Test
    fun givenLinkFromComponents_whenParsed_thenShouldMatchOriginal(){
        val json = Json { encodeDefaults = true }
        val link = MeshrabiyaConnectLink.fromComponents(
            nodeAddr = randomApipaAddr(),
            port = 8087,
            hotspotConfig = WifiConnectConfig(
                nodeVirtualAddr = randomApipaAddr(),
                ssid = "test",
                passphrase = "testpass",
                port = 8087,
                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
            ),
            bluetoothConfig = null,
            json = json,
        )

        val parsedLink = MeshrabiyaConnectLink.parseUri(link.uri, json)
        Assert.assertEquals(link, parsedLink)


    }

}