package com.ustadmobile.meshrabiya.vnet.socket

import java.net.InetAddress

data class ChainSocketNextHop(
    val address: InetAddress,
    val port: Int,
    val isFinalDest: Boolean,
) {
}