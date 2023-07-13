package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The originator message is used to track routes around the mesh, roughly similar to the BATMAN protocol.
 *
 * @param pingTimeSum the likely sum of the ping time along the journey this message has taken. When
 *                    the message reaches a node, the node at each hop adds to the ping time as it
 *                    is received based on the most recent known ping time of the node that last relayed
 *                    it.
 */
class MmcpOriginatorMessage(
    messageId: Int,
    val pingTimeSum: Short,
    val connectConfig: WifiConnectConfig?,
): MmcpMessage(WHAT_ORIGINATOR, messageId) {
    override fun toBytes(): ByteArray {
        val connectConfigSize = connectConfig?.sizeInBytes ?: 0
        //size will be : ping time sum (2 bytes) + connect config size (2 bytes) + connect config
        val payloadSize = 2 + 2 + connectConfigSize
        val payload = ByteArray(payloadSize)
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            .putShort(pingTimeSum)
            .putShort(connectConfigSize.toShort())
        connectConfig?.toBytes(payload, 4)

        return headerAndPayloadToBytes(header, payload)
    }

    companion object {

        /**
         * When originator messages are being broadcasted the ping time is incremented.
         */
        fun incrementPingTimeSum(
            packet: VirtualPacket,
            pingTimeIncrement: Short,
        ) {
            //The MMCP what byte is always the first byte of an MMCP message -
            // see MmcpHeader.fromBytes
            val what = packet.data[packet.payloadOffset]
            if(what != WHAT_ORIGINATOR)
                throw IllegalArgumentException("This is NOT an originator message")

            //The offset to the time is the payload offset plus the MMCP header
            val timeOffset = packet.payloadOffset + MMCP_HEADER_LEN
            val readBuf = ByteBuffer.wrap(packet.data, timeOffset, 2)
                .order(ByteOrder.BIG_ENDIAN)
            val setPingTime = readBuf.short
            val writeBuf = ByteBuffer.wrap(packet.data, timeOffset, 2)
                .order(ByteOrder.BIG_ENDIAN)
            writeBuf.putShort((setPingTime + pingTimeIncrement).toShort())
        }

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int,
            len: Int = byteArray.size
        ): MmcpOriginatorMessage {
            val header = MmcpHeader.fromBytes(byteArray, offset)

            val byteBuf = ByteBuffer
                .wrap(byteArray, offset + MMCP_HEADER_LEN, len - offset - MMCP_HEADER_LEN)
                .order(ByteOrder.BIG_ENDIAN)
            val pingTimeSum = byteBuf.short
            val connectConfigSize = byteBuf.short
            val connectConfig = if(connectConfigSize > 0) {
                WifiConnectConfig.fromBytes(byteArray, offset + MMCP_HEADER_LEN + 4)
            }else {
                null
            }

            return MmcpOriginatorMessage(header.messageId, pingTimeSum, connectConfig)
        }

    }
}