package com.ustadmobile.meshrabiya.test

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import kotlin.random.Random

fun newVirtualPacketWithRandomPayload(
    toAddr: Int,
    toPort: Int,
    fromAddr: Int,
    fromPort: Int,
    lastHopAddr: Int = fromAddr,
    payloadSize: Int
): VirtualPacket {
    val buffer = ByteArray(payloadSize + VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)
    Random.Default.nextBytes(buffer, VirtualPacketHeader.HEADER_SIZE)
    return VirtualPacket.fromHeaderAndPayloadData(
        header = VirtualPacketHeader(
            toAddr = toAddr,
            toPort = toPort,
            fromAddr = fromAddr,
            fromPort = fromPort,
            lastHopAddr = lastHopAddr,
            hopCount = 0,
            maxHops = 8,
            payloadSize = payloadSize
        ),
        data = buffer,
        payloadOffset = VirtualPacketHeader.HEADER_SIZE
    )
}