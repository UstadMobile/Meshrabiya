package com.ustadmobile.meshrabiya.vnet

data class VirtualPacket(
    val header: VirtualPacketHeader,
    val payload: ByteArray,
    val payloadOffset: Int = 0,
) {


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VirtualPacket) return false

        if (header != other.header) return false
        if (!payload.contentEquals(other.payload)) return false
        if (payloadOffset != other.payloadOffset) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + payloadOffset
        return result
    }

    companion object {

        const val MAX_SIZE = 1500

    }
}
