package com.ustadmobile.meshrabiya.vnet.socket

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Chain Socket Factory provides a SocketFactory that can connect to addresses on the virtual network.
 * See concept notes in ChainSocketServer.
 *
 * @param virtualRouter local node virtual router
 * @param systemSocketFactory the underlying system socket factory
 */
abstract class ChainSocketFactory: SocketFactory() {

    data class ChainSocketResult(
        val socket: Socket,
        val nextHop: ChainSocketNextHop,
    )

    abstract fun createChainSocket(address: InetAddress, port: Int): ChainSocketResult

}