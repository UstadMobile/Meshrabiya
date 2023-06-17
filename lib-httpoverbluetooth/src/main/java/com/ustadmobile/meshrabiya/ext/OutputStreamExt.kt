package com.ustadmobile.meshrabiya.ext

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import java.io.OutputStream
import java.nio.ByteBuffer

fun OutputStream.writeAddress(address: Int){
    val buffer = ByteBuffer.wrap(ByteArray(4))
    buffer.putInt(address)
    write(buffer.array())
}

/**
 * Write the given virtual packet to the receiver output stream
 */
fun OutputStream.writeVirtualPacket(packet: VirtualPacket) {
    write(packet.header.toBytes())
    write(packet.payload, packet.payloadOffset, packet.header.payloadSize.toInt())
}
