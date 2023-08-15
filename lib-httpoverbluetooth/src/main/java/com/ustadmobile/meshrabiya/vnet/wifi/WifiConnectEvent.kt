package com.ustadmobile.meshrabiya.vnet.wifi

import com.ustadmobile.meshrabiya.vnet.VirtualNodeDatagramSocket
import java.net.InetAddress

/**
 * Event triggered by the MeshrabiyaWifiManager when a new connection
 */
data class WifiConnectEvent(
    val neighborPort: Int,
    val neighborInetAddress: InetAddress,
    val socket: VirtualNodeDatagramSocket,
    val neighborVirtualAddress: Int,
)
