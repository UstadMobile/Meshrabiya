package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader

/**
 * Enhanced Meshrabiya Mesh Control Protocol message (MMCP) using the new gossip protocol.
 * Replaces the old message structure with comprehensive support for all mesh network features.
 */
sealed class MmcpMessage(
    val what: Byte,
    val messageId: Int,
) {
    val header = MmcpHeader(what, messageId)

    abstract fun toBytes(): ByteArray

    fun toVirtualPacket(
        toAddr: Int,
        fromAddr: Int,
        lastHopAddr: Int = 0,
        hopCount: Byte = 0,
    ): VirtualPacket {
        val packetPayload = toBytes()
        val packetData = ByteArray(packetPayload.size + VirtualPacketHeader.HEADER_SIZE)

        System.arraycopy(packetPayload, 0, packetData, VirtualPacketHeader.HEADER_SIZE, packetPayload.size)

        return VirtualPacket.fromHeaderAndPayloadData(
            header = VirtualPacketHeader(
                toAddr = toAddr,
                toPort = 0,
                fromAddr = fromAddr,
                fromPort = 0,
                lastHopAddr = lastHopAddr,
                hopCount =  hopCount,
                maxHops = 0,
                gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE, //V3: MMCP is mesh-local
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
        // Enhanced message type constants
        const val WHAT_PING = 1.toByte()
        const val WHAT_PONG = 2.toByte()
        const val WHAT_ACK = 4.toByte()
        const val WHAT_HOTSPOT_REQUEST = 5.toByte()
        const val WHAT_HOTSPOT_RESPONSE = 6.toByte()
        const val WHAT_ORIGINATOR = 7.toByte()
        
        // New enhanced message types
        const val WHAT_NODE_ANNOUNCEMENT = 8.toByte()
        // DEPRECATED: Service advertisement system (false start - never sent or processed)
        // const val WHAT_SERVICE_ADVERTISEMENT = 9.toByte()
        // DEPRECATED: Compute task request (superseded by MeshEcosystemMessage.ComputeTaskRequestMessage)
        // const val WHAT_COMPUTE_TASK_REQUEST = 10.toByte()
        // DEPRECATED: I2P Router Advertisement (remove all usage)
        // const val WHAT_I2P_ROUTER_ADVERTISEMENT = 11.toByte()
        // DEPRECATED: Storage advertisement (superseded by OriginatorMessage with MeshRole.STORAGE)
        // const val WHAT_STORAGE_ADVERTISEMENT = 12.toByte()
        // DEPRECATED: Quorum system (false start - MmcpQuorumProposal class doesn't exist)
        // const val WHAT_QUORUM_PROPOSAL = 13.toByte()
        const val WHAT_HEARTBEAT = 14.toByte()
        // DEPRECATED: Emergency Broadcast (remove all usage)
        // const val WHAT_EMERGENCY_BROADCAST = 15.toByte()
        const val WHAT_NETWORK_METRICS = 16.toByte()
        const val WHAT_GATEWAY_ANNOUNCEMENT = 17.toByte()

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
                WHAT_ACK -> MmcpAck.fromBytes(byteArray, offset, len)
                WHAT_HOTSPOT_REQUEST -> MmcpHotspotRequest.fromBytes(byteArray, offset, len)
                WHAT_HOTSPOT_RESPONSE -> MmcpHotspotResponse.fromBytes(byteArray, offset, len)
                WHAT_ORIGINATOR -> MmcpOriginatorMessage.fromBytes(byteArray, offset, len)
                // DEPRECATED: Node announcement (superseded by MmcpOriginatorMessage - class doesn't exist)
                // WHAT_NODE_ANNOUNCEMENT -> MmcpNodeAnnouncement.fromBytes(byteArray, offset, len)
                // DEPRECATED: Service advertisement (false start - never sent or processed)
                // WHAT_SERVICE_ADVERTISEMENT -> MmcpServiceAdvertisement.fromBytes(byteArray, offset, len)
                // DEPRECATED: Compute task request (superseded by MeshEcosystemMessage.ComputeTaskRequestMessage)
                // WHAT_COMPUTE_TASK_REQUEST -> MmcpComputeTaskRequest.fromBytes(byteArray, offset, len)
                // DEPRECATED: I2P Router Advertisement (removed)
                // WHAT_I2P_ROUTER_ADVERTISEMENT -> MmcpI2PRouterAdvertisement.fromBytes(byteArray, offset, len)
                // DEPRECATED: Storage advertisement (superseded by OriginatorMessage with MeshRole.STORAGE)
                // WHAT_STORAGE_ADVERTISEMENT -> MmcpStorageAdvertisement.fromBytes(byteArray, offset, len)
                // DEPRECATED: Quorum proposal handling (false start - MmcpQuorumProposal class doesn't exist)
                // WHAT_QUORUM_PROPOSAL -> MmcpQuorumProposal.fromBytes(byteArray, offset, len)
                WHAT_HEARTBEAT -> MmcpHeartbeat.fromBytes(byteArray, offset, len)
                // DEPRECATED: Emergency Broadcast (removed)
                // WHAT_EMERGENCY_BROADCAST -> MmcpEmergencyBroadcast.fromBytes(byteArray, offset, len)
                WHAT_NETWORK_METRICS -> MmcpNetworkMetrics.fromBytes(byteArray, offset, len)
                // DEPRECATED: Gateway announcement (MmcpGatewayAnnouncement.md - class doesn't exist)
                // WHAT_GATEWAY_ANNOUNCEMENT -> MmcpGatewayAnnouncement.fromBytes(byteArray, offset, len)
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


