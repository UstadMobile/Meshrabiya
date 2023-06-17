package com.ustadmobile.meshrabiya.vnet

data class VirtualPacket(
    val header: VirtualPacketHeader,
    val payload: ByteArray,
    val payloadOffset: Int = 0,
) {
}
