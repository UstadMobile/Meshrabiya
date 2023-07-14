package com.ustadmobile.meshrabiya.vnet

import java.net.DatagramPacket

/**
 * Represents the netwrok
 */
interface VirtualRouter {

    /**
     * Route the given incoming packet.
     *
     * @param packet the packet received
     */
    fun route(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket? = null,
        virtualNodeDatagramSocket: VirtualNodeDatagramSocket? = null,
    )

    fun nextMmcpMessageId(): Int

    /**
     * The default datagram socket local port (not bound to any network). Used to send/receive
     * VirtualPackets over the real network.
     */
    val localDatagramPort: Int


    /**
     * Allocate a port on the virtual router
     */
    fun allocatePortOrThrow(
        protocol: Protocol,
        portNum: Int
    ): Int

    fun deallocatePort(
        protocol: Protocol,
        portNum: Int
    )


    companion object {



    }

}
