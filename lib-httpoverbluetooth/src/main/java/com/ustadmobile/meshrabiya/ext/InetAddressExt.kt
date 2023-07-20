package com.ustadmobile.meshrabiya.ext

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

fun InetAddress.requireAddressAsInt(): Int {
    val addrData = address
    if(addrData.size != 4)
        throw IllegalArgumentException("requireAddressAsInt: not 32-bit address")

    return addrData.ip4AddressToInt()
}

fun InetAddress.requireAsIpv6() : Inet6Address {
    return this as? Inet6Address ?: throw IllegalStateException("$this not an ipv6 address")
}

fun unspecifiedIpv6Address() = Inet6Address.getByName("::").requireAsIpv6()

fun InetAddress.prefixMatches(
    networkPrefixLength: Int,
    other: InetAddress
) : Boolean {
    return address.prefixMatches(networkPrefixLength, other.address)
}

/**
 * Find a local InetAddress (if any) where the address and network prefix matches the destination.
 *
 * @param destAddress The destination address as above
 *
 * @return InetAddress that is a local network interface where the address matches the destination
 *                     up to the network prefix length of its interface, null if there is no match
 */
fun findLocalInetAddressForDestinationAddress(
    destAddress: InetAddress
) : InetAddress? {
    return NetworkInterface.getNetworkInterfaces().firstNotNullOfOrNull { netInterface ->
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
}
