package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.mmcp.MmcpHello
import java.net.InetAddress

data class HelloEvent(
    val address: InetAddress,
    val port: Int,
    val virtualPacket: VirtualPacket,
    val mmcpHello: MmcpHello,
    val socket: VirtualNodeDatagramSocket,
) {
}