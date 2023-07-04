package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.vnet.wifi.HotspotConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test

class MeshrabiyaConnectLinkTest {

    @Test
    fun givenLinkFromComponents_whenParsed_thenShouldMatchOriginal(){
        val json = Json { encodeDefaults = true }
        val link = MeshrabiyaConnectLink.fromComponents(
            nodeAddr = randomApipaAddr(),
            port = 8087,
            hotspotConfig = HotspotConfig(
                nodeVirtualAddr = randomApipaAddr(),
                ssid = "test",
                passphrase = "testpass",
                port = 8087,
                hotspotType = HotspotType.LOCALONLY_HOTSPOT,
            ),
            bluetoothConfig = null,
            json = json,
        )

        val parsedLink = MeshrabiyaConnectLink.parseUri(link.uri, json)
        Assert.assertEquals(link, parsedLink)


    }

}