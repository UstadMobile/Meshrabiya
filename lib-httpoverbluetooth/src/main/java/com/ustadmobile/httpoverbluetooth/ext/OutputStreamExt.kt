package com.ustadmobile.httpoverbluetooth.ext

import java.io.OutputStream
import java.nio.ByteBuffer

fun OutputStream.writeAddress(address: Int){
    val buffer = ByteBuffer.wrap(ByteArray(4))
    buffer.putInt(address)
    write(buffer.array())
}
