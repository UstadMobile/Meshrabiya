package com.ustadmobile.meshrabiya.vnet

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


    companion object {

        /**
         * Broadcast address - 255.255.255.255 (-1)
         */
        const val ADDR_BROADCAST = (255 shl 24).or(255 shl 16).or(255 shl 8).or(255)

    }

}
