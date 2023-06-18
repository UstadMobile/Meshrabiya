package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader

/**
 * Meshrabiya Mesh Control Protocol message (MMCP) is like ICMP for the mesh network. Used to send
 * routing info, pings, etc.
 */
sealed class MmcpMessage(
    val what: Byte
) {

    abstract fun toBytes(): ByteArray

    fun toVirtualPacket(toAddr: Int, fromAddr: Int): VirtualPacket {
        val packetPayload = toBytes()
        return VirtualPacket(
            header = VirtualPacketHeader(
                toAddr = toAddr,
                toPort = 0,
                fromAddr = fromAddr,
                fromPort = 0,
                hopCount =  0,
                maxHops = 0,
                payloadSize = packetPayload.size
            ),
            payload = packetPayload
        )
    }


    companion object {

        const val WHAT_PING = 1.toByte()

        const val WHAT_PONG = 2.toByte()

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpMessage {
            return when(val what = byteArray[0]) {
                WHAT_PING -> MmcpPing.fromBytes(byteArray, offset, len)
                WHAT_PONG -> MmcpPong.fromBytes(byteArray)
                else -> throw IllegalArgumentException("Mmcp: Invalid what: $what")
            }
        }


        fun whatAndPayloadFromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): Pair<Byte, ByteArray> {
            val what = byteArray[offset]
            val mmcpPayload = ByteArray(len - 1)

            System.arraycopy(byteArray, offset + 1, mmcpPayload, 0, mmcpPayload.size)
            return Pair(what, mmcpPayload)
        }

        fun whatAndPayloadToBytes(what: Byte, payload: ByteArray): ByteArray {
            val byteArray = ByteArray(payload.size + 1)
            byteArray[0] = what
            System.arraycopy(payload, 0, byteArray, 1, payload.size)
            return byteArray
        }


    }

}


