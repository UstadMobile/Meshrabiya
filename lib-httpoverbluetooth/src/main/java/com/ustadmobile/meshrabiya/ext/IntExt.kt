package com.ustadmobile.meshrabiya.ext

import java.nio.ByteBuffer
import java.nio.ByteOrder


fun Int.addressToDotNotation() : String {
    return "${(this shr 24).and(0xff)}.${(this shr 16).and(0xff)}" +
            ".${(this shr 8).and(0xff)}.${this.and(0xff)}"
}

fun Int.addressToByteArray(): ByteArray {
    return ByteBuffer.wrap(ByteArray(4))
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(this)
        .array()
}
