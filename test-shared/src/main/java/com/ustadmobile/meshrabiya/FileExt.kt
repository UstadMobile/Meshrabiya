package com.ustadmobile.meshrabiya

import java.io.File
import java.io.FileInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.random.Random


fun File.writeRandomData(size: Int) {
    val buf = ByteArray(8192)
    var bytesWritten = 0
    outputStream().use { outStream ->
        while(bytesWritten < size) {
            val len = minOf(buf.size, size - bytesWritten)
            Random.nextBytes(buf, 0, len)
            outStream.write(buf, 0, len)
            bytesWritten += len
        }
        outStream.flush()
    }
}

fun File.md5sum(): ByteArray {
    val messageDigest = MessageDigest.getInstance("MD5")
    val inStream = FileInputStream(this)
    val digestInputStream = DigestInputStream(inStream, messageDigest)

    val buf = ByteArray(8192)
    digestInputStream.use {
        while(inStream.read(buf) != -1) {
            //do nothing - just read through to get the md5sum
        }
    }

    return messageDigest.digest()
}
