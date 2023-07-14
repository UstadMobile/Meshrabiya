package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.portforward.ReturnPathSocketFactory
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Implementation of return path socket factory that can create an IDatagramSocket for the real
 * local loopback or the virtual network.
 */
class VirtualNodeReturnPathSocketFactory(
    private val node: VirtualNode,
): ReturnPathSocketFactory {

    //Should use subnet/mask, but for now, this will work
    private fun isVirtualAddr(address: InetAddress): Boolean {
        return address is Inet4Address && address.address[0] == 169.toByte() &&
                address.address[1] == 254.toByte()
     }


    override fun createSocket(destAddress: InetAddress, port: Int): IDatagramSocket {
        return if(isVirtualAddr(destAddress)) {
            node.openSocket(port)
        }else {
            DatagramSocket().asIDatagramSocket()
        }
    }

}