package com.ustadmobile.meshrabiya.vnet.socket

import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * This is a replacement for the no-args Socket constructor, where the destination is not known
 * when the socket is constructed.
 *
 * If the intended address is provided at the time of construction, we can't override the connect
 * function because connect gets called as part of the superclass initialization. At the time of
 * superclass initialization, child class variables (e.g. the virtual router) will not be initialized.
 *
 * It would be possible to use extension functions to adjust the call to super initialization, but
 * it would not be possible to determine if this was the final hop (or not).
 */
class ChainSocket(private val virtualRouter: VirtualRouter): Socket() {

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val endpointInetAddr = endpoint as? InetSocketAddress
        val address = endpointInetAddr?.address
        if(
            address?.prefixMatches(
                virtualRouter.networkPrefixLength, virtualRouter.localNodeInetAddress
            ) == true
        ) {
            val nextHop = virtualRouter.lookupNextHopForChainSocket(
                endpointInetAddr.address, endpoint.port
            )
            super.connect(InetSocketAddress(nextHop.address, nextHop.port))

            initializeChainIfNotFinalDest(
                ChainSocketInitRequest(
                    virtualDestAddr = address,
                    virtualDestPort = endpointInetAddr.port,
                    fromAddr = virtualRouter.localNodeInetAddress
                ),
                nextHop
            )
            println("init done")
        }else {
            super.connect(endpoint, timeout)
        }

    }
}
