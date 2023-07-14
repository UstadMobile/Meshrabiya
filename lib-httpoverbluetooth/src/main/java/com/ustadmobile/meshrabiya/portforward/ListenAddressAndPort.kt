package com.ustadmobile.meshrabiya.portforward

import java.net.InetAddress

data class ListenAddressAndPort(
    val listenInterface: InetAddress,
    val listenPort: Int,
) {
}