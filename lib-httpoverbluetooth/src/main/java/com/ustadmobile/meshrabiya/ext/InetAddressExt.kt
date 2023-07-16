package com.ustadmobile.meshrabiya.ext

import java.net.Inet6Address
import java.net.InetAddress

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
