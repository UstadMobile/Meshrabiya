package com.ustadmobile.meshrabiya.ext

import java.net.InetAddress

fun InetAddress.requireAddressAsInt(): Int {
    val addrData = address
    if(addrData.size != 4)
        throw IllegalArgumentException("requireAddressAsInt: not 32-bit address")

    return addrData.ip4AddressToInt()
}
