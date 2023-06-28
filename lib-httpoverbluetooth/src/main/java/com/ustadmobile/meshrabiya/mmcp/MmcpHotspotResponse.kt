package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse

class MmcpHotspotResponse(
    messageId: Int,
    val result: LocalHotspotResponse,
) : MmcpMessage(WHAT_HOTSPOT_RESPONSE, messageId) {

    override fun toBytes(): ByteArray {
        return headerAndPayloadToBytes(header, result.toBytes())
    }

    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpHotspotResponse {
            try {
                val (header, payload) = mmcpHeaderAndPayloadFromBytes(
                    byteArray, offset, len
                )

                val response = LocalHotspotResponse.fromBytes(payload, 0)
                return MmcpHotspotResponse(header.messageId, response)
            }catch(e: Exception) {
                println("FFS")
                e.printStackTrace()
                throw e
            }
        }
    }
}
