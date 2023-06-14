package com.ustadmobile.httpoverbluetooth.ext

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

fun InputStream.readExactly(b: ByteArray, offset: Int, len: Int) {
    var currentOffset = offset
    var lenRemaining = len

    var bytesRead = 0
    while(lenRemaining > 0 && read(b, currentOffset, lenRemaining).also { bytesRead = it } != -1) {
        currentOffset += bytesRead
        lenRemaining -= bytesRead
    }

    if(lenRemaining != 0)
        throw IOException("readExactly: unexpected end of stream!")

}

fun InputStream.readByteArrayOfSize(size: Int): ByteArray {
    return ByteArray(size).also {
        readExactly(it,  0, size)
    }
}

fun InputStream.readRemoteAddress() : Int{
    val addressArray = readByteArrayOfSize(4)
    val byteBuffer = ByteBuffer.wrap(addressArray)
    val address = byteBuffer.getInt()
    return address
}