package com.ustadmobile.meshrabiya.ext

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Attempt to read exactly the given number of bytes. Read() may read zero to len bytes each time it
 * is invoked.
 *
 * This function will attempt to read exactly the given number of bytes. The number of bytes read
 * will only be less than len if the end of stream is reached
 */
fun InputStream.readExactly(b: ByteArray, offset: Int, len: Int): Int {
    var currentOffset = offset
    var lenRemaining = len

    var bytesRead = 0
    while(lenRemaining > 0 && read(b, currentOffset, lenRemaining).also { bytesRead = it } != -1) {
        currentOffset += bytesRead
        lenRemaining -= bytesRead
    }

    return bytesRead
}

fun InputStream.readExactlyOrThrow(b: ByteArray, offset: Int, len: Int) {
    val bytesRead = readExactly(b, offset, len)
    if(bytesRead != len)
        throw IOException("Read only or throw: could not read $len bytes (read $bytesRead)")
}

fun InputStream.readByteArrayOfSize(size: Int): ByteArray? {
    val byteArray = ByteArray(size)
    val bytesRead = readExactly(byteArray, 0, size)
    return if(bytesRead == size)
        byteArray
    else
        null
}

fun InputStream.readRemoteAddress() : Int{
    val addressArray = readByteArrayOfSize(4) ?: throw IOException("readRemoteAddress: Could not read 4 bytes")

    val byteBuffer = ByteBuffer.wrap(addressArray)
    val address = byteBuffer.getInt()
    return address
}

/**
 * Read a Virtual Packet from the receiver InputStream. Will read the packet data into the given
 * buffer, which will be returned as part of the virtualpacket.
 */
fun InputStream.readVirtualPacket(
    buffer: ByteArray,
    offset: Int,
) : VirtualPacket? {
    val headerBytes = readByteArrayOfSize(VirtualPacketHeader.HEADER_SIZE) ?: return null
    val header = VirtualPacketHeader.fromBytes(headerBytes)

    readExactlyOrThrow(buffer, offset, header.payloadSize.toInt())

    return VirtualPacket(
        header = header,
        payload = buffer,
        payloadOffset = offset
    )
}
