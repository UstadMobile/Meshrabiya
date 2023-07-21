package com.ustadmobile.meshrabiya.vnet.datagram

import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.DatagramSocketImpl
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress

class VirtualDatagramSocketImpl(
    private val router: VirtualRouter
): DatagramSocketImpl() {

    init {

    }

    override fun setOption(optID: Int, value: Any?) {

    }

    override fun getOption(optId: Int): Any? {
        return null
    }

    override fun create() {
        TODO("Not yet implemented")
    }

    override fun bind(p0: Int, p1: InetAddress?) {
        TODO("Not yet implemented")
    }

    override fun send(p0: DatagramPacket?) {
        TODO("Not yet implemented")
    }

    override fun peek(p0: InetAddress?): Int {
        TODO("Not yet implemented")
    }

    override fun peekData(p0: DatagramPacket?): Int {
        TODO("Not yet implemented")
    }

    override fun receive(p0: DatagramPacket?) {
        TODO("Not yet implemented")
    }

    @Deprecated("Deprecated in Java")
    override fun setTTL(p0: Byte) {
        TODO("Not yet implemented")
    }

    @Deprecated("Deprecated in Java")
    override fun getTTL(): Byte {
        TODO("Not yet implemented")
    }

    override fun setTimeToLive(p0: Int) {
        TODO("Not yet implemented")
    }

    override fun getTimeToLive(): Int {
        TODO("Not yet implemented")
    }

    override fun join(p0: InetAddress?) {
        TODO("Not yet implemented")
    }

    override fun leave(p0: InetAddress?) {
        TODO("Not yet implemented")
    }

    override fun joinGroup(p0: SocketAddress?, p1: NetworkInterface?) {
        TODO("Not yet implemented")
    }

    override fun leaveGroup(p0: SocketAddress?, p1: NetworkInterface?) {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }
}