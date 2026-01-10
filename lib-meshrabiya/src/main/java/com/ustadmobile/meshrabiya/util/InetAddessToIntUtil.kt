package com.ustadmobile.meshrabiya.util

import java.net.InetAddress

fun InetAddress.addressAsInt(): Int {
    val bytes = this.address
    require(bytes.size == 4) { "Only IPv4 addresses are supported" }
    return ((bytes[0].toInt() and 0xFF) shl 24) or
           ((bytes[1].toInt() and 0xFF) shl 16) or
           ((bytes[2].toInt() and 0xFF) shl 8) or
           (bytes[3].toInt() and 0xFF)
}