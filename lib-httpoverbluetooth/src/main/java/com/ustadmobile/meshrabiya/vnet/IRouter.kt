package com.ustadmobile.meshrabiya.vnet

interface IRouter {

    /**
     * Route the given incoming packet.
     */
    fun route(
        from: Int,
        packet: VirtualPacket
    )

}
