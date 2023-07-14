package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.ext.requireAsIpv6
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotPersistenceType
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import org.junit.Assert
import org.junit.Test
import java.net.Inet6Address

class MmcpOriginatorMessageTest {

    @Test
    fun givenOriginatorMessage_whenSerializedThenDeserialized_shouldBeEqual() {
        val originatorMessage = MmcpOriginatorMessage(
            messageId = 1042,
            pingTimeSum = 200.toShort(),
            connectConfig = WifiConnectConfig(
                nodeVirtualAddr = 1000,
                ssid = "test",
                passphrase = "apassword",
                linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
                port = 1023,
                hotspotType = HotspotType.WIFIDIRECT_GROUP,
                persistenceType = HotspotPersistenceType.FULL,
            )
        )

        //Apply an offset to ensure this works as expected
        val originatorBytes = originatorMessage.toBytes()
        val byteArray = ByteArray(1500)
        val offset = 42
        System.arraycopy(originatorBytes, 0, byteArray, offset, originatorBytes.size)

        val messageFromBytes = MmcpOriginatorMessage.fromBytes(byteArray, offset)

        Assert.assertEquals(originatorMessage, messageFromBytes)
    }

    @Test
    fun givenOriginatorMessage_whenConvertedToPacketAndPingTimeIncremented_thenPingTimeShouldMatchExpectedVal() {
        val originatorMessage = MmcpOriginatorMessage(
            messageId = 1042,
            pingTimeSum = 32.toShort(),
            connectConfig = WifiConnectConfig(
                nodeVirtualAddr = 1000,
                ssid = "test",
                passphrase = "apassword",
                linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
                port = 1023,
                hotspotType = HotspotType.WIFIDIRECT_GROUP,
                persistenceType = HotspotPersistenceType.FULL,
            )
        )

        val packet = originatorMessage.toVirtualPacket(
            toAddr = ADDR_BROADCAST,
            fromAddr =  1000
        )

        val pingTimeIncrement = 32.toShort()
        MmcpOriginatorMessage.incrementPingTimeSum(packet, pingTimeIncrement)

        val messageFromPacket = MmcpMessage.fromVirtualPacket(packet) as MmcpOriginatorMessage

        Assert.assertEquals((originatorMessage.pingTimeSum + pingTimeIncrement).toShort(), messageFromPacket.pingTimeSum)
        Assert.assertEquals(originatorMessage.connectConfig, messageFromPacket.connectConfig)
    }


}