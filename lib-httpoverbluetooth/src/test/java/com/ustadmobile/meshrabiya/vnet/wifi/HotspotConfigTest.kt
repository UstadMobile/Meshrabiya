package com.ustadmobile.meshrabiya.vnet.wifi

import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test

class HotspotConfigTest {

    @Test
    fun givenHotspotConfigWithSsidAndPassphrase_whenConvertedToAndFromBytes_thenWillBeEqual() {
        val hotspotConfig = HotspotConfig(
            nodeVirtualAddr = randomApipaAddr(),
            ssid = "test",
            passphrase = "secret",
            port = 8042,
            hotspotType = HotspotType.LOCALONLY_HOTSPOT,
        )

        val someOffset = 5//just to test that the offset is used appropriately

        val byteArr = ByteArray(hotspotConfig.sizeInBytes + someOffset)
        hotspotConfig.toBytes(byteArr, someOffset)
        val hotspotConfigFromBytes = HotspotConfig.fromBytes(byteArr, someOffset)

        Assert.assertEquals(hotspotConfig, hotspotConfigFromBytes)
    }

    @Test
    fun givenHotspotConfigSerialized_whenSerialized_thenWillMatch() {
        val hotspotConfig = HotspotConfig(
            nodeVirtualAddr = randomApipaAddr(),
            ssid = "test",
            passphrase = "secret",
            port = 8042,
            hotspotType = HotspotType.LOCALONLY_HOTSPOT,
        )

        val json = Json {
            encodeDefaults = true
        }

        val configJsonStr = json.encodeToString(HotspotConfig.serializer(), hotspotConfig)

        val hotspotConfigFromJson = json.decodeFromString(
            HotspotConfig.serializer(), configJsonStr
        )

        Assert.assertEquals(hotspotConfig, hotspotConfigFromJson)
    }

}