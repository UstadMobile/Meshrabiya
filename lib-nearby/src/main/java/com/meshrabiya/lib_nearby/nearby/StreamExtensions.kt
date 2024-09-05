package com.meshrabiya.lib_nearby.nearby

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

fun OutputStream.writeNearbyStreamHeader(header: NearbyStreamHeader) {
    val headerBytes = header.toBytes()
    write(ByteBuffer.allocate(4).putInt(headerBytes.size).array())
    write(headerBytes)
}

fun InputStream.readNearbyStreamHeader(): NearbyStreamHeader {
    val sizeBuffer = ByteArray(4)
    read(sizeBuffer)
    val headerSize = ByteBuffer.wrap(sizeBuffer).int
    val headerBytes = ByteArray(headerSize)
    read(headerBytes)
    return NearbyStreamHeader.fromBytes(headerBytes)
}