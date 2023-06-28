package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MmcpHotspotRequest(
    messageId: Int,
    val hotspotRequest: LocalHotspotRequest,
): MmcpMessage(WHAT_HOTSPOT_REQUEST, messageId) {


    override fun toBytes(): ByteArray {
        return headerAndPayloadToBytes(header,
            ByteBuffer.wrap(ByteArray(4))
                .put(if(hotspotRequest.is5GhzSupported) 1 else 0)
                .array()
        )
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
            val is5GhzSupported =  ByteBuffer.wrap(payload)
                .order(ByteOrder.BIG_ENDIAN)
                .get() == 1.toByte()

            return MmcpHotspotRequest(
                messageId = header.messageId,
                hotspotRequest = LocalHotspotRequest(is5GhzSupported)
            )
        }

    }
}