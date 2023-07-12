package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader

/**
 * Meshrabiya Mesh Control Protocol message (MMCP) is like ICMP for the mesh network. Used to send
 * routing info, pings, etc.
 */
sealed class MmcpMessage(
    val what: Byte,
    val messageId: Int,
) {
    val header = MmcpHeader(what, messageId)

    abstract fun toBytes(): ByteArray

    fun toVirtualPacket(toAddr: Int, fromAddr: Int): VirtualPacket {
        val packetPayload = toBytes()
        val packetData = ByteArray(packetPayload.size + VirtualPacketHeader.HEADER_SIZE)

        System.arraycopy(packetPayload, 0, packetData, VirtualPacketHeader.HEADER_SIZE, packetPayload.size)

        return VirtualPacket.fromHeaderAndPayloadData(
            header = VirtualPacketHeader(
                toAddr = toAddr,
                toPort = 0,
                fromAddr = fromAddr,
                fromPort = 0,
                lastHopAddr = 0,
                hopCount =  0,
                maxHops = 0,
                payloadSize = packetPayload.size
            ),
            data =packetData,
            payloadOffset = VirtualPacketHeader.HEADER_SIZE,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MmcpMessage) return false

        if (what != other.what) return false
        if (messageId != other.messageId) return false
        if (header != other.header) return false

        return true
    }

    override fun hashCode(): Int {
        var result = what.toInt()
        result = 31 * result + messageId
        result = 31 * result + header.hashCode()
        return result
    }


    companion object {

        const val WHAT_PING = 1.toByte()

        const val WHAT_PONG = 2.toByte()

        const val WHAT_HELLO = 3.toByte()

        const val WHAT_ACK = 4.toByte()

        const val WHAT_HOTSPOT_REQUEST = 5.toByte()

        const val WHAT_HOTSPOT_RESPONSE = 6.toByte()

        const val WHAT_ORIGINATOR = 7.toByte()

        const val MMCP_HEADER_LEN = 5 //1 byte what, 4 bytes message id

        fun fromVirtualPacket(
            packet: VirtualPacket
        ): MmcpMessage {

            return fromBytes(
                byteArray = packet.data,
                offset = packet.payloadOffset,
                len = packet.header.payloadSize
            )
        }

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpMessage {
            return when(val what = byteArray[offset]) {
                WHAT_PING -> MmcpPing.fromBytes(byteArray, offset, len)
                WHAT_PONG -> MmcpPong.fromBytes(byteArray, offset, len)
                WHAT_HELLO -> MmcpHello.fromBytes(byteArray, offset, len)
                WHAT_ACK -> MmcpAck.fromBytes(byteArray, offset, len)
                WHAT_HOTSPOT_REQUEST -> MmcpHotspotRequest.fromBytes(byteArray, offset, len)
                WHAT_HOTSPOT_RESPONSE -> MmcpHotspotResponse.fromBytes(byteArray, offset, len)
                else -> throw IllegalArgumentException("Mmcp: Invalid what: $what")
            }
        }



        fun mmcpHeaderAndPayloadFromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): Pair<MmcpHeader, ByteArray> {
            val header = MmcpHeader.fromBytes(byteArray, offset)

            val mmcpPayload = ByteArray(len - MMCP_HEADER_LEN)

            System.arraycopy(byteArray, offset + MMCP_HEADER_LEN, mmcpPayload, 0, mmcpPayload.size)
            return Pair(header, mmcpPayload)
        }

        fun headerAndPayloadToBytes(header: MmcpHeader, payload: ByteArray): ByteArray {
            val byteArray = ByteArray(payload.size + MMCP_HEADER_LEN)
            header.toBytes(byteArray, 0)
            System.arraycopy(payload, 0, byteArray, MMCP_HEADER_LEN, payload.size)
            return byteArray
        }


    }

}


