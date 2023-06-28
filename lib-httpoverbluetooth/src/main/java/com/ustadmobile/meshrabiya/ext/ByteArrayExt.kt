package com.ustadmobile.meshrabiya.ext

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun ByteArray.ip4AddressToInt() : Int{
    return ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN).int
}
