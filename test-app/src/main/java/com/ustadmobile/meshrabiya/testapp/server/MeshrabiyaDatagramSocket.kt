package com.ustadmobile.meshrabiya.testapp.server

import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.ByteBuffer

/**
 * Socket implementation that works with the virtual network
 */
class MeshrabiyaDatagramSocket(private val nearbyNetwork: NearbyVirtualNetwork) : DatagramSocket((null as SocketAddress?)) {
    private var messageHandler: ((String) -> Unit)? = null
    private var isBound = false

    fun setMessageHandler(handler: (String) -> Unit) {
        messageHandler = handler
    }

    override fun bind(addr: SocketAddress?) {
        if (!isBound) {
            super.bind(addr)
            isBound = true
        }
    }

    override fun send(packet: DatagramPacket) {
        if (!isBound) {
            throw SocketException("Socket not bound")
        }

        val virtualPacket = VirtualPacket.fromHeaderAndPayloadData(
            header = VirtualPacketHeader(
                fromAddr = ByteBuffer.wrap(nearbyNetwork.virtualAddress.address).int,
                toAddr = ByteBuffer.wrap(packet.address.address).int,
                fromPort = localPort,
                toPort = packet.port,
                payloadSize = packet.length,
                hopCount = 0,
                maxHops = 10,
                lastHopAddr = ByteBuffer.wrap(nearbyNetwork.virtualAddress.address).int
            ),
            data = ByteArray(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE),
            payloadOffset = VirtualPacketHeader.HEADER_SIZE
        )

        System.arraycopy(packet.data, packet.offset, virtualPacket.data, virtualPacket.payloadOffset, packet.length)
        nearbyNetwork.send(virtualPacket, packet.address)
    }

}