package com.ustadmobile.meshrabiya.portforward

import com.ustadmobile.meshrabiya.vnet.IDatagramSocket
import java.net.InetAddress

fun interface ReturnPathSocketFactory {

    fun createSocket(
        destAddress: InetAddress,
        port: Int,
    ): IDatagramSocket

}
