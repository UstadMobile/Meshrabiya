package com.ustadmobile.meshrabiya.vnet

import java.net.DatagramPacket
import java.nio.ByteBuffer


/**
 * A VirtualPacket represents a packet being sent over the virtual mesh network. The packet data
 * includes a header (which contains the virtual to/from address and port, hop count, max hops and
 * payload size) followed by a payload of a given length in bytes as specified within the header.
 *
 * The payloadOffset is always >= VirtualPacketHeader.HEADER_SIZE - this makes it possible to convert
 * to/from a DatagramPacket without requiring a new buffer (e.g. the offset can be moved as required).
 *
 * @param data ByteArray to be used to store data
 * @param dataOffset the offset to the start of the data (the header data begins at offset, payload
 *                   data begins at dataOffset + VirtualPacketHeader.HEADER_SIZE)
 * @param header if the header is supplied - it will be written into the data. If the header is not
 * supplied, the header data MUST be in data and it will be read when constructed
 */
class VirtualPacket private constructor(
    val data: ByteArray,
    val dataOffset: Int,
    header: VirtualPacketHeader? = null,
    assertHeaderAlreadyInData: Boolean = false,
) {

    val header: VirtualPacketHeader

    init {
        this.header = header?.also {
            //copy header data into the data unless it is asserted that it is already there
            it.takeIf { !assertHeaderAlreadyInData }?.toBytes(data, dataOffset)
        } ?: VirtualPacketHeader.fromBytes(data, dataOffset)
    }

    val payloadOffset: Int
        get() = dataOffset + VirtualPacketHeader.HEADER_SIZE

    /**
     * The size required for this packet when converted into a datagram packet
     */
    val datagramPacketSize: Int
        get() = header.payloadSize + VirtualPacketHeader.HEADER_SIZE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VirtualPacket) return false

        if (header != other.header) return false
        if (!data.contentEquals(other.data)) return false
        if (dataOffset != other.dataOffset) return false

        return true
    }

    fun toDatagramPacket(): DatagramPacket {
        return DatagramPacket(
            data, dataOffset, datagramPacketSize
        )
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + dataOffset
        return result
    }

    /**
     * Update the data array to set the last hop address and increment hop count. This would typically
     * be called by the route function just before the packet is sent.
     *
     * @param lastHopAddr the value to set for the last hop address
     */
    internal fun updateLastHopAddrAndIncrementHopCountInData(
        lastHopAddr: Int
    ) {
        val byteBuffer = ByteBuffer.wrap(data, dataOffset + LAST_HOP_ADDR_OFFSET, 5)
        byteBuffer.putInt(lastHopAddr)
        byteBuffer.put((header.hopCount + 1.toByte()).toByte())
    }

    companion object {

        const val MAX_PAYLOAD_SIZE = 1500

        const val VIRTUAL_PACKET_BUF_SIZE = MAX_PAYLOAD_SIZE + VirtualPacketHeader.HEADER_SIZE

        /**
         * Offset from start of header until the bytes that contain the last hop address - see fields
         * on header to check
         */
        private const val LAST_HOP_ADDR_OFFSET = 12


        fun fromDatagramPacket(
            datagramPacket: DatagramPacket,
        ) : VirtualPacket {
            return VirtualPacket(
                data = datagramPacket.data,
                dataOffset = datagramPacket.offset
            )
        }

        fun fromData(
            data: ByteArray,
            dataOffset: Int
        ): VirtualPacket {
            return VirtualPacket(
                data = data,
                dataOffset = dataOffset,
            )
        }

        /**
         * Create a VirtualPacket from the given header and payload data
         *
         * This will write the header to the data parameter in the bytes immediately preceding the
         * payload itself (e.g. at the position of (payloadOffset - VirtualPacketHeader.HEADER_SIZE).
         *
         */
        fun fromHeaderAndPayloadData(
            header: VirtualPacketHeader,
            data: ByteArray,
            payloadOffset: Int,
            headerAlreadyInData: Boolean = false,
        ) : VirtualPacket {
            if(payloadOffset < VirtualPacketHeader.HEADER_SIZE)
                throw IllegalArgumentException("VirtualPacket buffer MUST have at least " +
                        "${VirtualPacketHeader.HEADER_SIZE} empty bytes (offset) at the beginning to allow " +
                        "for conversion to/from DatagramPacket without creating a new buffer")

            return VirtualPacket(
                data = data,
                dataOffset = payloadOffset - VirtualPacketHeader.HEADER_SIZE,
                header = header,
                assertHeaderAlreadyInData = headerAlreadyInData,
            )
        }
    }
}
