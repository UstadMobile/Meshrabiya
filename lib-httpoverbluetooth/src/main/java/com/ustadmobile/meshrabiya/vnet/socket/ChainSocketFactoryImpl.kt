package com.ustadmobile.meshrabiya.vnet.socket

import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.ext.readChainInitResponse
import com.ustadmobile.meshrabiya.ext.writeChainSocketInitRequest
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class ChainSocketFactoryImpl(
    private val virtualRouter: VirtualRouter,
    private val systemSocketFactory: SocketFactory = getDefault(),
) : ChainSocketFactory() {


    private fun createSocketForVirtualAddress(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress? = null,
        localPort: Int? = null
    ) : ChainSocketFactory.ChainSocketResult {
        val nextHop = virtualRouter.lookupNextHopForChainSocket(address, port)
        val socket = if(localAddress != null && localPort != null) {
            systemSocketFactory.createSocket(nextHop.address, nextHop.port, localAddress, localPort)
        }else {
            systemSocketFactory.createSocket(nextHop.address, nextHop.port)
        }

        if(!nextHop.isFinalDest) {
            val chainInitRequest = ChainSocketInitRequest(
                virtualDestAddr = address,
                virtualDestPort = port,
                fromAddr = virtualRouter.localNodeInetAddress
            )
            socket.getOutputStream().writeChainSocketInitRequest(chainInitRequest)
            val initResponse = socket.getInputStream().readChainInitResponse()

            if(initResponse.statusCode != 200){
                socket.close()
                throw IOException("Could not init chain socket: status code = ${initResponse.statusCode}")
            }
        }

        return ChainSocketFactory.ChainSocketResult(socket, nextHop)
    }

    private fun InetAddress.isVirtualAddress(): Boolean {
        return prefixMatches(virtualRouter.networkPrefixLength, virtualRouter.localNodeInetAddress)
    }

    override fun createSocket(host: String, port: Int): Socket {
        val address = InetAddress.getByName(host)
        return if(address.isVirtualAddress()) {
            createSocketForVirtualAddress(address, port).socket
        }else {
            systemSocketFactory.createSocket(host, port)
        }
    }

    override fun createSocket(host: String, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val address = InetAddress.getByName(host)
        return if(address.isVirtualAddress()) {
            createSocketForVirtualAddress(address, port, localAddress, localPort).socket
        }else {
            systemSocketFactory.createSocket(host, port, localAddress, localPort)
        }
    }

    override fun createSocket(address: InetAddress, port: Int): Socket {
        return if(address.isVirtualAddress()) {
            createSocketForVirtualAddress(address, port).socket
        }else {
            systemSocketFactory.createSocket(address, port)
        }
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        return if(address.isVirtualAddress()) {
            createSocketForVirtualAddress(address, port, localAddress, localPort).socket
        }else {
            systemSocketFactory.createSocket(address, port, localAddress, localPort)
        }
    }

    override fun createChainSocket(address: InetAddress, port: Int): ChainSocketResult {
        return createSocketForVirtualAddress(address, port)
    }
}