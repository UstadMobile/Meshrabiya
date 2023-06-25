package com.ustadmobile.meshrabiya.vnet.localhotspot

import org.junit.Assert
import org.junit.Test

class HotspotConfigTest {

    @Test
    fun givenHotspotConfigWithSsidAndPassphrase_whenConvertedToAndFromBytes_thenWillBeEqual() {
        val hotspotConfig = HotspotConfig(
            ssid = "test",
            passphrase = "secret",
            port = 8042
        )

        val someOffset = 5//just to test that the offset is used appropriately

        val byteArr = ByteArray(hotspotConfig.sizeInBytes + someOffset)
        hotspotConfig.toBytes(byteArr, someOffset)
        val hotspotConfigFromBytes = HotspotConfig.fromBytes(byteArr, someOffset)

        Assert.assertEquals(hotspotConfig, hotspotConfigFromBytes)
    }

}