package com.ustadmobile.meshrabiya.vnet

import java.io.Closeable
import java.net.DatagramPacket

/**
 * Common interface representing a DatagramSocket. This can be used on a normal datagramsocket or a
 * VirtualDatagramSocket
 */
interface IDatagramSocket: Closeable {

    val localPort: Int

    fun receive(datagramPacket: DatagramPacket)

    fun send(datagramPacket: DatagramPacket)

}