package com.ustadmobile.meshrabiya.ext

import android.net.LinkProperties
import android.os.Build

fun LinkProperties.toPrettyString(): String {
    val dhcpServerStr = if(Build.VERSION.SDK_INT >= 30) {
        dhcpServerAddress.toString()
    }else {
        ""
    }

    return "LinkProperties: dhcpServer=$dhcpServerStr "
}
