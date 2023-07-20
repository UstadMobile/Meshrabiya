package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.findLocalInetAddressForDestinationAddress
import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.portforward.ReturnPathSocketFactory
import java.lang.IllegalArgumentException
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Implementation of return path socket factory that can create an IDatagramSocket for the real
 * network or a virtual datagram socket.
 *
 * If a destination is on the real network, then the created socket will be bound to the network
 * interface where the netmask matches the given destination address.
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
            val bindAddress = findLocalInetAddressForDestinationAddress(destAddress)

            return bindAddress?.let { DatagramSocket(0, it).asIDatagramSocket() }
                ?: throw IllegalArgumentException("Could not find network interface with subnet " +
                        "mask for dest address $destAddress")
        }
    }

}
