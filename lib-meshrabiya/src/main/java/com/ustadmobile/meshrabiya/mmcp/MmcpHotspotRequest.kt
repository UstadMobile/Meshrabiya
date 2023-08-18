package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MmcpHotspotRequest(
    messageId: Int,
    val hotspotRequest: LocalHotspotRequest,
): MmcpMessage(WHAT_HOTSPOT_REQUEST, messageId) {


    override fun toBytes(): ByteArray {
        return headerAndPayloadToBytes(header,
            ByteBuffer.wrap(ByteArray(2))
                .put(hotspotRequest.preferredBand.flag)
                .put(hotspotRequest.preferredType.flag)
                .array()
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MmcpHotspotRequest) return false
        if (!super.equals(other)) return false

        if (hotspotRequest != other.hotspotRequest) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + hotspotRequest.hashCode()
        return result
    }

    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpHotspotRequest {
            val (header, payload) = mmcpHeaderAndPayloadFromBytes(
                byteArray, offset, len
            )

            val byteBuffer = ByteBuffer.wrap(payload)
                .order(ByteOrder.BIG_ENDIAN)

            val preferredBand = ConnectBand.fromFlag(byteBuffer.get())
            val preferredHotspotType = HotspotType.fromFlag(byteBuffer.get())

            return MmcpHotspotRequest(
                messageId = header.messageId,
                hotspotRequest = LocalHotspotRequest(preferredBand, preferredHotspotType)
            )
        }

    }
}