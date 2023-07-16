package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.portforward.ReturnPathSocketFactory
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Implementation of return path socket factory that can create an IDatagramSocket for the real
 * local loopback or the virtual network.
 */
class VirtualNodeReturnPathSocketFactory(
    private val node: VirtualNode,
): ReturnPathSocketFactory {

    override fun createSocket(destAddress: InetAddress, port: Int): IDatagramSocket {
        return if(
            destAddress.address.prefixMatches(node.networkPrefixLength, node.localNodeAddressByteArray)
        ) {
            node.openSocket(port)
        }else{
            DatagramSocket().asIDatagramSocket()
        }
    }

}