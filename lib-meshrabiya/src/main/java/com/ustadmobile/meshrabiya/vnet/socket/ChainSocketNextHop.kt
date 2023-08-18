package com.ustadmobile.meshrabiya.vnet.socket

import android.net.Network
import java.net.InetAddress

data class ChainSocketNextHop(
    val address: InetAddress,
    val port: Int,
    val isFinalDest: Boolean,
    val network: Network?,
) {
}