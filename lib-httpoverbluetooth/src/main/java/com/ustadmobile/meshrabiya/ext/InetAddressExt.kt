package com.ustadmobile.meshrabiya.ext

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun InetAddress.requireAddressAsInt(): Int {
    val addrData = address
    if(addrData.size != 4)
        throw IllegalArgumentException("requireAddressAsInt: not 32-bit address")

    return ByteBuffer.wrap(addrData).order(ByteOrder.BIG_ENDIAN).int
}
