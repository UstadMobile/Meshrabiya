package com.meshrabiya.lib_nearby.nearby

import java.io.InputStream
import java.io.OutputStream


fun OutputStream.writeNearbyStreamHeader(header: NearbyStreamHeader) {
    write(header.toBytes())
}

fun InputStream.readNearbyStreamHeader(): NearbyStreamHeader {
    val headerBytes = ByteArray(NearbyStreamHeader.HEADER_SIZE)
    read(headerBytes)
    return NearbyStreamHeader.fromBytes(headerBytes)
}