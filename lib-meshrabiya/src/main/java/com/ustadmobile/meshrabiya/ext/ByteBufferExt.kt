package com.ustadmobile.meshrabiya.ext

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Put a string into the byte buffer (based on the encoding it into bytes). This will first put an
 * int with the length of the bytearray, and then the bytearray itself.
 *
 * Length = -1 is used to store null
 */
fun ByteBuffer.putStringFromBytes(
    strBytes: ByteArray?
): ByteBuffer {
    if(strBytes != null) {
        putInt(strBytes.size)
        put(strBytes)
    }else {
        putInt(-1)
    }
    return this
}

/**
 * Get a string that was stored using putStringFromBytes
 */
fun ByteBuffer.getString(): String? {
    val len = int
    if(len != -1) {
        val strBytes = ByteArray(len)
        get(strBytes)
        return String(strBytes)
    }else {
        return null
    }
}

fun ByteBuffer.getStringOrThrow() : String {
    return getString() ?: throw NullPointerException("ByteBuffer.getStringOrThrow: stored string was null")
}

fun ByteBuffer.putInet4Address(inetAddress: InetAddress): ByteBuffer {
    val addressBytes = inetAddress.address
    if(addressBytes.size != 4)
        throw IllegalArgumentException("putInetAddr: expected address of 4 bytes got ${addressBytes.size}")

    put(inetAddress.address)

    return this
}

fun ByteBuffer.getInet4Address(): InetAddress {
    val addressBytes = ByteArray(4)
    get(addressBytes)
    return InetAddress.getByAddress(addressBytes)
}

