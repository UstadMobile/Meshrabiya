package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse

class MmcpHotspotResponse(
    messageId: Int,
    val result: LocalHotspotResponse,
) : MmcpMessage(WHAT_HOTSPOT_RESPONSE, messageId) {

    override fun toBytes(): ByteArray {
        return headerAndPayloadToBytes(header, result.toBytes())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MmcpHotspotResponse) return false
        if (!super.equals(other)) return false

        if (result != other.result) return false

        return true
    }

    override fun hashCode(): Int {
        var result1 = super.hashCode()
        result1 = 31 * result1 + result.hashCode()
        return result1
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
