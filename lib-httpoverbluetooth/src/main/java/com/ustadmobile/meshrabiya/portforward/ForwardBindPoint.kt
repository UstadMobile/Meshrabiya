package com.ustadmobile.meshrabiya.portforward

import com.ustadmobile.meshrabiya.vnet.VirtualNode
import java.net.InetAddress

/**
 * Represents a binding point for a forwarding rule. Forwarding can be bound to a specific address
 * (e.g. the loopback address) or it can be bound to a zone - the virtual network zone or the real
 * network zone.
 */
internal data class ForwardBindPoint(
    val listenInterface: InetAddress?,
    val listenZone: VirtualNode.Zone?,
    val listenPort: Int,
) {
}