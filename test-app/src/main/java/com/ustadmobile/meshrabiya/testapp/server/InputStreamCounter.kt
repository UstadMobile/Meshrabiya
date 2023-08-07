package com.ustadmobile.meshrabiya.testapp.server

import java.io.FilterInputStream
import java.io.InputStream

class InputStreamCounter(
    `in`: InputStream
):  FilterInputStream(`in`){

    @Volatile
    var bytesRead: Int = 0
        private set

    @Volatile
    var closed: Boolean = false
        private set

    override fun read(): Int {
        return super.read().also {
            if(it != -1)
                bytesRead++
        }
    }

    override fun read(b: ByteArray): Int {
        return super.read(b).also {
            if(it != -1)
                bytesRead += it
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return super.read(b, off, len).also {
            if(it != -1)
                bytesRead += it
        }
    }

    override fun close() {
        super.close()
        closed = true
    }
}
