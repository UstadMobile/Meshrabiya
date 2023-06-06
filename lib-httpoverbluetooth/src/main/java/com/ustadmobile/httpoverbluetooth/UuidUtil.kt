package com.ustadmobile.httpoverbluetooth

import java.nio.ByteBuffer
import java.util.UUID

fun UUID.toBytes(): ByteArray {
    val byteBuffer = ByteBuffer.wrap(ByteArray(16))
    byteBuffer.putLong(mostSignificantBits)
    byteBuffer.putLong(leastSignificantBits)
    return byteBuffer.array()
}

object UuidUtil {

    fun uuidFromBytes(bytes: ByteArray): UUID {
        val byteBuffer = ByteBuffer.wrap(bytes)
        val mostSigBits = byteBuffer.long
        val leastSigBits = byteBuffer.long
        return UUID(mostSigBits, leastSigBits)
    }

}