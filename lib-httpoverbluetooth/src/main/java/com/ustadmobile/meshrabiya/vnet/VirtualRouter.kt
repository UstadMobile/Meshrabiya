package com.ustadmobile.meshrabiya.vnet

/**
 * Represents the netwrok
 */
interface VirtualRouter {

    /**
     * Route the given incoming packet.
     */
    fun route(
        packet: VirtualPacket
    )

    fun nextMmcpMessageId(): Int


    /**
     * Allocate a port on the virtual router
     */
    fun allocatePortOrThrow(
        protocol: Protocol,
        portNum: Int
    ): Int

}
