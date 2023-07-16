package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.portforward.ReturnPathSocketFactory
import java.lang.IllegalArgumentException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Enumeration

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

    private inline fun <T, R> Enumeration<T>.firstNotNullOfOrNull(
        transform: (T) -> R?
    ): R? {
        while(hasMoreElements()) {
            val transformed = transform(nextElement())
            if(transformed != null)
                return transformed
        }

        return null
    }

    override fun createSocket(destAddress: InetAddress, port: Int): IDatagramSocket {
        return if(
            destAddress.address.prefixMatches(node.networkPrefixLength, node.localNodeAddressByteArray)
        ) {
            node.openSocket(port)
        }else{
            val bindAddress = NetworkInterface.getNetworkInterfaces().firstNotNullOfOrNull { netInterface ->
                netInterface.interfaceAddresses.firstNotNullOfOrNull { interfaceAddress ->
                    if(interfaceAddress.address.prefixMatches(
                            interfaceAddress.networkPrefixLength.toInt(), destAddress)
                    ) {
                        interfaceAddress.address
                    }else {
                        null
                    }
                }
            }

            return bindAddress?.let { DatagramSocket(0, it).asIDatagramSocket() }
                ?: throw IllegalArgumentException("Could not find network interface with subnet " +
                        "mask for dest address $destAddress")
        }
    }

}
