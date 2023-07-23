package com.ustadmobile.meshrabiya.portforward

import java.net.DatagramSocket
import java.net.InetAddress

fun interface ReturnPathSocketFactory {

    fun createSocket(
        destAddress: InetAddress,
        port: Int,
    ): DatagramSocket

}
