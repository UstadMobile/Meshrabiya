package com.ustadmobile.meshrabiya.vnet.wifi

import com.ustadmobile.meshrabiya.ext.requireAsIpv6
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test
import java.net.Inet6Address

class WifiConnectConfigTest {

    @Test
    fun givenHotspotConfigWithSsidAndPassphrase_whenConvertedToAndFromBytes_thenWillBeEqual() {
        val hotspotConfig = WifiConnectConfig(
            nodeVirtualAddr = randomApipaAddr(),
            ssid = "test",
            passphrase = "secret",
            port = 8042,
            hotspotType = HotspotType.LOCALONLY_HOTSPOT,
            linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334")
                .requireAsIpv6(),
        )

        val someOffset = 5//just to test that the offset is used appropriately

        val byteArr = ByteArray(hotspotConfig.sizeInBytes + someOffset)
        hotspotConfig.toBytes(byteArr, someOffset)
        val hotspotConfigFromBytes = WifiConnectConfig.fromBytes(byteArr, someOffset)

        Assert.assertEquals(hotspotConfig, hotspotConfigFromBytes)
    }

    @Test
    fun givenHotspotConfigSerialized_whenSerialized_thenWillMatch() {
        val hotspotConfig = WifiConnectConfig(
            nodeVirtualAddr = randomApipaAddr(),
            ssid = "test",
            passphrase = "secret",
            port = 8042,
            hotspotType = HotspotType.LOCALONLY_HOTSPOT,
            linkLocalAddr = Inet6Address
                .getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
        )

        val json = Json {
            encodeDefaults = true
        }

        val configJsonStr = json.encodeToString(WifiConnectConfig.serializer(), hotspotConfig)

        val hotspotConfigFromJson = json.decodeFromString(
            WifiConnectConfig.serializer(), configJsonStr
        )

        Assert.assertEquals(hotspotConfig, hotspotConfigFromJson)
    }

}