package com.ustadmobile.meshrabiya.vnet

import java.net.DatagramPacket
import java.net.DatagramSocket

class IDatagramSocketAdapter(
    private val realSocket: DatagramSocket
) : IDatagramSocket{

    override val localPort: Int
        get() = realSocket.localPort

    override fun receive(datagramPacket: DatagramPacket) {
        realSocket.receive(datagramPacket)
    }

    override fun send(datagramPacket: DatagramPacket) {
        realSocket.send(datagramPacket)
    }

    override fun close() {
        realSocket.close()
    }
}

fun DatagramSocket.asIDatagramSocket(): IDatagramSocket = IDatagramSocketAdapter(this)

