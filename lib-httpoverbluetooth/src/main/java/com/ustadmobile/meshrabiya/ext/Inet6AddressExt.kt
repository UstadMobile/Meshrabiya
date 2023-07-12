package com.ustadmobile.meshrabiya.ext

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Get the IPv6 address without any interface scope ( without @interfacename )
 */
fun Inet6Address.withoutScope(): Inet6Address {
    return Inet6Address.getByAddress(this.address) as Inet6Address
}

fun InetAddress.requireHostAddress(): String {
    return hostAddress ?: throw IllegalStateException("No host address on address $this")
}
